#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# スクリーンショットを撮る (smoke/ の Screenshots)。CI の Screenshots の流れ (.github/workflows/screenshots.yml) が、
# API 36・1080p の Android TV エミュレータで走らせる。手元でも、繋いだ端末で同じに撮れる。
#
#   scripts/shots-run.sh <app-minified.apk> <smoke-minified.apk> [画像を置くディレクトリ]
#
# 画像は PNG (1920×1080) で置く。webp にするのは呼ぶ側 (README の画像は 1280 幅の webp)
# ---------------------------------------------------------------------------
set -euo pipefail

app=$1
smoke=$2
out=${3:-shots}
mkdir -p "$out"

adb install -r -t "$app"
adb install -r -t "$smoke"
# 終わりの印が出ても adb shell が戻らないことがある (smoke-run.sh) ので、上限を付ける
timeout 900 adb shell am instrument -w -r -e shots 1 -e class io.github.danything.denpatv.smoke.Screenshots \
    io.github.danything.denpatv.smoke/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrument.txt" || true
adb pull /data/local/tmp/shots/. "$out/" || true
# 終わりに一覧を上下に送ったあいだのフレーム (Screenshots の最後) と、そのあとのメモリ使用量
timeout 60 adb shell dumpsys gfxinfo io.github.danything.denpatv > "$out/gfxinfo.txt" || true
timeout 60 adb shell dumpsys meminfo io.github.danything.denpatv > "$out/meminfo.txt" || true
ls -l "$out"
grep -q '^OK (1 test)' "$out/instrument.txt" || { timeout 30 adb exec-out screencap -p > "$out/failed.png" || true; exit 1; }
