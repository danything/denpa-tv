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

`docs/images/` の絵は全部、CI の Screenshots の流れ (`.github/workflows/screenshots.yml`) で撮ります。smoke/ の `Screenshots` が
Android TV のエミュレータ (API 36、1080p) で、作り物の局・番組・録画・説明 (`Showcase`) を返す偽の denpa に繋ぎ、
繋ぐ画面 → 録画の一覧・詳しく → ライブ (局送り・選局の間・メニュー・局の列・詳しく) → 設定 → 録画の再生 (字幕・操作の列・詳しく・最後まで) → 追っかけ
の順に撮って、1280 幅の webp にします。README の頭の動く絵 (`navigation.webp`) は、途中で撮ったこまを繋いだものです。
番組名・人名・説明は作り物で、ポスターと映像はぼかした場面に見えるように描いたもの、局ロゴは番号を書いただけの札です
(観た割合の帯・録画中・予約済み・エンコード中も、偽の denpa がそう返しているだけ)。局名だけは実在の名前です。

撮り直すとき:

1. Actions の Screenshots を手で走らせる (`gh workflow run screenshots.yml --ref <枝>`)
2. 終わったら artifact (`shots`) を取ってきて (`gh run download <run id> -n shots`)、`docs/images/` にあるのと同じ名前の webp を上書きする
3. 絵が変わったら、README・usage.md の alt も合わせる

ライブの字幕は撮れません (生の TS でしか出ないが、エミュレータには MPEG-2 のデコーダが無く、生の TS を選べない)。字幕は録画の再生の絵に出ています。
