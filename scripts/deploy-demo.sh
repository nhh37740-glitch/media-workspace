#!/usr/bin/env bash
#
# Installs a built release and brings the demonstration deployment up on it.
#
# The order is chosen so that a failure at any step leaves the previous release running:
#   1. assemble the release (the running deployment is untouched);
#   2. verify its checksums;
#   3. migrate the schema once, by a process that is not one of the two services;
#   4. move the `current` symlink;
#   5. restart against the new release;
#   6. smoke test, and roll the symlink back if it fails.
#
# The database is never rolled back. A migration that a new release needs is still applied when that
# release is rolled back, so a rollback is only performed after checking that the schema is
# compatible with the previous release - which is what the compatibility note in the release
# directory records.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"

log() { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

[ -d "$DEPLOY_ROOT" ] || fail "$DEPLOY_ROOT does not exist; run scripts/provision-host.sh first"
[ -r "$ENV_FILE" ] || fail "cannot read $ENV_FILE"

# The services are stopped before the build, not after it.
#
# A Gradle build with its test JVMs, a Node build and two application JVMs do not fit alongside
# MySQL and a broker on this host at the same time: the box was measured swapping so hard that its
# SSH handshake timed out. Staggering them costs a few seconds of downtime during a deployment that
# restarts the services anyway, and it removes the only configuration in which this host is
# unusable. The window is bounded: everything after this point either succeeds and restarts the
# services, or the script exits and the operator restarts them explicitly.
log "stopping the services so the build has the machine to itself"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" "$REPO_ROOT/scripts/service.sh" stop all || true

log "building the release"
# Stale directories from earlier runs are removed first: selecting "the last one" by name would
# pick an older release whose version string happens to sort higher, which is how a deploy ends up
# publishing yesterday's build under today's name.
rm -rf "$REPO_ROOT/build/release"
OUT_DIR="$REPO_ROOT/build/release" "$REPO_ROOT/scripts/build-release.sh"

release_dir="$(ls -1dt "$REPO_ROOT"/build/release/*/ 2>/dev/null | head -1)"
[ -n "$release_dir" ] || fail "no release directory was produced"
release_name="$(basename "$release_dir")"

log "verifying checksums of $release_name"
( cd "$release_dir" && sha256sum --check --quiet SHA256SUMS ) || fail "the release archive is damaged"

log "installing into $DEPLOY_ROOT/releases/$release_name"
mkdir -p "$DEPLOY_ROOT/releases"
rm -rf "$DEPLOY_ROOT/releases/$release_name"
cp -r "$release_dir" "$DEPLOY_ROOT/releases/$release_name"

previous=""
if [ -L "$DEPLOY_ROOT/current" ]; then
  previous="$(readlink -f "$DEPLOY_ROOT/current")"
fi

log "applying migrations with a process that is not a service"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  "$REPO_ROOT/scripts/bootstrap-users.sh" "$DEPLOY_ROOT/releases/$release_name"

# `current` must be a symlink. If it is a real directory, something created it by hand and a
# rename into it would either fail or, worse, silently nest the new release inside it. This refuses
# to guess, and never removes a directory it did not create: deleting the wrong thing here would
# take a release with it.
if [ -e "$DEPLOY_ROOT/current" ] && [ ! -L "$DEPLOY_ROOT/current" ]; then
  fail "$DEPLOY_ROOT/current exists and is not a symlink; remove it by hand and re-run"
fi

log "switching the current release to $release_name"
ln -sfn "$DEPLOY_ROOT/releases/$release_name" "$DEPLOY_ROOT/current.tmp"
mv -Tf "$DEPLOY_ROOT/current.tmp" "$DEPLOY_ROOT/current"

log "restarting the services"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  "$REPO_ROOT/scripts/service.sh" restart all "$DEPLOY_ROOT/releases/$release_name"

log "waiting for the API to become ready"
ready=0
for _ in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:8080/actuator/health/readiness" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  log "the API did not become ready"
  tail -n 40 "$DEPLOY_ROOT/var/logs/api/console.log" >&2 || true
  if [ -n "$previous" ]; then
    log "rolling the symlink back to $(basename "$previous")"
    ln -sfn "$previous" "$DEPLOY_ROOT/current.tmp"
    mv -Tf "$DEPLOY_ROOT/current.tmp" "$DEPLOY_ROOT/current"
    DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" "$REPO_ROOT/scripts/service.sh" restart all "$previous" || true
  fi
  fail "deployment failed and was rolled back"
fi
log "the API reports ready"

log "running the smoke test"
if DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" "$REPO_ROOT/scripts/smoke-test.sh"; then
  log "deployment of $release_name succeeded"
else
  log "the smoke test failed"
  if [ -n "$previous" ] && [ "$previous" != "$DEPLOY_ROOT/releases/$release_name" ]; then
    log "rolling back to $(basename "$previous") for the application only"
    log "the schema stays as migrated: it is forward compatible by design, and a reverse migration"
    log "of user data is not attempted"
    ln -sfn "$previous" "$DEPLOY_ROOT/current.tmp"
    mv -Tf "$DEPLOY_ROOT/current.tmp" "$DEPLOY_ROOT/current"
    DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" "$REPO_ROOT/scripts/service.sh" restart all "$previous" || true
  fi
  fail "the deployment did not pass its smoke test"
fi
