# リリースの出し方 (管理者向け)

`v0.2.0` のようなタグを押すと、`.github/workflows/release.yml` が APK をビルドして GitHub Release に付けます。
版 (`versionName`) はタグから、`versionCode` は版の数字から作ります (0.2.13 → 213)。版は上げる一方にします
(下げると上書きで入れられない)。

## 署名の鍵を用意する (最初の1回)

上書きで更新できるように、**リリースはいつも同じ鍵で署名**します。鍵をなくすと、使っている人は
アンインストールしてから入れ直すことになるので、控えを安全な所に残してください。

```sh
keytool -genkeypair -v -keystore denpa-tv-release.jks -alias denpa-tv \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 denpa-tv-release.jks > denpa-tv-release.jks.b64
```

リポジトリの Settings → Secrets and variables → Actions に2つ入れます (別名は `denpa-tv` 固定、鍵のパスワードは keystore と同じ。keytool の既定の PKCS12 では分けられません):

| 名前 | 中身 |
| --- | --- |
| `DENPA_TV_KEYSTORE_BASE64` | `denpa-tv-release.jks.b64` の中身 |
| `DENPA_TV_KEYSTORE_PASSWORD` | keystore のパスワード |

鍵がまだ入っていない間は、debug の署名の APK (`denpa-tv-<版>-debug.apk`) を出します。鍵を入れたあとの
リリースへ移るときは、使っている人に入れ直してもらう必要があります ([install.md](install.md#署名について))。

## パッケージ名 (v1.0.0 から)

v1.0.0 から、パッケージ名は `io.doany.denpatv` です。v0.30.0 までは `io.github.danything.denpatv` でした。
古いパッケージ名のリリースは v0.30.0 で終わりで、これより後に古いパッケージ名のリリースは出しません。

- v0.30.0 の古いアプリは、v1.0.0 以上のリリースを新しいアプリとみなし、上書きの代わりに新しいアプリとして入れて開く。
  新しいアプリが初めて開いたときに設定を渡す (`data/OldApp.kt` が受け取る側)
- 設定の受け渡しは、署名が一致することで守っている。新しいアプリも古いアプリと同じ鍵 (`DENPA_TV_KEYSTORE_*`) で署名する
- v0.30.0 より前の古いアプリは v1.0.0 を見つけると上書きしようとして失敗する。v0.30.0 を出してから v1.0.0 を出すまでに間を空けると、
  その間に開いた古いアプリは v0.30.0 に上がる
- `scripts/install.sh`・`install.ps1` の `OLD_LAST` (`$oldLast`) は v0.30.0 に留める。古いアプリが入っていれば、先にこのバージョンへ上げる

## アプリの中のアップデートを確かめる

アプリの中で上げる流れ ([updates.md](updates.md)) は、手元のエミュレータで偽のリリースから確かめられます。
**debug だけ**、ビルドのときに `DENPA_TV_UPDATE_API` で GitHub のリリースの API の代わりを指せます (release・minified は
いつも GitHub)。同じ debug の鍵で、古い版と新しい版をビルドします。

```sh
export DENPA_TV_UPDATE_API=http://10.0.2.2:8732/repos/danything/denpa-tv   # エミュレータからホストの 8732
DENPA_TV_VERSION=0.6.3 DENPA_TV_VERSION_CODE=603 ./gradlew :app:assembleDebug   # 入れておく古い版
cp app/build/outputs/apk/debug/app-debug.apk old.apk
DENPA_TV_VERSION=0.7.0 DENPA_TV_VERSION_CODE=700 ./gradlew :app:assembleDebug   # 偽のリリースに置く新しい版
```

偽のリリースは `<API>/releases` に GitHub と同じ形式の JSON (`tag_name` と、`denpa-tv-0.7.0.apk`・`SHA256SUMS` の
`browser_download_url`) を返し、APK と `sha256sum` の出力を置けば足ります。`adb install old.apk` してから、
設定の「バージョン」のボタンを押して確認 → 同じボタンに出た「アップデート (…)」を押す → 許可の画面で許可して戻る、と進めます。
