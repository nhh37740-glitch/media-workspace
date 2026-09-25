#!/usr/bin/env bash
#
# Runs the Gradle integrationTest tasks against the real MySQL, Kafka and FFmpeg on this host.
#
# Credentials are read from the runtime environment file and exported into the test process only.
# They are never printed, never written into the repository, and never passed on a command line
# where another local user could read them from the process table.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${MW_ENV_FILE:-/opt/media-workspace/config/media-workspace.env}"

if [ ! -r "$ENV_FILE" ]; then
  echo "cannot read the environment file at $ENV_FILE" >&2
  echo "run scripts/provision-host.sh first, or set MW_ENV_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
. "$ENV_FILE"
set +a

export MW_DB_HOST="${DB_HOST:-127.0.0.1}"
export MW_DB_PORT="${DB_PORT:-3306}"
export MW_DB_USER="${DB_USER:-media_app}"
export MW_DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"
export MW_KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-127.0.0.1:9092}"
export MW_IT_STORAGE_ROOT="${MW_IT_STORAGE_ROOT:-/opt/media-workspace/var/it-storage}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

mkdir -p "$MW_IT_STORAGE_ROOT"

echo "[integration] database ${MW_DB_USER}@${MW_DB_HOST}:${MW_DB_PORT} (schema per run)"
echo "[integration] kafka ${MW_KAFKA_BOOTSTRAP}"
echo "[integration] storage root ${MW_IT_STORAGE_ROOT}"

cd "$REPO_ROOT"
exec ./gradlew --no-daemon "$@" integrationTest
