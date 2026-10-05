#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 端末のテスト (smoke/ の SmokeTest) が偽の denpa から流す映像を作り直す。作ったものはリポジトリに置いてある
# (smoke/src/main/assets/。合わせて約 100 KB) ので、ふだんは走らせなくてよい。要るのは ffmpeg (libx264 入り) だけ。
#
#   live.mp4       ライブ (fragmented MP4、H.264 / AAC)。地は緑
#   recording.mkv  焼いた録画 (Matroska、H.264 / AAC)。地は赤紫
#
# 焼く前の録画・ライブの生の TS (MPEG-2) は無い。Android TV のエミュレータは MPEG-2 のデコーダを有効にしていない
# (c2.android.mpeg2.decoder は domain="tv" で、エミュレータでは使えない) ので、映せるかを確かめようがない。
#
# どれも 320x180・15 fps・10 秒で、白い四角が横へ動く。SmokeTest は画面を撮って地の色の点を数え、映像が出たかを見る
# (色を変えるなら SmokeTest の *_COLOR も合わせる)
# ---------------------------------------------------------------------------
set -euo pipefail

out="$(cd "$(dirname "$0")/.." && pwd)/smoke/src/main/assets"
mkdir -p "$out"

video() { echo "color=c=$1:s=320x180:r=15:d=10,drawbox=x='mod(t*60,280)':y=70:w=40:h=40:color=white:t=fill"; }
ff() { ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$1" -f lavfi -i "sine=f=440:d=10" "${@:2}"; }
h264=(-c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 15 -crf 30)
aac=(-c:a aac -b:a 32k -ac 2)

ff "$(video 0x20C040)" "${h264[@]}" "${aac[@]}" -movflags frag_keyframe+empty_moov+default_base_moof "$out/live.mp4"
ff "$(video 0xC02080)" "${h264[@]}" "${aac[@]}" "$out/recording.mkv"
ls -l "$out"
