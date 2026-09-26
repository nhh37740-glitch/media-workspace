#!/usr/bin/env bash
#
# Makes the host ready for a build.
#
# The demonstration services are stopped for the duration of the build. This is not tidiness: this
# host was measured with its SSH handshake timing out because two application JVMs, MySQL, a broker
# and a Gradle build with its test JVMs did not fit in memory at once. Integration tests create their
# own schema per run, so stopping the application services does not affect them; MySQL and Kafka stay
# up because they are the dependencies those tests need.
#
# The deployment that runs afterwards restarts everything.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"

log() { printf '[ci-prepare] %s\n' "$*"; }

log "stopping the demonstration services"
DEPLOY_ROOT="$DEPLOY_ROOT" MW_ENV_FILE="$ENV_FILE" \
  bash "$REPO_ROOT/scripts/service.sh" stop all

log "checking the build dependencies"
if ! systemctl is-active --quiet mysql; then
  echo "MySQL is not running; the integration stage needs it" >&2
  exit 1
fi
if ! systemctl is-active --quiet media-kafka; then
  echo "Kafka is not running; the integration stage needs it" >&2
  exit 1
fi

if [ -r "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  export MW_DB_HOST="${DB_HOST:-127.0.0.1}"
  export MW_DB_PORT="${DB_PORT:-3306}"
  export MW_DB_USER="${DB_USER:-media_app}"
  export MW_DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"
  export MW_KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-127.0.0.1:9092}"
  export MW_IT_STORAGE_ROOT="${MW_IT_STORAGE_ROOT:-$DEPLOY_ROOT/var/it-storage}"
fi
export MW_TEST_MEDIA="${MW_TEST_MEDIA:-$DEPLOY_ROOT/var/test-media}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

if [ ! -d "$MW_TEST_MEDIA" ]; then
  log "generating the test media fixtures"
  bash "$REPO_ROOT/scripts/generate-test-media.sh" "$MW_TEST_MEDIA"
fi

free_mb="$(free -m | awk '/^Mem:/ {print $7}')"
log "available memory: ${free_mb} MiB"
if [ "$free_mb" -lt 600 ]; then
  # A warning rather than a failure: the build may still succeed, and refusing to build because the
  # host is busy would be worse than a slow build.
  log "WARNING: less than 600 MiB is available; the build will be slow and may swap"
fi

log "ready"
