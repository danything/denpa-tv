# テレビに入れる (adb で手で入れる)

Play ストアにはまだ出していないので、パソコンから **adb** で入れます (サイドロード)。
一度入れれば、あとは新しい APK を同じやり方で上から入れるだけです。

確かめた日: 2026-10-04。メニューの名前は端末と OS の版で違うことがあります。

## まとめて1行で

テレビの準備 (下の 3.) が済んでいれば、パソコンから1行で、APK の取得 (ハッシュの確認つき)・接続・インストール・起動まで済みます。
adb が無ければ Google の platform-tools を作業用の場所に取ってきて使います (パソコンには入れません)。

```sh
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.sh | bash -s -- <テレビの IP>
```

```powershell
# Windows (PowerShell)
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.ps1))) <テレビの IP>
```

- 入れるのは最新のリリース (試し版も含む)。版を決めるなら `--version v0.2.1` (Windows は `-Version v0.2.1`)
- Android 11 以降の「ワイヤレス デバッグ」は、先にペア設定が要ります。テレビの「ペア設定コードでデバイスをペア設定」に出る
  IP・ポート・コードを `--pair <IP>:<ポート> <コード>` (Windows は `-Pair <IP>:<ポート> -Code <コード>`) で渡します。
  そのあとの接続先は、ワイヤレス デバッグの画面に出ている IP とポートです
- テレビに「USB デバッグを許可しますか」が出たら許可を押してください (スクリプトは 60 秒待ちます)
- 署名の違うものが入っていて上書きできないときは、消すコマンドを出して止まります (下の「署名について」)

手で1つずつやるなら、以下の 1.〜5. の手順です。

## 1. APK を手に入れる

- **リリース** — [Releases](https://github.com/danything/denpa-tv/releases) の最新から `denpa-tv-<版>.apk` を落とす。
  名前に `-debug` が付いているものは debug の署名です (下の「署名について」)
- **CI の焼きたて** — まだリリースが無い・最新の main を試したいときは、
  [Actions](https://github.com/danything/denpa-tv/actions/workflows/ci.yml) の成功した実行を開き、
  下の Artifacts から `denpa-tv-debug` を落として展開する (`app-debug.apk`)。GitHub にログインしている必要があります

### 署名について

Android は**同じ鍵で署名された APK しか上書きで入れられません**。リリースは決まった鍵で署名して出すので、
上書きで更新できます。debug の署名 (CI の APK、`-debug` のリリース) と行き来するときは、
一度アンインストールしてから入れ直してください (`adb uninstall`。設定 — 繋ぐ先 — は消えます)。

## 2. パソコンに adb を入れる

adb は Google の **Android SDK Platform-Tools** に入っています。

| OS | 入れ方 |
| --- | --- |
| Windows | `winget install --id Google.PlatformTools` ([winget の登録](https://winstall.app/apps/Google.PlatformTools)) |
| macOS | `brew install --cask android-platform-tools` ([Homebrew](https://formulae.brew.sh/cask/android-platform-tools)) |
| Linux (Debian / Ubuntu) | `sudo apt install adb` |
| どれでも | [Google の配布ページ](https://developer.android.com/tools/releases/platform-tools) から zip を落として展開し、中の `adb` を使う |

`adb version` で版が出れば入っています。

## 3. テレビで開発者向けオプションとデバッグを有効にする

### Google TV (Chromecast with Google TV、Google TV Streamer、Google TV の載ったテレビ)

1. 設定 → **システム** → **端末情報** → **Android TV OS ビルド** を決定ボタンで 7 回押す
   (「デベロッパーになりました」と出る) ([MakeUseOf の手順](https://www.makeuseof.com/secret-google-tv-developer-options/))
2. 設定 → **システム** → **開発者向けオプション** を開く
3. **USB デバッグ** をオンにする
4. Android 11 以降の端末なら **ワイヤレス デバッグ** もある (下の 4-A)。無い端末は 4-B

### Android TV (Google TV でないもの)

1. 設定 → **デバイス設定** (端末によっては「デバイス」) → **端末情報** → **ビルド** を何回か押す
   ([Google Cast の Android TV のデバッグの頁](https://developers.google.com/cast/docs/android_tv_receiver/debugging))
2. 設定 → **デバイス設定** → **開発者向けオプション** → **USB デバッグ** をオン
3. ホーム画面へ戻る (同じ頁によると、戻らないと設定が効かない)

### Fire TV

1. 設定 → **My Fire TV** → **バージョン情報** → (テレビの名前の行) を決定ボタンで 7 回押す。
   新しい Fire OS では開発者オプションが隠れていて、こうすると出る
   ([Amazon の手順](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html))
2. 設定 → **My Fire TV** → **開発者オプション** で **ADB デバッグ** をオン
3. 同じところの **不明ソースからのアプリ** をオン
4. IP アドレスは 設定 → **My Fire TV** → **バージョン情報** → **ネットワーク** で見る
   (設定 → ネットワーク の画面には出ない、と Amazon の頁にある)

## 4. パソコンからテレビに繋ぐ

パソコンとテレビは**同じネットワーク** (同じ Wi-Fi / 同じルーターの下) に置きます。

### 4-A. ワイヤレス デバッグ (Android 11 以降)

([adb の公式の手順](https://developer.android.com/tools/adb))

1. 開発者向けオプション → **ワイヤレス デバッグ** を開いてオンにする
2. **ペア設定コードによるデバイスのペア設定** を選ぶ。IP アドレス・ポート・6 桁のコードが出る
3. パソコンで `adb pair <IP>:<ポート>` を打ち、コードを入れる
4. ワイヤレス デバッグの画面に出ている **IP アドレスとポート** (ペア設定のものとは別) に繋ぐ:
   `adb connect <IP>:<ポート>`

### 4-B. ネットワーク越しの adb (Fire TV と、ワイヤレス デバッグの無い端末)

```sh
adb connect <テレビの IP>:5555
```

テレビに「このコンピュータからの USB デバッグを許可しますか」(Fire TV は同様の確認) が出たら、
**このコンピュータを常に許可** にチェックして OK を押します。

USB でしか繋げない端末は、USB で繋いで `adb tcpip 5555` を打ってから、ケーブルを抜いて上の
`adb connect` を打ちます (同じく adb の公式の手順)。

### 繋がったか確かめる

```sh
adb devices
```

`<IP>:<ポート>    device` と出れば繋がっています。

## 5. 入れる・上げる・外す

```sh
adb install -r denpa-tv-0.2.0.apk     # 入れる。-r は上書き (設定を残して更新)
adb shell am start -n io.github.danything.denpatv/.MainActivity   # 起動する
adb uninstall io.github.danything.denpatv                          # 外す
```

入れるとテレビのアプリの一覧 (Google TV なら「アプリ」の列、Fire TV なら「アプリ」の一覧) に
**denpa** が出ます。ホームの列に出ないときはアプリの一覧の中を探してください。

## 6. 不具合を知らせるとき

```sh
adb exec-out screencap -p > denpa-tv.png
adb logcat -d --pid=$(adb shell pidof -s io.github.danything.denpatv) > denpa-tv.log
```

(Windows の PowerShell では `--pid=$(...)` の代わりに、先に `adb shell pidof -s io.github.danything.denpatv` で
番号を出してから `adb logcat -d --pid=<番号>` と打つ)

## 困ったとき

| 出たもの | 理由と直し方 |
| --- | --- |
| `adb devices` で `unauthorized` | テレビの許可の確認に答えていない。テレビの画面で許可する。出ていなければ `adb kill-server` してから繋ぎ直す |
| `failed to connect` / `Connection refused` | テレビの ADB デバッグ (ネットワーク デバッグ) がオフ、IP の打ち間違い、パソコンとテレビが別のネットワーク (ゲスト Wi-Fi など) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 入っているものと署名が違う (debug とリリースを行き来した)。`adb uninstall io.github.danything.denpatv` してから入れ直す |
| `INSTALL_FAILED_OLDER_SDK` | テレビの Android が古すぎる。Android 7.0 (API 24) 以上が要る |
| 入れたのにホームに出ない | アプリの一覧を見る。テレビ向けのアプリとして入るので、普通のスマホ向けのランチャーには出ない |

## 終わったらデバッグを切る

入れ終わったら、開発者向けオプションの **USB デバッグ** / **ワイヤレス デバッグ** (Fire TV は **ADB デバッグ**)
をオフに戻してください。オンのままだと、同じネットワークの誰かがテレビにアプリを入れられます
(許可の確認は出ますが、押し間違えると通ってしまう)。更新するときにまたオンにします。
