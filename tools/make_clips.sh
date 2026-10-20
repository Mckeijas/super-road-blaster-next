#!/bin/bash
# make_clips.sh <harness> <rom> <chapters.txt> <states_dir> <out_dir>
# Records a ~3.5 s silent preview clip (352x256 H.264) of every scene.
H=$1; ROM=$2; C=$3; S=$4; OUT=$5
one() {
  st=$1; n=$(basename $st .state); tmp=$(mktemp -d)
  $H state $ROM $C $st $tmp 230 2 2 1 1.0 0 >/dev/null 2>&1
  ls $tmp/f*.png | sort | tail -n +21 | head -n 90 > $tmp/list.txt
  i=0; mkdir $tmp/seq; while read f; do cp $f $tmp/seq/$(printf %04d $i).png; i=$((i+1)); done < $tmp/list.txt
  ffmpeg -v error -y -framerate 25 -i $tmp/seq/%04d.png -vf "crop=256:213:0:8,scale=352:256:flags=lanczos,format=yuv420p" \
    -c:v libx264 -preset slow -crf 27 -movflags +faststart -an $OUT/$n.mp4
  rm -rf $tmp
}
export -f one; export H ROM C OUT
ls $S/*.state | xargs -P 4 -I{} bash -c 'one {}'
