#!/usr/bin/env bash
#
# Generates the fixtures the media tests use, from FFmpeg's own synthetic sources.
#
# Nothing here is third-party content: every sample is produced by a filter at run time, so no
# copyright-encumbered file is stored in the repository or shipped in a build artifact. Re-running
# the script reproduces the same inputs, which is what makes a failure reproducible.
#
# Usage: scripts/generate-test-media.sh [output-directory]
set -euo pipefail

OUT="${1:-/opt/media-workspace/var/test-media}"
FFMPEG="${FFMPEG:-ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"

mkdir -p "$OUT"

log() { printf '[fixtures] %s\n' "$*"; }

# 10 second 640x360 clip with a moving pattern and a tone, so the picture is not a single flat
# frame and the encoder has something to do.
log "tiny.mp4 (10s, 640x360, video+audio)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=640x360:rate=25:duration=10" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=10" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest \
  -movflags +faststart "$OUT/tiny.mp4"

# No audio track at all: the encoder must not fail for a missing audio stream.
log "silent.mp4 (no audio track)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=320x240:rate=25:duration=4" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  -movflags +faststart "$OUT/silent.mp4"

# Vertical video: exercises the scale filter on the other aspect ratio.
log "portrait.mp4 (portrait orientation)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=360x640:rate=25:duration=4" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  -movflags +faststart "$OUT/portrait.mp4"

# 1280x720 input: the output must stay at 720p, not be enlarged.
log "hd720.mp4 (already 720p)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=1280x720:rate=25:duration=4" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  -movflags +faststart "$OUT/hd720.mp4"

# Larger than 720p: the scale filter has to shrink it and keep both dimensions even.
log "over720.mp4 (1920x1080, must shrink to 720p)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=1920x1080:rate=25:duration=4" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  -movflags +faststart "$OUT/over720.mp4"

# A truncated file: valid header, missing tail. Used for the corrupt-input case.
log "corrupt.mp4 (truncated)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=320x240:rate=25:duration=6" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  -movflags +faststart "$OUT/.corrupt-full.mp4"
head -c 20000 "$OUT/.corrupt-full.mp4" > "$OUT/corrupt.mp4"
rm -f "$OUT/.corrupt-full.mp4"

# Plain text with an mp4 extension: the container must be decided by content, not by name.
log "fake.mp4 (text with an mp4 extension)"
printf 'this is not a video file, it only claims to be\n' > "$OUT/fake.mp4"

# Unicode file name, used to prove the display name is never turned into a path.
log "unicode-name fixture"
cp "$OUT/tiny.mp4" "$OUT/团队活动-录像_2026.mp4"

# A real JPEG poster. The fake encoder copies this when it is asked for a poster, so a test can
# reach the artifact-validation stage instead of tripping over a poster that is not an image.
log "poster.jpg (a real JPEG, for the deterministic failure tests)"
"$FFMPEG" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=size=640x360:rate=1:duration=1" \
  -frames:v 1 -q:v 3 "$OUT/poster.jpg"

log "done: $OUT"
ls -la "$OUT" | tail -n +2

log "probing each video to confirm what was produced"
for f in tiny.mp4 silent.mp4 portrait.mp4 hd720.mp4 over720.mp4; do
  "$FFPROBE" -hide_banner -v error -select_streams v:0 \
    -show_entries stream=width,height,codec_name -of csv=p=0 "$OUT/$f" \
    | sed "s|^|  $f: |"
done
