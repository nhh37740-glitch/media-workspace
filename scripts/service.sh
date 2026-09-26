#!/usr/bin/env bash
#
# Starts, stops and inspects the API and Worker processes of one deployment.
#
# Two rules this script follows strictly, because ignoring them is how a deployment script destroys
# somebody else's work:
#
#   1. It only ever acts on a process whose recorded start information still matches. A PID file
#      can name a recycled pid, so before signalling anything the script re-reads the live process
#      and refuses to touch it unless it is the same process it started.
#   2. It never kills by name. No `pkill java`, no `pkill ffmpeg`. Another project's JVM on this
#      host must survive this script entirely.
#
# Usage: service.sh <start|stop|restart|status> <api|worker|all> [release-directory]
set -euo pipefail

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/media-workspace}"
ENV_FILE="${MW_ENV_FILE:-$DEPLOY_ROOT/config/media-workspace.env}"
RUN_DIR="$DEPLOY_ROOT/var/run"
LOG_DIR="$DEPLOY_ROOT/var/logs"

ACTION="${1:-status}"
TARGET="${2:-all}"
RELEASE_ARG="${3:-}"

log() { printf '[service] %s\n' "$*"; }
fail() { printf '[service] ERROR: %s\n' "$*" >&2; exit 1; }

# The release to run: an explicit argument, otherwise the current symlink.
resolve_release() {
  if [ -n "$RELEASE_ARG" ]; then
    printf '%s' "$RELEASE_ARG"
    return
  fi
  local current="$DEPLOY_ROOT/current"
  [ -e "$current" ] || fail "no release selected and $current does not exist"
  # -L resolves the symlink even when the target has been replaced, so a rollback takes effect here.
  readlink -f "$current"
}

load_environment() {
  [ -r "$ENV_FILE" ] || fail "cannot read $ENV_FILE; the deployment is not provisioned"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  : "${DB_PASSWORD:?DB_PASSWORD must be set in the environment file}"
  : "${STORAGE_ROOT:?STORAGE_ROOT must be set in the environment file}"
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
  export LOG_DIR
}

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }
pidfile_start_info() { printf '%s/%s.start' "$RUN_DIR" "$1"; }

app_jar() {
  local release="$1" name="$2"
  find "$release/apps" -maxdepth 1 -name "${name}-*.jar" | head -1
}

jvm_opts_file() {
  local release="$1" name="$2"
  printf '%s/config/%s.opts' "$release" "$name"
}

# Whether the pid in the file is still the process this script started.
pid_is_ours() {
  local name="$1" pid
  pid="$(cat "$(pid_file "$name")" 2>/dev/null || true)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  local recorded actual
  recorded="$(cat "$(pidfile_start_info "$name")" 2>/dev/null || true)"
  # The kernel start time of pid 1-signalled process, from /proc, distinguishes a live process from
  # a recycled pid that now belongs to something else entirely.
  actual="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null || true)"
  [ -n "$actual" ] && [ "$recorded" = "$actual" ]
}

start_one() {
  local name="$1" release="$2"
  local jar opts
  jar="$(app_jar "$release" "media-$name")"
  [ -n "$jar" ] || fail "no media-$name JAR found under $release/apps"
  opts="$(jvm_opts_file "$release" "$name")"
  [ -f "$opts" ] || fail "missing JVM options file $opts"

  if pid_is_ours "$name"; then
    log "$name is already running (pid $(cat "$(pid_file "$name")"))"
    return 0
  fi

  mkdir -p "$RUN_DIR" "$LOG_DIR/$name"
  # Pipeline cleanup identifies descendants by JENKINS_NODE_COOKIE; keep that build cookie out of
  # the long-lived JVM. BUILD_ID covers the older Jenkins process-tree marker as well. Limit both
  # overrides to this launch so ordinary build children remain under Jenkins cleanup.
  # shellcheck disable=SC2046
  JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe \
    nohup "$JAVA_HOME/bin/java" $(grep -v '^#' "$opts" | grep -v '^$' | tr '\n' ' ') \
      -jar "$jar" >> "$LOG_DIR/$name/console.log" 2>&1 &
  local pid=$!
  printf '%s' "$pid" > "$(pid_file "$name")"
  awk '{print $22}' "/proc/$pid/stat" > "$(pidfile_start_info "$name")"
  log "$name started (pid $pid) from $jar"
}

stop_one() {
  local name="$1"
  if ! pid_is_ours "$name"; then
    log "$name is not running under this deployment"
    rm -f "$(pid_file "$name")" "$(pidfile_start_info "$name")"
    return 0
  fi
  local pid
  pid="$(cat "$(pid_file "$name")")"
  log "stopping $name (pid $pid)"
  kill -TERM "$pid" 2>/dev/null || true
  for _ in $(seq 1 40); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$(pid_file "$name")" "$(pidfile_start_info "$name")"
      log "$name stopped"
      return 0
    fi
    sleep 0.5
  done
  log "$name did not stop within 20s; sending SIGKILL to the recorded pid only"
  kill -KILL "$pid" 2>/dev/null || true
  rm -f "$(pid_file "$name")" "$(pidfile_start_info "$name")"
}

status_one() {
  local name="$1"
  if pid_is_ours "$name"; then
    printf '%-8s running  pid=%s\n' "$name" "$(cat "$(pid_file "$name")")"
  else
    printf '%-8s stopped\n' "$name"
  fi
}

targets() {
  case "$TARGET" in
    api) printf 'api\n' ;;
    worker) printf 'worker\n' ;;
    all) printf 'api\nworker\n' ;;
    *) fail "unknown target '$TARGET'; expected api, worker or all" ;;
  esac
}

case "$ACTION" in
  start)
    load_environment
    release="$(resolve_release)"
    [ -d "$release" ] || fail "release directory $release does not exist"
    while read -r name; do start_one "$name" "$release"; done < <(targets)
    ;;
  stop)
    # Stopping only reads the pid files; it must not require the environment file to be readable,
    # so that a deployment whose configuration is broken can still be brought down.
    while read -r name; do stop_one "$name"; done < <(targets)
    ;;
  restart)
    bash "$0" stop "$TARGET"
    bash "$0" start "$TARGET" "$RELEASE_ARG"
    ;;
  status)
    while read -r name; do status_one "$name"; done < <(targets)
    ;;
  *)
    fail "unknown action '$ACTION'; expected start, stop, restart or status"
    ;;
esac
