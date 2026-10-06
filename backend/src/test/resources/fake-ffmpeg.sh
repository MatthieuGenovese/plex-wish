#!/bin/sh
# Faux ffmpeg pour le test à blanc du remux : succès, échec, avertissement ou lenteur d'après le nom du fichier.
dir=$(dirname "$0")
printf '%s\n' "$*" >> "$dir/fake-ffmpeg.log"
if [ "$1" = "-version" ]; then echo "ffmpeg version fake-1.0 Copyright (c) test"; exit 0; fi
input=""; unpack=0; prev=""
for a in "$@"; do
  [ "$prev" = "-i" ] && input="${a#file:}"
  [ "$a" = "mpeg4_unpack_bframes" ] && unpack=1
  prev="$a"
done
case "$input" in
  *remuxfail*) echo "[matroska @ 0x1] Timestamps are unset in a packet for stream 0. ($input)" >&2; exit 234 ;;
  *unpackfail*) if [ $unpack = 1 ]; then echo "Error applying bitstream filters to an output packet" >&2; exit 1; fi ;;
  *slowremux*) sleep 5 ;;
  *warn*) echo "[ogg @ 0x1] Headers mismatch for stream 0: expected 2 received 1." >&2 ;;
esac
exit 0
