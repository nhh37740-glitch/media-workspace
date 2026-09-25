#!/usr/bin/env bash
#
# Exercises the whole business chain against a running deployment and exits non-zero on the first
# failure, so a pipeline stage can gate on it.
#
# The chain is the one a user performs: sign in, create a space, upload a real clip in chunks,
# complete it, wait for the worker to transcode it, then play it back. Nothing is stubbed and no
# step is skipped when it fails: a stage that cannot complete the chain fails the build.
#
# Credentials come from the environment file. Nothing secret is printed: the transcript shows status
# codes and identifiers, never a password, a cookie or a share token.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8088}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"
TEST_MEDIA="${MW_TEST_MEDIA:-$DEPLOY_ROOT/var/test-media}"
COOKIE_JAR="${COOKIE_JAR:-$(mktemp)}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-180}"
KEEP_WORK="${KEEP_WORK:-0}"

step() { printf '\n=== %s\n' "$*"; }
ok() { printf '  ok   %s\n' "$*"; }
bad() { printf '  FAIL %s\n' "$*" >&2; exit 1; }

cleanup() {
  [ "$KEEP_WORK" = "1" ] || rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

[ -r "$ENV_FILE" ] || bad "cannot read $ENV_FILE"
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
: "${MW_PASSWORD_OWNER:?MW_PASSWORD_OWNER is not set in the environment file}"

CSRF_HEADER="X-CSRF-TOKEN"
csrf=""

# Fetches a fresh CSRF token. The token is bound to the session, so it is re-read after every login
# and logout rather than reused.
refresh_csrf() {
  local response
  response="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/api/v1/auth/csrf")"
  csrf="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
}

# Makes a request and fails the run when the status is not the expected one.
# $1 method, $2 path, $3 expected status, $4 optional body, $5.. extra curl arguments
request() {
  local method="$1" path="$2" expected="$3" body="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-sS -o /tmp/smoke-body.json -w '%{http_code}' -X "$method"
              -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "$CSRF_HEADER: $csrf")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local status
  status="$(curl "${args[@]}" "$@" "$BASE_URL$path")"
  if [ "$status" != "$expected" ]; then
    printf '  response body: %s\n' "$(head -c 400 /tmp/smoke-body.json)" >&2
    bad "$method $path returned $status, expected $expected"
  fi
  cat /tmp/smoke-body.json
}

json_field() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

step "1. health"
for _ in $(seq 1 30); do
  if curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1 || curl -sf "${API_URL:-http://127.0.0.1:8080}/actuator/health" >/dev/null 2>&1; then
    ok "the service answers a health probe"
    break
  fi
  sleep 2
done
curl -sf "${API_URL:-http://127.0.0.1:8080}/actuator/health/readiness" >/dev/null \
  || bad "the API readiness probe does not report UP"
ok "readiness probe is UP"

step "2. sign in"
refresh_csrf
login_body="$(request POST /api/v1/auth/login 200 \
  "{\"username\":\"owner\",\"password\":\"$MW_PASSWORD_OWNER\"}")"
user_id="$(printf '%s' "$login_body" | json_field '["userId"]')"
ok "signed in as owner ($user_id)"
refresh_csrf

step "3. create a space"
space_body="$(request POST /api/v1/spaces 201 "{\"name\":\"smoke-$RANDOM\"}")"
space_id="$(printf '%s' "$space_body" | json_field '["spaceId"]')"
ok "space $space_id"

step "4. upload a real clip in chunks"
source_file="$TEST_MEDIA/tiny.mp4"
[ -f "$source_file" ] || bad "test media $source_file is absent; run scripts/generate-test-media.sh"
size="$(stat -c%s "$source_file")"
sha="$(sha256sum "$source_file" | cut -d' ' -f1)"

idempotency_key="smoke-$(date +%s)-$RANDOM"
create_body="$(request POST "/api/v1/spaces/$space_id/uploads" 201 \
  "{\"filename\":\"smoke.mp4\",\"sizeBytes\":$size,\"sha256\":\"$sha\",\"title\":\"smoke test\"}" \
  -H "Idempotency-Key: $idempotency_key")"
upload_id="$(printf '%s' "$create_body" | json_field '["uploadId"]')"
chunk_size="$(printf '%s' "$create_body" | json_field '["chunkSize"]')"
chunk_count="$(printf '%s' "$create_body" | json_field '["chunkCount"]')"
ok "session $upload_id, $chunk_count chunk(s) of $chunk_size bytes"

# The same idempotency key with the same body must return the same session, not a second one.
replay_body="$(request POST "/api/v1/spaces/$space_id/uploads" 200 \
  "{\"filename\":\"smoke.mp4\",\"sizeBytes\":$size,\"sha256\":\"$sha\",\"title\":\"smoke test\"}" \
  -H "Idempotency-Key: $idempotency_key")"
replay_id="$(printf '%s' "$replay_body" | json_field '["uploadId"]')"
[ "$replay_id" = "$upload_id" ] || bad "the idempotent replay created a different session"
ok "the same key returned the same session"

work_dir="$(mktemp -d)"
for index in $(seq 0 $((chunk_count - 1))); do
  offset=$((index * chunk_size))
  part="$work_dir/part-$index"
  dd if="$source_file" of="$part" bs=1 skip="$offset" count="$chunk_size" status=none
  part_sha="$(sha256sum "$part" | cut -d' ' -f1)"
  local_status="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "$CSRF_HEADER: $csrf" \
    -H "X-Chunk-SHA256: $part_sha" -H 'Content-Type: application/octet-stream' \
    --data-binary "@$part" "$BASE_URL/api/v1/uploads/$upload_id/chunks/$index")"
  case "$local_status" in
    201|200) ok "chunk $index stored ($local_status)" ;;
    *) bad "chunk $index returned $local_status" ;;
  esac
done
[ "$KEEP_WORK" = "1" ] || rm -rf "$work_dir"

step "5. complete and wait for the worker"
complete_body="$(request POST "/api/v1/uploads/$upload_id/complete" 202 '{}')"
ok "merge accepted (status $(printf '%s' "$complete_body" | json_field '["status"]'))"

media_id=""
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  status_body="$(request GET "/api/v1/uploads/$upload_id" 200)"
  state="$(printf '%s' "$status_body" | json_field '["status"]')"
  if [ "$state" = "COMPLETED" ]; then
    media_id="$(printf '%s' "$status_body" | json_field '["mediaId"]')"
    break
  fi
  [ "$state" = "FAILED" ] && bad "the merge failed: $(printf '%s' "$status_body" | json_field '["errorCode"]')"
  sleep 2
done
[ -n "$media_id" ] || bad "the upload did not complete within ${TIMEOUT_SECONDS}s"
ok "media $media_id published"

task_body="$(request GET "/api/v1/media/$media_id" 200)"
task_id="$(printf '%s' "$task_body" | json_field '["taskId"]')"
[ -n "$task_id" ] || bad "the media has no task"
ok "task $task_id"

while [ "$(date +%s)" -lt "$deadline" ]; do
  state_body="$(request GET "/api/v1/tasks/$task_id" 200)"
  state="$(printf '%s' "$state_body" | json_field '["state"]')"
  progress="$(printf '%s' "$state_body" | json_field '["progress"]')"
  printf '  task state %s (%s%%)\n' "$state" "$progress"
  case "$state" in
    SUCCEEDED) break ;;
    FAILED|CANCELLED) bad "the task ended as $state: $(printf '%s' "$state_body" | json_field '.get("errorCode")')" ;;
  esac
  sleep 3
done
[ "$state" = "SUCCEEDED" ] || bad "the task did not succeed within ${TIMEOUT_SECONDS}s"
ok "transcoding succeeded"

step "6. play the result"
length="$(curl -sS -o /dev/null -w '%{size_download}' -b "$COOKIE_JAR" \
  "$BASE_URL/api/v1/media/$media_id/content")"
[ "$length" -gt 0 ] || bad "the content endpoint returned no bytes"
ok "full download is $length bytes"

range_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
  -H 'Range: bytes=0-99' "$BASE_URL/api/v1/media/$media_id/content")"
[ "$range_status" = "206" ] || bad "a range request returned $range_status, expected 206"
ok "a range request returned 206"

poster_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
  "$BASE_URL/api/v1/media/$media_id/poster")"
[ "$poster_status" = "200" ] || bad "the poster returned $poster_status"
ok "the poster is served"

step "7. share and revoke"
expires="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
share_body="$(request POST "/api/v1/media/$media_id/shares" 201 "{\"expiresAt\":\"$expires\"}")"
share_id="$(printf '%s' "$share_body" | json_field '["shareId"]')"
share_token="$(printf '%s' "$share_body" | json_field '["token"]')"
ok "share $share_id created (the token is not printed)"

# A share is redeemed by a visitor with no account, so the exchange runs in its own cookie jar with
# its own CSRF token. The token is bound to the session that requested it, so reusing the signed-in
# user's token here would be rejected - which is the point of the check, not an obstacle to work
# around. This mirrors what the share landing page does: fetch a token, then exchange.
share_cookie="$(mktemp)"
share_csrf="$(curl -sS -b "$share_cookie" -c "$share_cookie" "$BASE_URL/api/v1/auth/csrf" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
share_ok="$(curl -sS -o /dev/null -w '%{http_code}' -b "$share_cookie" -c "$share_cookie" \
  -H "$CSRF_HEADER: $share_csrf" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$share_token\"}" "$BASE_URL/api/v1/public/share-access")"
[ "$share_ok" = "200" ] || bad "redeeming the share returned $share_ok"
ok "the share token was exchanged for a session"

# A token minted for a different session must not be accepted: that is what makes the CSRF check
# worth having rather than a formality.
cross_session_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$share_cookie" -c "$share_cookie" \
  -H "$CSRF_HEADER: $csrf" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$share_token\"}" "$BASE_URL/api/v1/public/share-access")"
[ "$cross_session_status" = "403" ] \
  || bad "a CSRF token from another session was accepted ($cross_session_status)"
ok "a CSRF token from another session is rejected"

shared_bytes="$(curl -sS -o /dev/null -w '%{size_download}' -b "$share_cookie" \
  "$BASE_URL/api/v1/public/share-access/content")"
[ "$shared_bytes" -gt 0 ] || bad "the share session could not play the media"
ok "the share session played $shared_bytes bytes"

request DELETE "/api/v1/shares/$share_id" 204 >/dev/null
revoked_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$share_cookie" \
  "$BASE_URL/api/v1/public/share-access/content")"
[ "$revoked_status" = "404" ] || bad "playback after revocation returned $revoked_status, expected 404"
ok "revocation stops new requests"
rm -f "$share_cookie"

step "8. sign out"
request POST /api/v1/auth/logout 204 '{}' >/dev/null
me_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE_URL/api/v1/auth/me")"
[ "$me_status" = "401" ] || bad "/auth/me after logout returned $me_status, expected 401"
ok "the session is invalid after logout"

printf '\nSMOKE TEST PASSED\n'
