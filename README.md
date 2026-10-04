# denpa TV

[denpa](https://github.com/danything/denpa) を Android TV / Google TV で観るアプリです。
Jetpack Compose for TV で書いています。

- **ライブ** — 局の一覧から選ぶと、そのまま観られます。上下キー (リモコンのチャンネル送り) で隣の局へ
- **録画** — 新しい順に並び、選ぶと再生します。左で 10 秒戻し、右で 30 秒送り、決定で止める・動かす。
  観た位置は denpa に預けるので、ブラウザで続きから観られます

## 入れ方

リリース前なので、CI が焼いた APK を手で入れます (サイドロード)。

1. [Actions](https://github.com/danything/denpa-tv/actions) の最新の成功した実行から、
   `denpa-tv-debug` を落として展開する (`app-debug.apk`)
2. テレビの「開発者向けオプション」で USB デバッグ (またはネットワーク デバッグ) を有効にする
3. `adb connect <テレビの IP>` → `adb install app-debug.apk`

## denpa に繋ぐ

初めて開くと URL を聞かれます。ブラウザで denpa を開いている URL を入れてください
(例: `http://192.168.1.10:3000`。前段の接頭辞の下で動かしているなら、その接頭辞まで)。
繋がるかを確かめてから覚えます。あとから変えるときは、ホームのいちばん下の「繋ぐ先を変える」から。

**ログインはありません。** テレビが denpa の `TRUSTED_NETWORKS` に入っている (家の LAN から繋ぐ)
前提です。入っていないと一覧が取れません。

## 再生できる形

| 何 | 形 | 選び方 |
| --- | --- | --- |
| ライブ | fragmented MP4 (AV1 または H.264) | 端末がハードで AV1 を解ければ AV1、そうでなければ H.264 |
| 録画 | Matroska (AV1 / H.264)、生の TS (MPEG-2) | 解ける中で軽いものから: AV1 → H.264 → 生の TS |

焼いた録画の字幕 (PGS) は出ます。

## できないこと

- **データ放送** と **ライブの字幕** (ARIB) は出ません。ブラウザの denpa を使ってください
- 番組表・予約・ルールはまだありません (観るだけ)
- CM 飛ばし (チャプター送り) はまだありません
- 録画の続きから**始める**のは、denpa の一覧 API が観た位置を返さないので、まだできません
  (位置を預けるほうはできます)

## 開発

- JDK 17 以上 (CI は 21)、Android SDK (compileSdk 37)
- `./gradlew assembleDebug` / `./gradlew testDebugUnitTest` / `./gradlew lintDebug`
- 使っているライブラリと選んだ理由は [docs/libraries.md](docs/libraries.md)
