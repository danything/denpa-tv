#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 端末のテスト (smoke/ の SmokeTest) が偽の denpa から流す映像を作り直す。作ったものはリポジトリに置いてある
# (smoke/src/main/assets/。合わせて約 670 KB) ので、ふだんは走らせなくてよい。要るのは ffmpeg (libx264 入り) だけ。
#
#   live.mp4       ライブ (fragmented MP4、H.264 / AAC)。地は緑
#   recording.mkv  焼いた録画 (Matroska、H.264 / AAC)。地は赤紫
#   showcase-live-{1,2,3}.mp4  画面の絵 (Screenshots) のライブ・追っかけ。ぼかした色の帯がゆっくり動く (120 秒。局ごとに色を替える)
#   showcase.mkv   画面の絵の録画 (30 秒。最後まで観たときの絵も撮るので短く)
#
# 焼く前の録画・ライブの生の TS (MPEG-2) は無い。Android TV のエミュレータは MPEG-2 のデコーダを有効にしていない
# (c2.android.mpeg2.decoder は domain="tv" で、エミュレータでは使えない) ので、映せるかを確かめようがない。
#
# live・recording は 320x180・15 fps・10 秒で、白い四角が横へ動く。SmokeTest は画面を撮って地の色の点を数え、映像が出たかを見る
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

# 画面の絵のもの (音は無音)。320x180・10 fps。ぼかしてあるので引き伸ばしても粗さが目立たない
showcase() { echo "gradients=s=320x180:r=10:d=$1:n=3:c0=$2:c1=$3:c2=$4:speed=0.004:type=$5,gblur=sigma=12,format=yuv420p"; }
quiet() { ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$1" -f lavfi -i "anullsrc=r=48000:cl=stereo" -t "$2" "${@:3}"; }
small=(-c:v libx264 -profile:v baseline -g 30 -crf 36 -c:a aac -b:a 16k)
fmp4=(-movflags frag_keyframe+empty_moov+default_base_moof)
quiet "$(showcase 120 0x4A2C6E 0xD07AA8 0x2B3F66 linear)" 120 "${small[@]}" "${fmp4[@]}" "$out/showcase-live-1.mp4"
quiet "$(showcase 120 0x2E4A7A 0xE0A070 0x1F2B3C linear)" 120 "${small[@]}" "${fmp4[@]}" "$out/showcase-live-2.mp4"
quiet "$(showcase 120 0x1F5A55 0x9AD0B0 0x283050 linear)" 120 "${small[@]}" "${fmp4[@]}" "$out/showcase-live-3.mp4"
quiet "$(showcase 30 0x6A4A3A 0xE8C890 0x34507A radial)" 30 "${small[@]}" "$out/showcase.mkv"
ls -l "$out"
