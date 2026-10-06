#!/bin/sh
# Faux ffprobe pour les tests (ARCHITECTURE §22) : réponse choisie d'après le nom du fichier.
dir=$(dirname "$0")
echo "$*" >> "$dir/fake-ffprobe.log"
if [ "$1" = "-version" ]; then echo "ffprobe version fake-1.0 Copyright (c) test"; exit 0; fi
for a in "$@"; do last="$a"; done
file="${last#file:}"
case "$file" in
  *broken*) echo "$file: Invalid data found when processing input" >&2; exit 1 ;;
  *slowprobe*) sleep 5 ;;
  *garbage*) echo "pas du json"; exit 0 ;;
  *hevc10*) cat "$dir/ffprobe-fixtures/mkv-hevc10-aac-ass.json"; exit 0 ;;
  *.avi) cat "$dir/ffprobe-fixtures/avi-xvid-mp3.json"; exit 0 ;;
  *.ogm) cat "$dir/ffprobe-fixtures/ogm-mpeg4-vorbis.json"; exit 0 ;;
  *.mp4) cat "$dir/ffprobe-fixtures/mp4-real.json"; exit 0 ;;
  *.mkv) cat "$dir/ffprobe-fixtures/mkv-h264-aac-srt-pgs.json"; exit 0 ;;
esac
echo "$file: format inconnu" >&2; exit 1
