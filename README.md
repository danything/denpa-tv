# denpa TV

[denpa](https://github.com/danything/denpa) を Android TV / Google TV で観るアプリです (Jetpack Compose for TV)。
録画 (続きから・CM 飛ばし・追っかけ再生)、ライブ (局送り・録画)、字幕と音声の切り替えができます。

<p align="center">
  <img src="docs/images/navigation.webp" alt="録画の一覧から左のメニュー (頭に denpa の電波塔) を開いてライブへ。入ってすぐは局と番組とキーの手引き (左右で局送り・決定か下でメニュー・上で局の列・決定の長押しで番組) が出て、選局の間は回るものが出る。上キーで、いま映している局に合った局の列を開き、右で隣の局に合わせて決定で替える (替える間は前の局の絵のまま回るもの)。最後は右キーで次の BS の局へ送る" width="720">
</p>

| 録画 | 録画の再生 (下キー) |
| --- | --- |
| <img src="docs/images/recordings.webp" alt="録画の一覧。左に印だけの細いメニュー。上の段に、合わせている録画の番組名を大きく、局・放送日時・長さ、エンコード中 42% と形の札、説明の頭の1行。後ろにその録画の絵をぼかして暗く敷く。下に3列の大きなポスターのカード。ポスターの下の縁の暗い帯に番組名を白く太く2行まで (長い番組名は途中を「…」で縮めて話数「#3」「#1」を残す。まだ観ていないものは頭に水色の点)、ポスターの下に局と放送の時刻 (合わせたものは膨らんで水色の縁)。録っている最中のものは「● 録画中」、焼いている最中のものは「エンコード中 42%」の札。下の端で次の段のカードが覗く" width="420"> | <img src="docs/images/player-bar.webp" alt="録画の再生。下の端に小さく、番組名と位置、CM を色分けしたシークバー、操作の札が1行に収まって並ぶ: 一時停止・前へ・次へ・速さ・CM 飛ばし・字幕・音声・削除" width="420"> |
| **ライブのメニュー** (下・決定) | **局の列** (上キー) |
| <img src="docs/images/live-menu.webp" alt="ライブの下の端に、局ロゴと局と番組・番組の進み、操作の札 (画質 H.264・録画)、その下に地上波の局の列が覗き、次の BS の列の頭が見える" width="420"> | <img src="docs/images/live-channels.webp" alt="局の列。地上波の局の札が横に並び、それぞれに番号・局ロゴ・局名・番組名。いま映している局は水色の地に「視聴中」、合わせたフジテレビの札は膨らんで水色の縁で「録画中」の印" width="420"> |

絵は作り物の番組を返す偽の denpa で撮ったものです。ほかの画面と動きは [docs/usage.md](docs/usage.md)。

## 入れ方

Play ストアにはまだ無いので、パソコンから adb で入れます。テレビの開発者向けオプションでデバッグを有効にしてから:

```sh
curl -fsSL https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.sh | bash -s -- <テレビの IP>
```

Windows・テレビ側の準備・ワイヤレス デバッグのペア設定は [docs/install.md](docs/install.md)。
2回目からはアプリの中で上げられます ([docs/updates.md](docs/updates.md))。

## denpa に繋ぐ

**denpa v1.50.0 以上**が要ります。古ければ録画の一覧の頭と設定に知らせが出ます (止めはしませんが、字幕が出ません)。

1. 初めて開くと出る QR を、テレビと同じ Wi-Fi のスマホで読む
2. ブラウザで denpa を開いている URL を入れて送る (例: `http://192.168.1.10:3000/`。ポートを省くと 80、3000 の順に試す)
3. 家の LAN の denpa (テレビが `TRUSTED_NETWORKS` の中) ならそれで終わり。家の外の denpa (OIDC) なら、スマホでログインするとテレビが登録されて進む

繋ぐ先を変える・外すのは左のメニューの「設定」から。リモコンで URL を打つ・安全のことは [docs/pairing.md](docs/pairing.md)。

## リモコン

十字キーと決定・戻るだけで全部に届きます。

| キー | ライブ | 録画・追っかけ再生 |
| --- | --- | --- |
| 左 / 右 | 前 / 次の局 | 10 秒戻す / 送る |
| 下・決定 | メニュー (操作の列と局の列) | 下: シークバーと操作の列。決定: 止める・動かす |
| 上 | 局の列 (いまの局に合う) | 操作の列 |
| 決定の長押し | 番組の詳しく | 番組の詳しく |
| 戻る | 閉じる。何も無ければいちばん上のメニューへ | 閉じる。何も無ければ一覧へ |
| CH+ / CH- | 次 / 前の局 | — |

Menu・情報・メディアキー・緑のボタンと、メニューの中のキーは [docs/controls.md](docs/controls.md)。

## ドキュメント

- [docs/usage.md](docs/usage.md) — 画面ごとの動き (ライブ・録画・追っかけ再生・字幕と音声・リンクで開く・できないこと)
- [docs/controls.md](docs/controls.md) — キーの全部と、そう決めた理由
- [docs/playback.md](docs/playback.md) — 再生できる形・MPEG-2・切れたときの繋ぎ直し
- [docs/pairing.md](docs/pairing.md) — denpa に繋ぐ (詳しく・安全のために)
- [docs/install.md](docs/install.md) / [docs/updates.md](docs/updates.md) — adb で入れる / アプリの中で上げる
- [docs/development.md](docs/development.md) — 焼く・試す・絵を撮る ([libraries.md](docs/libraries.md)・[release.md](docs/release.md))

## 謝辞

- [@kametani-mikihiro](https://github.com/kametani-mikihiro) — Android TV 12 (BRAVIA) で再生すると落ちるのを、ログ付きで2度報告してくれました (#24)

マージした PR を書いてくれた人はここに載せます。手を入れる前に [CONTRIBUTING](.github/CONTRIBUTING.md) を
見てください。不具合・要望は [Issue](https://github.com/danything/denpa-tv/issues/new/choose) へ。

## ライセンス

[GNU Affero General Public License v3.0](LICENSE) (denpa と同じ)。ただし:

- `app/src/main/java/io/nayuki/qrcodegen/` は [Project Nayuki の QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) を取り込んだもので **MIT License** (各ファイルの頭の表示のまま)
- 字幕と放送の字 ([Denpa Font](https://github.com/danything/denpa-font)。焼くときに取ってきて APK に入れる) は **SIL Open Font License 1.1**
