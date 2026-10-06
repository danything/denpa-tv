#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 縮めた APK (:app の minified) と SmokeTest の APK (:smoke) を、繋いだ端末 (エミュレータ) に入れて走らせる。
# CI の emulator の列はこれだけを走らせる (Gradle も JDK も要らない。APK は apks の列が1度だけ焼き、ここで待って取ってくる)。
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

# CI (`SMOKE_STOP_EMULATOR=1`) では、終わったらエミュレータもここで止める。API 28 の像では、エミュレータが閉じても
# その子の crashpad_handler が出力を握ったまま残り、android-emulator-runner がその終わりを待ったまま手順の上限まで固まった
# (ほかの API では一緒に閉じる)。頼んで 30 秒待ち、閉じなければ qemu を殺し、残った crashpad_handler も殺す
# (スナップショットは保存しない手順なので、殺しても困らない)
stop_emulator() {
    [ "${SMOKE_STOP_EMULATOR:-}" = 1 ] || return 0
    timeout 10 adb emu kill || true
    for _ in $(seq 1 15); do
        pgrep -f qemu-system > /dev/null || break
        sleep 2
    done
    if pgrep -f qemu-system > /dev/null; then
        echo "エミュレータが閉じないので止めます"
        pkill -9 -f qemu-system || true
    fi
    pkill -9 -f emulator/crashpad_handler || true
}
logcat=""
trap '[ -n "$logcat" ] && kill "$logcat" 2>/dev/null; stop_emulator' EXIT
# CI (`SMOKE_WAIT_APKS=<置く先>`) では、エミュレータを起こしてから apks の列が焼き終わるのを待って APK を取ってくる
# (scripts/wait-apks.sh)。取れなくてもエミュレータは上で止める
if [ -n "${SMOKE_WAIT_APKS:-}" ]; then
    bash "$(dirname "$0")/wait-apks.sh" "$SMOKE_WAIT_APKS"
fi
adb install -r -t "$app"
adb install -r -t "$smoke"
adb logcat -c || true
# logcat は走らせている間ずっと書き出す (途中で止められても、そこまでの記録が残る)
adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
logcat=$!
# am instrument は失敗しても 0 で戻るので、JUnit の締めの行 (OK (3 tests) / FAILURES!!!) で見る。
# テストの APK ごと起き上がれないと 0 件のまま終わるので、1件以上通ったことも見る。-r でテストごとの始まり・終わりも出す。
#
# **adb shell が戻るのを待たない。** API 28 のエミュレータでは、テストが終わって am が閉じても (logcat に
# 「run finished」も出る) adb shell が戻らず、そのまま adb ごと応えなくなることがあった (CI で 30 分待って切られた)。
# 終わりの印 (INSTRUMENTATION_CODE) が出たら adb を待たずに読み終える。出ないまま `SMOKE_TIMEOUT` 秒たったら切る
adb shell am instrument -w -r io.github.danything.denpatv.smoke/androidx.test.runner.AndroidJUnitRunner > "$out/instrument.txt" 2>&1 &
instrument=$!
deadline=$((SECONDS + ${SMOKE_TIMEOUT:-480}))
while kill -0 "$instrument" 2>/dev/null && ! grep -q '^INSTRUMENTATION_CODE:' "$out/instrument.txt" && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 2
done
finished=$(grep -c '^INSTRUMENTATION_CODE:' "$out/instrument.txt" || true)
kill "$instrument" 2>/dev/null || true
cat "$out/instrument.txt"
if grep -q '^OK ([1-9]' "$out/instrument.txt"; then
    exit 0
fi
[ "$finished" -eq 0 ] && echo "am instrument が ${SMOKE_TIMEOUT:-480} 秒で終わりませんでした" | tee -a "$out/instrument.txt"
# 止まったところの画面と、画面の部品の木 (どこで待っているかの手掛かり)。adb が応えないこともあるので、どれも待ちすぎない
timeout 30 adb exec-out screencap -p > "$out/screen.png" || true
timeout 30 adb exec-out uiautomator dump /dev/tty > "$out/window.xml" 2>/dev/null || true
timeout 30 adb shell dumpsys activity activities > "$out/activities.txt" 2>&1 || true
exit 1
