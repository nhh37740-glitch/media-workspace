#!/usr/bin/env bash
#
# Starts, stops and inspects the API and Worker containers of one deployment.
#
# Docker-managed releases use their own Compose project label, so this script never operates on
# another project's containers. Older releases created before Docker support continue to use the
# recorded-PID path below during rollback.
#
# The legacy path still follows two rules strictly:
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

release_uses_docker() {
  [ -f "$1/docker/compose.yaml" ]
}

docker_container_ids() {
  local name="$1"
  sudo docker ps -aq \
    --filter 'label=com.docker.compose.project=media-workspace' \
    --filter "label=com.docker.compose.service=$name"
}

stop_container_one() {
  local name="$1" ids
  ids="$(docker_container_ids "$name")"
  if [ -z "$ids" ]; then
    log "$name container is not running"
    return 0
  fi
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    log "stopping $name container"
    sudo docker stop --time 60 "$id" >/dev/null
  done <<< "$ids"
}

container_status_one() {
  local name="$1" ids id state
  ids="$(sudo docker ps -aq \
    --filter 'label=com.docker.compose.project=media-workspace' \
    --filter "label=com.docker.compose.service=$name")"
  if [ -z "$ids" ]; then
    printf '%-8s stopped\n' "$name"
    return 0
  fi
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    state="$(sudo docker inspect --format '{{.State.Status}}' "$id")"
    printf '%-8s %-8s container=%s\n' "$name" "$state" "$id"
  done <<< "$ids"
}

start_docker_release() {
  local release="$1"
  local release_name
  release_name="$(basename "$release")"
  shift
  sudo env \
    DEPLOY_ROOT="$DEPLOY_ROOT" \
    MW_ENV_FILE="$ENV_FILE" \
    MEDIA_API_IMAGE="media-workspace/api:$release_name" \
    MEDIA_WORKER_IMAGE="media-workspace/worker:$release_name" \
    MEDIA_DB_NAME="$DB_NAME" \
    MEDIA_KAFKA_TOPIC_PREFIX="${KAFKA_TOPIC_PREFIX:-}" \
    MEDIA_STORAGE_HOST_PATH="$STORAGE_ROOT" \
    MEDIA_API_LOG_HOST_PATH="$LOG_DIR/api" \
    MEDIA_WORKER_LOG_HOST_PATH="$LOG_DIR/worker" \
    MEDIA_API_PORT="${HTTP_PORT:-8080}" \
    MEDIA_WORKER_HEALTH_PORT="${WORKER_HEALTH_PORT:-8090}" \
    MEDIA_WORKER_CONCURRENCY="${WORKER_CONCURRENCY:-1}" \
    MEDIA_UID="$(id -u)" \
    MEDIA_GID="$(id -g)" \
    docker compose --project-name media-workspace \
      --file "$release/docker/compose.yaml" up -d "$@"
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
    if release_uses_docker "$release"; then
      case "$TARGET" in
        api) start_docker_release "$release" api ;;
        worker) start_docker_release "$release" worker ;;
        all) start_docker_release "$release" api worker ;;
        *) fail "unknown target '$TARGET'; expected api, worker or all" ;;
      esac
      log "$TARGET Docker service(s) started from $release"
    else
      while read -r name; do start_one "$name" "$release"; done < <(targets)
    fi
    ;;
  stop)
    release="$RELEASE_ARG"
    if [ -z "$release" ] && { [ -e "$DEPLOY_ROOT/current" ] || [ -L "$DEPLOY_ROOT/current" ]; }; then
      release="$(readlink -f "$DEPLOY_ROOT/current" || true)"
    fi
    # Container stops use Docker's project/service labels and do not need application credentials.
    while read -r name; do
      if [ -n "$release" ] && release_uses_docker "$release"; then
        stop_container_one "$name"
      else
        stop_one "$name"
      fi
    done < <(targets)
    ;;
  restart)
    bash "$0" stop "$TARGET" "$RELEASE_ARG"
    bash "$0" start "$TARGET" "$RELEASE_ARG"
    ;;
  status)
    release="$RELEASE_ARG"
    if [ -z "$release" ] && { [ -e "$DEPLOY_ROOT/current" ] || [ -L "$DEPLOY_ROOT/current" ]; }; then
      release="$(readlink -f "$DEPLOY_ROOT/current" || true)"
    fi
    while read -r name; do
      if [ -n "$release" ] && release_uses_docker "$release"; then
        container_status_one "$name"
      else
        status_one "$name"
      fi
    done < <(targets)
    ;;
  *)
    fail "unknown action '$ACTION'; expected start, stop, restart or status"
    ;;
esac
