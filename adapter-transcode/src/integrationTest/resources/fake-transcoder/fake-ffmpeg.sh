#!/usr/bin/env bash
#
# A stand-in for FFmpeg whose failure modes are chosen by the environment.
#
# Deterministic failure tests cannot be built on a real encoder: making FFmpeg hang, flood stderr or
# die half way through would depend on the input and on timing. This script produces each of those
# behaviours on demand, so a test asserts on the worker's reaction rather than on the encoder.
#
# Configured by FAKE_MODE:
#   ok            write a plausible output file and exit 0
#   hang          never exit, and keep the pipes open
#   flood         write far more stderr than the retention budget, then exit 0
#   progress      emit a realistic -progress stream on stdout, then exit 0
#   exit-nonzero  exit 3 with a diagnostic on stderr
#   half          write a partial output file and exit 1
#   zero-no-file  exit 0 without creating any output
#   disk-full     exit 1 with the message a full volume produces
#   spawn-child   start a grandchild that outlives this process unless the tree is terminated
#
# The last argument is treated as the output path, which is how FFmpeg is invoked.
set -u

MODE="${FAKE_MODE:-ok}"
OUTPUT="${@: -1}"

# When set, the "produced" file is a copy of a real artifact. The adapter re-probes whatever the
# encoder wrote, so a mode that is meant to reach the validation stage must write something ffprobe
# accepts; otherwise the test would only ever exercise the rejection path.
#
# The poster invocation is recognised by its -f image2 argument and gets a real JPEG, so the poster
# check is exercised rather than bypassed.
emit_output() {
  if printf '%s' "$*" | grep -q 'image2'; then
    if [ -n "${FAKE_COPY_POSTER:-}" ]; then
      cp "$FAKE_COPY_POSTER" "$OUTPUT"
      return
    fi
  elif [ -n "${FAKE_COPY_FROM:-}" ]; then
    cp "$FAKE_COPY_FROM" "$OUTPUT"
    return
  fi
  printf 'fake mp4 payload\n' > "$OUTPUT"
}

case "$MODE" in
  ok)
    emit_output "$@"
    exit 0
    ;;
  hang)
    # Keep writing occasionally so the pipes stay open and a reader cannot mistake silence for exit.
    while true; do
      echo "frame=1 time=00:00:01.00" >&1
      sleep 1
    done
    ;;
  flood)
    # About 4 MiB of stderr, well over the 64 KiB the worker is allowed to retain.
    for i in $(seq 1 40000); do
      echo "fake-ffmpeg: warning number $i with a reasonably long descriptive line" >&2
    done
    emit_output "$@"
    exit 0
    ;;
  progress)
    for i in $(seq 0 100); do
      echo "out_time_us=$((i * 100000))"
      echo "out_time_ms=$((i * 100000))"
      echo "frame=$((i * 25))"
      echo "progress=continue"
    done
    echo "progress=end"
    emit_output "$@"
    exit 0
    ;;
  exit-nonzero)
    echo "fake-ffmpeg: the encoder refused this input" >&2
    exit 3
    ;;
  half)
    printf 'partial' > "$OUTPUT"
    echo "fake-ffmpeg: died while writing the output" >&2
    exit 1
    ;;
  zero-no-file)
    echo "fake-ffmpeg: pretending to succeed" >&2
    exit 0
    ;;
  disk-full)
    echo "av_interleaved_write_frame(): No space left on device" >&2
    printf 'partial' > "$OUTPUT" 2>/dev/null || true
    exit 1
    ;;
  spawn-child)
    # A grandchild that ignores its parent: only terminating the whole tree stops it.
    ( while true; do sleep 1; done ) &
    echo "fake-ffmpeg: spawned a descendant at pid $!"
    while true; do sleep 1; done
    ;;
  *)
    echo "fake-ffmpeg: unknown mode $MODE" >&2
    exit 64
    ;;
esac
