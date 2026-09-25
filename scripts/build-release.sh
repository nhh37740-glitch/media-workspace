#!/usr/bin/env bash
#
# Builds the deliverables and assembles the versioned release layout.
#
# Layout, per the contract:
#   apps/     the two executable JARs
#   libs/     the library JARs, for study and cross-repository reuse
#   web/      the built front end
#   config/   configuration templates; no secrets
#   scripts/  the scripts that start, stop and check the deployment
#   manifest.json, SHA256SUMS
#
# The manifest is generated from what was actually built: the commit is read from Git, the tool
# versions from the tools themselves, and every digest from the file on disk. Nothing in it is typed
# by hand, so a manifest can never claim a build that did not happen.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

OUT_DIR="${OUT_DIR:-$REPO_ROOT/build/release}"
SKIP_TESTS="${SKIP_TESTS:-0}"
SKIP_WEB="${SKIP_WEB:-0}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

log() { printf '[release] %s\n' "$*"; }

read_version() {
  grep -E '^version=' gradle.properties | head -1 | cut -d= -f2 | tr -d '[:space:]'
}

read_commit() {
  if [ -n "${GIT_COMMIT:-}" ]; then
    printf '%s' "$GIT_COMMIT"
  elif git rev-parse --verify HEAD >/dev/null 2>&1; then
    git rev-parse HEAD
  else
    printf 'unknown'
  fi
}

# Whether the working tree differs from the recorded commit.
#
# A manifest that names a commit the build did not come from is the fabricated provenance the
# delivery contract forbids. Rather than refusing to build - which would block a legitimate local
# iteration - the state is recorded: the commit is marked, and the modified paths are listed. A
# pipeline checkout is clean, so a released artifact carries an exact commit and no marker.
dirty_state() {
  if git rev-parse --verify HEAD >/dev/null 2>&1 && ! git diff --quiet HEAD -- 2>/dev/null; then
    printf 'dirty'
  elif git rev-parse --verify HEAD >/dev/null 2>&1 \
       && [ -n "$(git ls-files --others --exclude-standard | head -1)" ]; then
    printf 'dirty'
  else
    printf 'clean'
  fi
}

VERSION="$(read_version)"
COMMIT="$(read_commit)"
TREE_STATE="$(dirty_state)"
SHORT_COMMIT="${COMMIT:0:7}"
BUILD_NUMBER="${BUILD_NUMBER:-local}"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RELEASE_NAME="${VERSION}-${SHORT_COMMIT}"
RELEASE_DIR="$OUT_DIR/${RELEASE_NAME}"

if [ "$TREE_STATE" = "dirty" ]; then
  log "WARNING: the working tree differs from $SHORT_COMMIT; the manifest will record that"
fi
log "version $VERSION commit $SHORT_COMMIT build $BUILD_NUMBER ($TREE_STATE)"

# The build number and commit are compiled into the manifest rather than into the JARs, so a JAR is
# reproducible from the same sources regardless of which pipeline built it.
GRADLE_TASKS="clean bootJar jar"
if [ "$SKIP_TESTS" != "1" ]; then
  GRADLE_TASKS="clean check bootJar jar"
fi
log "running ./gradlew $GRADLE_TASKS"
./gradlew --no-daemon $GRADLE_TASKS

rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"/{apps,libs,web,config,scripts}

log "collecting applications"
cp media-api/build/libs/media-api-"$VERSION".jar "$RELEASE_DIR/apps/"
cp media-worker/build/libs/media-worker-"$VERSION".jar "$RELEASE_DIR/apps/"

log "collecting library JARs"
for module in media-contracts media-domain media-application \
              adapter-persistence adapter-messaging adapter-transcode adapter-storage; do
  jar_path="$module/build/libs/${module}-${VERSION}.jar"
  if [ -f "$jar_path" ]; then
    cp "$jar_path" "$RELEASE_DIR/libs/"
  else
    echo "missing expected library JAR: $jar_path" >&2
    exit 1
  fi
done

if [ "$SKIP_WEB" != "1" ] && [ -d web/dist ]; then
  log "collecting the web build"
  cp -r web/dist/. "$RELEASE_DIR/web/"
elif [ -d "${WEB_DIST_DIR:-}" ]; then
  log "collecting the web build from $WEB_DIST_DIR"
  cp -r "$WEB_DIST_DIR"/. "$RELEASE_DIR/web/"
else
  log "no web build present; the release will not contain a front end"
fi

log "collecting configuration templates and scripts"
cp deploy/jvm/*.opts "$RELEASE_DIR/config/"
cp -r deploy/nginx "$RELEASE_DIR/config/" 2>/dev/null || true
cp scripts/service.sh scripts/smoke-test.sh scripts/check_change_scope.py \
   scripts/check_change_scope.sh "$RELEASE_DIR/scripts/"
chmod +x "$RELEASE_DIR/scripts"/*.sh

cat > "$RELEASE_DIR/config/application-template.json" <<TEMPLATE
{
  "comment": "Environment values are supplied by the runtime environment file, never by this template.",
  "required": ["DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD", "KAFKA_BOOTSTRAP", "STORAGE_ROOT"],
  "optional": {
    "HTTP_PORT": "8080",
    "WORKER_HEALTH_PORT": "8090",
    "CHUNK_SIZE_BYTES": "8388608",
    "WORKSPACE_QUOTA_BYTES": "10737418240"
  }
}
TEMPLATE

log "writing the manifest"
jdk_version="$(java -version 2>&1 | head -1 | sed 's/^[^"]*"//; s/".*//')"
ffmpeg_version="$(ffmpeg -version 2>/dev/null | head -1 | awk '{print $3}' || echo 'absent')"
node_version="$(node --version 2>/dev/null || echo 'absent')"
# The schema version is the highest applied migration, read from the migration files themselves.
schema_version="$(ls adapter-persistence/src/main/resources/db/migration \
  | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)"

{
  printf '{\n'
  printf '  "version": "%s",\n' "$VERSION"
  printf '  "commit": "%s",\n' "$COMMIT"
  printf '  "buildNumber": "%s",\n' "$BUILD_NUMBER"
  printf '  "buildTime": "%s",\n' "$BUILD_TIME"
  printf '  "jdk": "%s",\n' "$jdk_version"
  printf '  "node": "%s",\n' "$node_version"
  printf '  "ffmpeg": "%s",\n' "$ffmpeg_version"
  printf '  "schemaVersion": "%s",\n' "${schema_version:-unknown}"
  printf '  "eventSchemaVersion": 1,\n'
  # A build from a modified tree says so, and lists what was modified. Naming only the commit would
  # claim the artifacts came from that commit when they did not.
  printf '  "treeState": "%s",\n' "$TREE_STATE"
  printf '  "dirtyPaths": ['
  if [ "$TREE_STATE" = "dirty" ]; then
    first_path=1
    while IFS= read -r changed; do
      [ -n "$changed" ] || continue
      [ "$first_path" -eq 1 ] || printf ', '
      printf '"%s"' "$changed"
      first_path=0
    done < <( { git diff --name-only HEAD -- 2>/dev/null; git ls-files --others --exclude-standard; } | sort -u )
  fi
  printf '],\n'
  printf '  "artifacts": {\n'
  first=1
  while IFS= read -r file; do
    relative="${file#"$RELEASE_DIR"/}"
    digest="$(sha256sum "$file" | cut -d' ' -f1)"
    [ "$first" -eq 1 ] || printf ',\n'
    printf '    "%s": "%s"' "$relative" "$digest"
    first=0
  done < <(find "$RELEASE_DIR" -type f ! -name manifest.json ! -name SHA256SUMS | sort)
  printf '\n  }\n'
  printf '}\n'
} > "$RELEASE_DIR/manifest.json"

log "writing checksums"
( cd "$RELEASE_DIR" && find . -type f ! -name SHA256SUMS -printf '%P\0' \
    | sort -z | xargs -0 sha256sum > SHA256SUMS )

log "assembling the distribution archive"
( cd "$OUT_DIR" && zip -q -r "media-workspace-${RELEASE_NAME}.zip" "${RELEASE_NAME}" )

log "release ready: $RELEASE_DIR"
log "archive: $OUT_DIR/media-workspace-${RELEASE_NAME}.zip"
