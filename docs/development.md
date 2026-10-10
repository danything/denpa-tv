# 開発

- JDK 17 以上 (CI は 25)、Android SDK (compileSdk 37)
- `./gradlew assembleDebug` / `./gradlew testDebugUnitTest` / `./gradlew lintDebug`
- 縮めた APK をエミュレータ (か繋いだテレビ) で動かす: `./gradlew :smoke:connectedMinifiedAndroidTest`
  (`smoke/`。偽の denpa を立ててライブと録画を映し、落ちないか・映像が出るかを見る。CI は APK を1度だけ焼き、
  API 24・28・31・34・36 の Android TV のエミュレータに `scripts/smoke-run.sh` で入れて走らせる)。
  端末が何台も繋がっているときは `ANDROID_SERIAL` で1台に絞る
- 使っているライブラリと選んだ理由は [libraries.md](libraries.md)、リリースの出し方 (署名の鍵) は [release.md](release.md)
- 要る denpa の版を上げるときは、[README](../README.md#denpa-に繋ぐ)・[pairing.md](pairing.md#要る-denpa-の版) と `MIN_DENPA` (`data/DenpaVersion.kt`) をいっしょに直す。
  古い denpa に合わせた作りは持たない

## 画面の絵

`docs/images/` の絵は CI の Screenshots の流れ (`.github/workflows/screenshots.yml`) で撮ります。smoke/ の `Screenshots` が
Android TV のエミュレータ (API 36、1080p) で、作り物の番組・録画・説明を返す偽の denpa に繋いで撮り、1280 幅の webp にします。
番組名・人名は作り物で、ポスターと映像はぼかした場面に見えるように描いたもの、局ロゴは番号を書いただけの札、ライブは色の映像です
(観た割合の帯や追っかけの「録画中」も、偽の denpa がそう返しているだけ)。
