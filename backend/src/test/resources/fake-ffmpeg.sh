#!/bin/sh
# Faux ffmpeg (test à blanc et remux) : succès, échec, avertissement ou lenteur d'après le nom du fichier source.
# Vers un vrai fichier (remux), il écrit 1000 octets commençant par « FIXTURE=<réponse du faux ffprobe> ».
dir=$(dirname "$0")
printf '%s\n' "$*" >> "$dir/fake-ffmpeg.log"
if [ "$1" = "-version" ]; then echo "ffmpeg version fake-1.0 Copyright (c) test"; exit 0; fi
input=""; unpack=0; prev=""; out=""
for a in "$@"; do
  [ "$prev" = "-i" ] && input="${a#file:}"
  [ "$a" = "mpeg4_unpack_bframes" ] && unpack=1
  prev="$a"; out="$a"
done
case "$input" in
  *remuxfail*) echo "[matroska @ 0x1] Timestamps are unset in a packet for stream 0. ($input)" >&2; exit 234 ;;
  *genptsfail*) if [ $unpack = 0 ]; then echo "[matroska @ 0x1] Timestamps are unset in a packet for stream 0." >&2; exit 234; fi ;;
  *unpackfail*) if [ $unpack = 1 ]; then echo "Error applying bitstream filters to an output packet" >&2; exit 1; fi ;;
  *slowremux*) sleep 5 ;;
  *slowish*) sleep 1 ;;
  *warn*) echo "[ogg @ 0x1] Headers mismatch for stream 0: expected 2 received 1." >&2 ;;
esac
case "$out" in
  file:*)
    target="${out#file:}"
    case "$input" in
      *badcopy*) fixture=copy-short ;;
      *noaudio*) fixture=copy-noaudio ;;
      *.ogm) fixture=ogm-mpeg4-vorbis ;;
      *) fixture=avi-xvid-mp3 ;;
    esac
    echo "out_time_us=N/A"
    echo "out_time_us=700000000"
    { printf 'FIXTURE=%s\n' "$fixture"; head -c 2000 /dev/zero; } | head -c 1000 > "$target"
    echo "progress=end"
    ;;
esac
exit 0
