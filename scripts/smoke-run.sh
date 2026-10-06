#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 縮めた APK (:app の minified) と SmokeTest の APK (:smoke) を、繋いだ端末 (エミュレータ) に入れて走らせる。
# CI の emulator の列はこれだけを走らせる (Gradle も JDK も要らない。APK は apks の列が1度だけ焼く)。
#
#   scripts/smoke-run.sh <app-minified.apk> <smoke-minified.apk> [結果を置くディレクトリ]
#
# 手元では ./gradlew :smoke:connectedMinifiedAndroidTest でも同じテストが走る。端末が何台もあれば ANDROID_SERIAL で絞る。
# 通れば 0。落ちたら logcat も結果のディレクトリに残す (スタックは R8 の名前のまま。mapping.txt で retrace する)
# ---------------------------------------------------------------------------
set -euo pipefail

app=$1
smoke=$2
out=${3:-smoke-results}
mkdir -p "$out"

adb install -r -t "$app"
adb install -r -t "$smoke"
adb logcat -c || true
# logcat は走らせている間ずっと書き出す (途中で止められても、そこまでの記録が残る)
adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
logcat=$!
trap 'kill $logcat 2>/dev/null || true' EXIT
# am instrument は失敗しても 0 で戻るので、JUnit の締めの行 (OK (3 tests) / FAILURES!!!) で見る。
# テストの APK ごと起き上がれないと 0 件のまま終わるので、1件以上通ったことも見る。
# **固まっても待ち続けない** (`SMOKE_TIMEOUT` 秒で切る)。-r でテストごとの始まり・終わりも出すので、どこで止まったか分かる
status=0
timeout "${SMOKE_TIMEOUT:-480}" adb shell am instrument -w -r io.github.danything.denpatv.smoke/androidx.test.runner.AndroidJUnitRunner \
    | tee "$out/instrument.txt" || status=$?
if [ "$status" -eq 0 ] && grep -q '^OK ([1-9]' "$out/instrument.txt"; then
    exit 0
fi
[ "$status" -eq 124 ] && echo "am instrument が ${SMOKE_TIMEOUT:-480} 秒で終わりませんでした" | tee -a "$out/instrument.txt"
# 止まったところの画面と、画面の部品の木 (どこで待っているかの手掛かり)
adb exec-out screencap -p > "$out/screen.png" || true
adb exec-out uiautomator dump /dev/tty > "$out/window.xml" 2>/dev/null || true
adb shell dumpsys activity activities > "$out/activities.txt" 2>&1 || true
exit 1
