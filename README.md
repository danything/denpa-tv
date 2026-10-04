# denpa TV

[denpa](https://github.com/danything/denpa) を Android TV / Google TV で観るアプリです。
Jetpack Compose for TV で書いています。

<p align="center">
  <img src="docs/images/navigation.webp" alt="ホームからライブを開き、番組名を出す" width="720">
</p>

| ホーム | ライブ |
| --- | --- |
| <img src="docs/images/home.webp" alt="ホーム。局のカードにいま放送中の番組と進み具合、録画のカード" width="420"> | <img src="docs/images/live.webp" alt="ライブ。下に局と番組名と残り時間" width="420"> |
| **録画を消す** (長押し) | **設定** |
| <img src="docs/images/delete.webp" alt="録画を消すか確かめる画面。最初はキャンセルに合っている" width="420"> | <img src="docs/images/settings.webp" alt="設定。ライブの画質・CM を飛ばす・繋ぐ先" width="420"> |

映像とポスターはぼかしてあります (放送の絵のため)。絵は Android TV のエミュレータ (API 36、1080p) で撮りました。

- **ライブ** — 局の一覧に、いま放送中の番組と進み具合が出ます。選ぶとそのまま観られます。
  上下キー (リモコンのチャンネル送り) で隣の局へ、決定で番組名と残り時間
- **録画** — 新しい順に並び、選ぶと**続きから**再生します。左で 10 秒戻し、右で 30 秒送り、
  決定で止める・動かす、上下 (次へ・前へ) でチャプター送り。**CM は自動で飛ばします** (設定で切れる)。
  観た位置は denpa に預けるので、ブラウザとも続きを分け合えます。録画のカードを**長押しすると消せます**
  (確かめる画面が出て、最初はキャンセルに合っています)

続きの位置といま放送中の番組は、それを返す denpa (danything/denpa#390 を含む版から) で出ます。古い denpa でも、
出ないだけでほかは動きます。

## 入れ方

Play ストアにはまだ出していないので、パソコンから adb で入れます。手順は [docs/install.md](docs/install.md)
(APK の手に入れ方、テレビの開発者向けオプション、ワイヤレス デバッグ、困ったとき)。

## denpa に繋ぐ

初めて開くと、繋ぐ画面に QR コードが出ます。

1. スマホをテレビと同じ Wi-Fi に繋ぎ、QR を読む (テレビの中の小さなページが開く)
2. ブラウザで denpa を開いている URL を入れて送る (例: `http://192.168.1.10:3000/`、`https://denpa.example.jp/`)
3. **家の LAN の denpa** (テレビが denpa の `TRUSTED_NETWORKS` に入っている) なら、それで終わり。
   スマホに「設定しました」と出て、テレビはホームに進みます
4. **家の外の denpa** (OIDC でログインする構成) なら、スマホが denpa の画面に移ります。ログインすると
   denpa がこのテレビを登録し、テレビは自動でホームに進みます。テレビとスマホに同じコード (`ABCD-EFGH`) が
   出るので、見比べてください

リモコンで URL を打つこともできます (家の外の denpa なら、テレビに出る QR / URL を別の端末で開いてログイン)。
繋ぐ先を変える・登録を外すのは、ホームのいちばん下の「設定」から。

家の外の denpa への登録には、denpa 側にテレビを登録する口 (`api/device/*`) が要ります
([danything/denpa#393](https://github.com/danything/denpa/pull/393) を含む版から)。

### 安全のために

- **テレビの中のページは、繋ぐ画面を開いている間だけ**待ち受けます。URL には推測できない文字列が入っていて、
  QR を見ていない同じ LAN の誰かが当てずっぽうで開くことはできません。ページは http (LAN の中だけ) で、
  受け取るのは denpa の URL だけです (パスワードは扱いません。ログインは denpa の画面でします)
- 登録で受け取るトークンは、アプリの領域 (他のアプリから読めない) に置きます。テレビを手放すときは
  「設定」→「サーバーから外す」でトークンを無効にしてください (denpa の画面からも外せます)
- トークンが効かなくなる (外された・期限切れ) と、テレビは繋ぐ画面に戻ります

## 再生できる形

| 何 | 形 | 選び方 |
| --- | --- | --- |
| ライブ | 低遅延 = 生の TS (MPEG-2)、または fragmented MP4 (H.264 / AV1) | 設定で選ぶ。既定は端末がハードで MPEG-2 を解ければ低遅延、そうでなければ H.264。AV1 はハードで解ける端末だけ選べる |
| 録画 | Matroska (AV1 / H.264)、生の TS (MPEG-2) | 解ける中で軽いものから: AV1 → H.264 → 生の TS |

焼いた録画の字幕 (PGS) は出ます。

### 低遅延 (MPEG-2)

denpa に焼かせず、放送そのもの (1局に絞った TS) を流します (`?codec=raw`)。焼くのを待たないぶん
いちばん早く映り、denpa の CPU も使いません。アプリ側も溜める量を小さくし (0.5〜2 秒、0.25 秒
溜まったら映す)、放送から遅れたら少し速く回して追いつきます。

- **ARIB の字幕は出ません** (焼いたライブ・録画の字幕とは別物で、アプリは読めません)
- **MPEG-2 をハードで解ける端末だけ**選べます。アプリが起動時に端末のデコーダを調べて決めます。
  - Fire TV は公式の仕様に MPEG-2 のハードデコードが載っています
    ([Amazon のデバイス仕様](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html))
  - Chromecast with Google TV と Google TV Streamer の公式の仕様には MPEG-2 が載っていません
    ([Google の仕様](https://support.google.com/chromecast/answer/3046409)) — 実機で調べて、無ければ選べません
  - 地デジのチューナーを内蔵したテレビは放送 (MPEG-2) を自分で解くので、持っていることが多いはずですが、
    アプリに使わせるかは機種しだいです。ここも実機で調べます
- **音声は放送の AAC のまま**です。二か国語 (デュアルモノ) の番組は、主と副が左右に分かれて
  同時に聞こえることがあります (アプリでは切り替えられません)。番組の途中で 5.1ch とステレオが
  入れ替わると、一瞬音が途切れることがあります

## できないこと

- **データ放送** と **ライブの字幕** (ARIB) は出ません。ブラウザの denpa を使ってください
- 番組表・予約・ルールはまだありません (観るだけ)
- CM 飛ばしは、denpa が CM の区切りをチャプターとして書いて焼いた録画だけです
  (チャプターは動画そのものから読みます。生の TS にはチャプターがありません)

## 開発

- JDK 17 以上 (CI は 25)、Android SDK (compileSdk 37)
- `./gradlew assembleDebug` / `./gradlew testDebugUnitTest` / `./gradlew lintDebug`
- 使っているライブラリと選んだ理由は [docs/libraries.md](docs/libraries.md)
- リリースの出し方 (署名の鍵) は [docs/release.md](docs/release.md)

## ライセンス

[GNU Affero General Public License v3.0](LICENSE) (denpa と同じ)。

`app/src/main/java/io/nayuki/qrcodegen/` は [Project Nayuki の QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) を
取り込んだもので、**MIT License** です (各ファイルの頭の表示のまま)。
