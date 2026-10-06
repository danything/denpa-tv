# リリースの出し方 (管理者向け)

`v0.2.0` のようなタグを押すと、`.github/workflows/release.yml` が APK を焼いて GitHub Release に付けます。
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
リリースへ移るときは、使っている人に入れ直してもらう必要があります (docs/install.md の「署名について」)。

## アプリの中のアップデートを確かめる

アプリの中で上げる流れ (README の「アップデート」) は、手元のエミュレータで偽のリリースから確かめられます。
**debug だけ**、焼くときに `DENPA_TV_UPDATE_API` で GitHub のリリースの API の代わりを指せます (release・minified は
いつも GitHub)。同じ debug の鍵で、古い版と新しい版を焼きます。

```sh
export DENPA_TV_UPDATE_API=http://10.0.2.2:8732/repos/danything/denpa-tv   # エミュレータからホストの 8732
DENPA_TV_VERSION=0.6.3 DENPA_TV_VERSION_CODE=63 ./gradlew :app:assembleDebug   # 入れておく古い版
cp app/build/outputs/apk/debug/app-debug.apk old.apk
DENPA_TV_VERSION=0.7.0 DENPA_TV_VERSION_CODE=70 ./gradlew :app:assembleDebug   # 偽のリリースに置く新しい版
```

偽のリリースは `<API>/releases` に GitHub と同じ形の JSON (`tag_name` と、`denpa-tv-0.7.0.apk`・`SHA256SUMS` の
`browser_download_url`) を返し、APK と `sha256sum` の出力を置けば足ります。`adb install old.apk` してから、
設定の「アップデートを確かめる」→ 知らせを押す → 許可の画面で許可して戻る、と進めます。
