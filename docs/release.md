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

リポジトリの Settings → Secrets and variables → Actions に4つ入れます:

| 名前 | 中身 |
| --- | --- |
| `DENPA_TV_KEYSTORE_BASE64` | `denpa-tv-release.jks.b64` の中身 |
| `DENPA_TV_KEYSTORE_PASSWORD` | keystore のパスワード |
| `DENPA_TV_KEY_ALIAS` | `denpa-tv` |
| `DENPA_TV_KEY_PASSWORD` | 鍵のパスワード |

鍵がまだ入っていない間は、debug の署名の APK (`denpa-tv-<版>-debug.apk`) を出します。鍵を入れたあとの
リリースへ移るときは、使っている人に入れ直してもらう必要があります (docs/install.md の「署名について」)。
