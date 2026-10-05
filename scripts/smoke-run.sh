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
# am instrument は失敗しても 0 で戻るので、JUnit の締めの行 (OK (3 tests) / FAILURES!!!) で見る。
# テストの APK ごと起き上がれないと 0 件のまま終わるので、1件以上通ったことも見る
adb shell am instrument -w io.github.danything.denpatv.smoke/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrument.txt"
if grep -q '^OK ([1-9]' "$out/instrument.txt"; then
    exit 0
fi
adb logcat -d > "$out/logcat.txt" || true
exit 1
