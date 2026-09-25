#!/usr/bin/env bash
#
# Creates the demonstration accounts.
#
# Runs the API JAR in a mode with no web server and no scheduler, so it applies migrations, creates
# the accounts and exits. The passwords come from the environment file; they are never passed on a
# command line, never echoed, and never written into the repository.
#
# Re-running is safe: an existing account is left alone unless RESET_PASSWORDS=1 is set, which is
# what stops a restart from silently invalidating a session somebody set up by hand.
set -euo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"
RELEASE="${1:-$(readlink -f "$DEPLOY_ROOT/current")}"

[ -r "$ENV_FILE" ] || { echo "cannot read $ENV_FILE" >&2; exit 1; }
[ -d "$RELEASE" ] || { echo "release directory $RELEASE does not exist" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export LOG_DIR="${LOG_DIR:-$DEPLOY_ROOT/var/logs/api}"
mkdir -p "$LOG_DIR"

jar="$(find "$RELEASE/apps" -maxdepth 1 -name 'media-api-*.jar' | head -1)"
[ -n "$jar" ] || { echo "no API JAR under $RELEASE/apps" >&2; exit 1; }

echo "[bootstrap] applying migrations and creating accounts from $jar"
# Three things are switched off so this process can terminate on its own:
#   web-application-type=none            no HTTP port is opened;
#   mediaworkspace.scheduler.enabled=false
#                                        no maintenance pass runs against a half-migrated schema;
#   mediaworkspace.kafka.listener-auto-startup=false
#                                        the consumer containers are what would otherwise keep a
#                                        finished process alive, because their threads are not
#                                        daemons. This project builds its own listener factory, so
#                                        Boot's spring.kafka.listener.auto-startup never reaches it
#                                        and the project's own switch has to be used instead.
#
# The timeout is a guard, not a mechanism: a command that can hang forever blocks every later
# deployment step, and a bounded failure with a log is far easier to act on than a silent wait.
exec timeout "${BOOTSTRAP_TIMEOUT_SECONDS:-180}" "$JAVA_HOME/bin/java" \
  -Xms64m -Xmx192m -XX:+UseSerialGC \
  -Dfile.encoding=UTF-8 -Duser.timezone=UTC \
  -jar "$jar" \
  --spring.main.web-application-type=none \
  --mediaworkspace.kafka.listener-auto-startup=false \
  --mediaworkspace.scheduler.enabled=false \
  --mediaworkspace.bootstrap.enabled=true \
  --mediaworkspace.bootstrap.reset-passwords="${RESET_PASSWORDS:-false}"
