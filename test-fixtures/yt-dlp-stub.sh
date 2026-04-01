#!/bin/sh
# yt-dlp fixture script for E2E tests.
# Replaces the real yt-dlp binary in the Docker test environment.
# Serves fixture data for YouTube URL resolution and audio download.

FIXTURES_DIR="/usr/share/nginx/html"

# Parse arguments to determine mode
case "$*" in
  *--dump-json*)
    # Metadata mode: output the fixture JSON to stdout
    cat "$FIXTURES_DIR/youtube-metadata.json"
    ;;
  *-x*--audio-format*)
    # Audio download mode: copy the test WAV to the output path
    # Extract -o <path> from arguments
    OUTPUT=""
    PREV=""
    for arg in "$@"; do
      if [ "$PREV" = "-o" ]; then
        OUTPUT="$arg"
        break
      fi
      PREV="$arg"
    done

    if [ -z "$OUTPUT" ]; then
      # Fallback: try to find a .wav path in args
      OUTPUT=$(echo "$@" | grep -oE '[^ ]+\.wav')
    fi

    if [ -n "$OUTPUT" ]; then
      cp "$FIXTURES_DIR/test-episode.wav" "$OUTPUT"
    else
      echo "yt-dlp-stub: could not determine output path from args: $*" >&2
      exit 1
    fi
    ;;
  *)
    echo "yt-dlp-stub: unrecognised arguments: $*" >&2
    exit 1
    ;;
esac
