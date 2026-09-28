#!/usr/bin/env bash
#
# Deploys a Jenkins-built release as isolated Docker API and Worker containers.
#
# The current deployment stays live while a candidate runs on separate loopback ports with its own
# mw_it_* schema, Kafka topic prefix, storage, and logs. Only after candidate readiness and the full
# upload/transcode/playback smoke test pass do we stop the old services and switch `current`. Any
# failure during that switch restores the previous release.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"
SOURCE_RELEASE="${MW_PREBUILT_RELEASE:-}"
KAFKA_HOME="${KAFKA_HOME:-/opt/kafka_2.13-3.9.2}"

log() { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

[ -d "$DEPLOY_ROOT" ] || fail "$DEPLOY_ROOT does not exist; run scripts/provision-host.sh first"
[ -r "$ENV_FILE" ] || fail "cannot read $ENV_FILE"
[ -n "$SOURCE_RELEASE" ] || fail "MW_PREBUILT_RELEASE must point to the Jenkins Package-stage output"
[ -d "$SOURCE_RELEASE" ] || fail "Jenkins release directory does not exist: $SOURCE_RELEASE"
SOURCE_RELEASE="$(cd "$SOURCE_RELEASE" && pwd)"
case "$SOURCE_RELEASE" in
  "$REPO_ROOT"/build/release/*) ;;
  *) fail "release is outside this Jenkins workspace's build/release directory" ;;
esac

base_release_name="$(basename "$SOURCE_RELEASE")"
[[ "$base_release_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || fail "unsafe release directory name"
build_token="$(printf '%s-%s' "${BUILD_NUMBER:-manual}" "$(date +%s)" \
  | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]')"
release_name="${base_release_name}-build-${build_token}"
RELEASE="$DEPLOY_ROOT/releases/$release_name"

if ! python3 - "$SOURCE_RELEASE/manifest.json" <<'PY'
import json, re, sys
with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
commit = manifest.get("commit", "")
if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", commit):
    raise SystemExit("release manifest must contain the full checkout commit SHA")
if manifest.get("treeState") != "clean":
    raise SystemExit("deployment requires artifacts built from a clean Jenkins checkout")
PY
then
  fail "release manifest provenance validation failed"
fi
[ -f "$SOURCE_RELEASE/docker/compose.yaml" ] || fail "release is missing its Docker Compose definition"
[ -f "$SOURCE_RELEASE/docker/api/Dockerfile" ] || fail "release is missing its API image module"
[ -f "$SOURCE_RELEASE/docker/api/media-api.jar" ] || fail "release is missing its API binary"
[ -f "$SOURCE_RELEASE/docker/worker/Dockerfile" ] || fail "release is missing its Worker image module"
[ -f "$SOURCE_RELEASE/docker/worker/media-worker.jar" ] || fail "release is missing its Worker binary"
( cd "$SOURCE_RELEASE" && sha256sum --check --quiet SHA256SUMS ) \
  || fail "the Jenkins release archive is damaged"

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
: "${DB_PASSWORD:?DB_PASSWORD must be set in the protected runtime config}"
: "${DB_USER:?DB_USER must be set in the protected runtime config}"
: "${DB_NAME:?DB_NAME must be set in the protected runtime config}"
: "${STORAGE_ROOT:?STORAGE_ROOT must be set in the protected runtime config}"
[ "${DB_HOST:-127.0.0.1}" = "127.0.0.1" ] \
  || fail "MySQL must remain bound to the host loopback address"
[ "${DB_PORT:-3306}" = "3306" ] \
  || fail "the Docker host-network runtime expects the existing loopback MySQL port 3306"
[ "${KAFKA_BOOTSTRAP:-127.0.0.1:9092}" = "127.0.0.1:9092" ] \
  || fail "Kafka must remain on its existing host-loopback listener"
[ "${HTTP_PORT:-8080}" = "8080" ] \
  || fail "the API port must remain 8080 to match the Nginx upstream"
[ "${WORKER_HEALTH_PORT:-8090}" = "8090" ] \
  || fail "the Worker health port must remain 8090"
[ "$STORAGE_ROOT" = "$DEPLOY_ROOT/var/storage" ] \
  || fail "STORAGE_ROOT must be the persistent deployment storage directory"

previous=""
if [ -L "$DEPLOY_ROOT/current" ]; then
  previous="$(readlink -f "$DEPLOY_ROOT/current" || true)"
fi
[ -n "$previous" ] && [ -d "$previous" ] \
  || fail "there is no healthy prior release to preserve for rollback"

log "checking the live release before preparing its replacement"
old_api_url="http://127.0.0.1:${HTTP_PORT:-8080}"
old_worker_url="http://127.0.0.1:${WORKER_HEALTH_PORT:-8090}"
curl -fsS "$old_api_url/actuator/health/readiness" >/dev/null \
  || fail "the current API is not ready; current release was left untouched"
curl -fsS "$old_worker_url/actuator/health/readiness" >/dev/null \
  || fail "the current Worker is not ready; current release was left untouched"

APP_UID="$(id -u)"
APP_GID="$(id -g)"
KAFKA_TOPIC_PREFIX="${KAFKA_TOPIC_PREFIX:-}"
candidate_project=""
candidate_db=""
candidate_db_created=0
candidate_root=""
candidate_log_root=""
candidate_topic_prefix=""
candidate_api_port=""
candidate_worker_port=""
transition_started=0
deployment_succeeded=0

compose_for() {
  local project="$1" release="$2" db="$3" storage="$4" log_root="$5"
  local topic_prefix="$6" api_port="$7" worker_port="$8" concurrency="$9"
  local api_mem="${10}" worker_mem="${11}" api_heap="${12}" worker_heap="${13}"
  shift 13
  local tag
  tag="$(basename "$release")"
  sudo env \
    MW_ENV_FILE="$ENV_FILE" \
    MEDIA_API_IMAGE="media-workspace/api:$tag" \
    MEDIA_WORKER_IMAGE="media-workspace/worker:$tag" \
    MEDIA_DB_NAME="$db" \
    MEDIA_KAFKA_TOPIC_PREFIX="$topic_prefix" \
    MEDIA_STORAGE_HOST_PATH="$storage" \
    MEDIA_API_LOG_HOST_PATH="$log_root/api" \
    MEDIA_WORKER_LOG_HOST_PATH="$log_root/worker" \
    MEDIA_API_PORT="$api_port" \
    MEDIA_WORKER_HEALTH_PORT="$worker_port" \
    MEDIA_WORKER_CONCURRENCY="$concurrency" \
    MEDIA_API_MEM_LIMIT="$api_mem" \
    MEDIA_WORKER_MEM_LIMIT="$worker_mem" \
    MEDIA_API_HEAP_INITIAL="$api_heap" \
    MEDIA_API_HEAP="$api_heap" \
    MEDIA_WORKER_HEAP_INITIAL="$worker_heap" \
    MEDIA_WORKER_HEAP="$worker_heap" \
    MEDIA_UID="$APP_UID" \
    MEDIA_GID="$APP_GID" \
    docker compose --project-name "$project" --file "$release/docker/compose.yaml" "$@"
}

allocate_loopback_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

create_canary_topics() {
  local topic partitions
  [ -x "$KAFKA_HOME/bin/kafka-topics.sh" ] \
    || fail "Kafka CLI is unavailable at $KAFKA_HOME/bin/kafka-topics.sh"
  for topic_spec in \
      media.task.requested.v1:3 media.task.result.v1:3 media.events.dlq.v1:1; do
    topic="${topic_spec%%:*}"
    partitions="${topic_spec##*:}"
    "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 \
      --create --if-not-exists --topic "${candidate_topic_prefix}${topic}" \
      --partitions "$partitions" --replication-factor 1 >/dev/null
  done
}

wait_ready() {
  local base_url="$1" label="$2"
  for _ in $(seq 1 60); do
    if curl -fsS "$base_url/actuator/health/readiness" >/dev/null 2>&1; then
      log "$label readiness is UP"
      return 0
    fi
    sleep 2
  done
  return 1
}

validate_candidate_database_name() {
  [[ "$candidate_db" =~ ^mw_it_deploy_[a-z0-9]+$ ]] \
    && [ "${#candidate_db}" -le 64 ]
}

create_canary_database() {
  validate_candidate_database_name \
    || fail "refusing unsafe generated canary database name"
  local sql
  sql="CREATE DATABASE \`$candidate_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
  sudo mysql --protocol=socket --batch --skip-column-names --execute="$sql" >/dev/null
  candidate_db_created=1
}

drop_canary_database() {
  [ "$candidate_db_created" = "1" ] || return 0
  validate_candidate_database_name \
    || { log "ERROR: refusing unsafe canary database name during cleanup"; return 1; }
  local sql
  sql="DROP DATABASE IF EXISTS \`$candidate_db\`"
  if ! sudo mysql --protocol=socket --batch --skip-column-names --execute="$sql" >/dev/null; then
    log "ERROR: could not drop canary database $candidate_db"
    return 1
  fi
  candidate_db_created=0
}

cleanup_candidate() {
  [ -n "$candidate_project" ] || return 0
  log "removing isolated canary containers and test resources"
  if ! compose_for "$candidate_project" "$RELEASE" "$candidate_db" \
    "$candidate_root/storage" "$candidate_log_root" "$candidate_topic_prefix" \
    "$candidate_api_port" "$candidate_worker_port" 1 320m 384m 128m 128m \
    down --remove-orphans >/dev/null 2>&1; then
    log "ERROR: could not stop canary containers; leaving their database and storage intact"
    return 1
  fi
  if ! drop_canary_database; then
    return 1
  fi
  if [ -n "$candidate_topic_prefix" ] && [ -x "$KAFKA_HOME/bin/kafka-topics.sh" ]; then
    for topic in media.task.requested.v1 media.task.result.v1 media.events.dlq.v1; do
      "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 \
        --delete --topic "${candidate_topic_prefix}${topic}" >/dev/null 2>&1 || true
    done
  fi
  case "$candidate_root" in
    "$DEPLOY_ROOT"/var/candidates/mw-canary-*)
      [ "$candidate_root" = "$DEPLOY_ROOT/var/candidates" ] || rm -rf -- "$candidate_root"
      ;;
  esac
  candidate_project=""
}

switch_current() {
  local target="$1"
  ln -sfn "$target" "$DEPLOY_ROOT/current.tmp"
  mv -Tf "$DEPLOY_ROOT/current.tmp" "$DEPLOY_ROOT/current"
}

rollback_previous() {
  set +e
  log "stopping the attempted release and restoring $(basename "$previous")"
  DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
    bash "$REPO_ROOT/scripts/service.sh" stop all "$RELEASE"
  switch_current "$previous"
  DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
    bash "$REPO_ROOT/scripts/service.sh" start all "$previous"
  curl -fsS "http://127.0.0.1:${HTTP_PORT:-8080}/actuator/health/readiness" >/dev/null
  log "previous release rollback completed"
}

on_exit() {
  local status=$?
  trap - EXIT
  if [ "$status" -ne 0 ] && [ "$transition_started" = "1" ] \
      && [ "$deployment_succeeded" != "1" ]; then
    rollback_previous || log "ERROR: automatic rollback needs operator attention"
  fi
  cleanup_candidate
  exit "$status"
}
trap on_exit EXIT

log "installing immutable release $release_name"
mkdir -p "$DEPLOY_ROOT/releases" "$DEPLOY_ROOT/var/logs/api" "$DEPLOY_ROOT/var/logs/worker"
mkdir -p "$DEPLOY_ROOT/var/storage"
[ ! -e "$RELEASE" ] || fail "release target already exists: $RELEASE"
cp -a "$SOURCE_RELEASE" "$RELEASE"
mkdir -p "$RELEASE/docker/api" "$RELEASE/docker/worker"
for writable_path in "$DEPLOY_ROOT/var/storage" "$DEPLOY_ROOT/var/logs/api" \
    "$DEPLOY_ROOT/var/logs/worker"; do
  [ -w "$writable_path" ] || fail "the Jenkins service account cannot write $writable_path"
done

log "building only the two runtime images from the verified binary release"
compose_for "mw-build-$build_token" "$RELEASE" "$DB_NAME" "$STORAGE_ROOT" \
  "$DEPLOY_ROOT/var/logs" "$KAFKA_TOPIC_PREFIX" "${HTTP_PORT:-8080}" \
  "${WORKER_HEALTH_PORT:-8090}" "${WORKER_CONCURRENCY:-1}" 384m 512m 192m 192m \
  build api worker

candidate_project="mw-canary-$build_token"
candidate_db="mw_it_deploy_$build_token"
validate_candidate_database_name \
  || fail "generated canary database name is invalid or longer than MySQL's 64-character limit"
candidate_root="$DEPLOY_ROOT/var/candidates/$candidate_project"
candidate_log_root="$DEPLOY_ROOT/var/logs/canary/$build_token"
candidate_topic_prefix="mw-canary-$build_token-"
candidate_api_port="$(allocate_loopback_port)"
candidate_worker_port="$(allocate_loopback_port)"
while [ "$candidate_api_port" = "$candidate_worker_port" ] \
    || [ "$candidate_api_port" = "${HTTP_PORT:-8080}" ] \
    || [ "$candidate_api_port" = "${WORKER_HEALTH_PORT:-8090}" ] \
    || [ "$candidate_api_port" = "8088" ] \
    || [ "$candidate_worker_port" = "${HTTP_PORT:-8080}" ] \
    || [ "$candidate_worker_port" = "${WORKER_HEALTH_PORT:-8090}" ] \
    || [ "$candidate_worker_port" = "8088" ]; do
  candidate_api_port="$(allocate_loopback_port)"
  candidate_worker_port="$(allocate_loopback_port)"
done
mkdir -p "$candidate_root/storage" "$candidate_log_root/api" "$candidate_log_root/worker"
[ -w "$candidate_root/storage" ] && [ -w "$candidate_log_root/api" ] \
  && [ -w "$candidate_log_root/worker" ] \
  || fail "the Jenkins service account cannot write the isolated canary paths"

log "creating the isolated canary database and Kafka topics"
create_canary_database
MW_DB_NAME_OVERRIDE="$candidate_db" \
MW_STORAGE_ROOT_OVERRIDE="$candidate_root/storage" \
MW_LOG_DIR_OVERRIDE="$candidate_log_root/api" \
  DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/bootstrap-users.sh" "$RELEASE"
create_canary_topics

log "starting the replacement on private loopback ports $candidate_api_port/$candidate_worker_port"
compose_for "$candidate_project" "$RELEASE" "$candidate_db" \
  "$candidate_root/storage" "$candidate_log_root" "$candidate_topic_prefix" \
  "$candidate_api_port" "$candidate_worker_port" 1 320m 384m 128m 128m \
  up -d api worker
candidate_url="http://127.0.0.1:$candidate_api_port"
wait_ready "$candidate_url" "candidate API" \
  || fail "candidate API readiness failed; the existing release is still serving"
wait_ready "http://127.0.0.1:$candidate_worker_port" "candidate Worker" \
  || fail "candidate Worker readiness failed; the existing release is still serving"

log "running the full smoke test against isolated candidate state"
BASE_URL="$candidate_url" API_URL="$candidate_url" \
  MW_ENV_FILE="$ENV_FILE" MW_TEST_MEDIA="$DEPLOY_ROOT/var/test-media" \
  SMOKE_TIMEOUT_SECONDS=240 bash "$REPO_ROOT/scripts/smoke-test.sh"
cleanup_candidate
log "candidate passed health and end-to-end smoke; the existing release remains available for rollback"

transition_started=1
log "stopping the current services only after candidate verification"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/service.sh" stop all "$previous"

log "applying production database migrations with the verified release"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/bootstrap-users.sh" "$RELEASE"

log "switching to Docker release $release_name"
switch_current "$RELEASE"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/service.sh" start all "$RELEASE"

api_url="http://127.0.0.1:${HTTP_PORT:-8080}"
worker_url="http://127.0.0.1:${WORKER_HEALTH_PORT:-8090}"
wait_ready "$api_url" "production API" || fail "production API readiness failed"
wait_ready "$worker_url" "production Worker" || fail "production Worker readiness failed"

log "running the production smoke test through Nginx"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/smoke-test.sh"

deployment_succeeded=1
log "Docker deployment $release_name passed health and end-to-end smoke"
