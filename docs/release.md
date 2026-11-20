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

## v1.0.0 以上を出すときの注意

**v1.0.0 以上のリリースは出さず、0.x のまま続けます。**
v0.30.0 には、v1.0.0 以上のリリースを別のパッケージ (`io.doany.denpatv`) の新しいアプリとして入れる流れが入っています。
パッケージ名は `io.github.danything.denpatv` のままにすると決めたので、v0.31.0 でこの流れを外しました。
v0.30.0 のまま上げていないテレビは、v1.0.0 以上のリリースが出ると次のように動きます
(v0.30.0 のコードと AOSP の `PackageInstallerSession` で確かめた。実機では試していない)。

- アプリの中のアップデートは、いまより新しい中でいちばん新しいバージョンを選ぶ。v1.0.0 以上が 1 つでも出ると、0.x の新しいリリースがあっても v1.0.0 以上を選ぶ
- 録画の一覧の上に「新しいアプリに移る (v1.0.0)」が出る。押すと APK をダウンロードしてハッシュを照らし、パッケージ名に `io.doany.denpatv` を指定してインストールのセッションを渡す
- APK のパッケージ名は `io.github.danything.denpatv` なので、OS は確認画面を出す前に `INSTALL_FAILED_INVALID_APK` で断る。
  画面には「インストールに失敗しました (…)」が出て、押し直しても同じ結果になる。何も入らず、設定と視聴はそのまま使える

このため、v0.30.0 のテレビはアプリの中のアップデートで先へ上がれなくなります。v0.31.0 を長く出しておいても、
上げていないテレビは v1.0.0 以上を選ぶので防げません。0.x のままなら、v0.30.0 からもふつうに上がれます。
`versionCode` は `major × 10000 + minor × 100 + patch` なので、minor は 100 を超えて上げてかまいません (patch は 99 まで)。

v1.0.0 以上を出すと決めたときは、v0.30.0 のテレビは [install.md](install.md) の 1 行のスクリプトで上書きインストールする、
とリリースノートに書きます。同じパッケージ名と同じ鍵なので、設定は残ります。

`io.doany.denpatv` という名前のアプリが入っているテレビでは、v0.30.0 は新しいバージョンを確かめず、「新しいアプリを開く」だけを出します (押すとそのアプリが開く)。
このパッケージ名のアプリは出しません。
