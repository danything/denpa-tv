# 使っているライブラリと、選んだ理由

調べた日: 2026-10-04。版は Google Maven / Maven Central の `maven-metadata.xml` と各公式の
リリースノートで確かめたもの。**依存は少なく**を基本にして、入れるものは1つずつ理由を書く。

## 土台 (Android / AndroidX)

| もの | 版 | メモ |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | 2026-09 の安定版。Gradle 9.6 以上・JDK 17 以上・compileSdk は 37 まで ([AGP 9.4 リリースノート](https://developer.android.com/build/releases/agp-9-4-0-release-notes))。AGP 9 は Kotlin を内蔵しているので `org.jetbrains.kotlin.android` は付けない |
| Gradle | 9.8.0 | 2026-10 時点の最新 ([services.gradle.org](https://services.gradle.org/versions/current)) |
| Kotlin (Compose / serialization のコンパイラプラグイン) | 2.4.20 | Maven Central の最新の安定版 |
| Compose BOM | 2026.09.00 | |
| Compose for TV (`androidx.tv:tv-material`) | 1.1.0 | 2026-05-06 の安定版 ([リリースノート](https://developer.android.com/jetpack/androidx/releases/tv))。TV 向けのフォーカスの見せ方 (拡大・縁取り) を持つ Card / Button / Surface を使う |
| `androidx.tv:tv-foundation` | **使わない** | TV 用の Lazy レイアウトは alpha11 で非推奨、alpha12 で削除済み。普通の Compose の `LazyRow` / `LazyColumn` で足りる (同ノート) |
| Navigation 3 (`androidx.navigation3`) | 1.2.0 | 戻る履歴をただのリストとして持つ Compose 向けの新しい Navigation。行き先は `@Serializable` のクラス。**minSdk 24 を求める** ので、アプリの minSdk もこれに合わせた |
| DataStore Preferences | 1.2.1 | 覚えるのは繋ぐ先の URL だけ。SharedPreferences の後継 |
| Media3 (ExoPlayer) | 1.11.1 | 2026-09-10 の安定版 ([リリースノート](https://developer.android.com/jetpack/androidx/releases/media3))。minSdk 23 |

### minSdk 24 (Android 7.0)

Media3 と AndroidX の下限は 23 だが、Navigation 3 が 24 を求める。Android TV で 7.0 より前の
端末はもう少なく、23 のために Navigation を古い作りにする値打ちは無いと判断した。

### Media3 で何が再生できるか

- **fragmented MP4 (H.264 / AV1)** — denpa のライブ (`api/services/<id>/live`)。ExoPlayer の
  `FragmentedMp4Extractor` が読む。流しっぱなし (長さ不明) でもそのまま再生できる
- **Matroska (.mkv)** — denpa の焼いた録画。`MatroskaExtractor` が読む。**字幕の PGS
  (`S_HDMV/PGS`) にも対応している** ([MatroskaExtractor のソース](https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java))。
  絵の字幕なので、出すのは Media3 の `SubtitleView` (View)。Compose の部品はまだ絵の字幕を描けない
- **MPEG-TS (MPEG-2)** — 焼く前の録画。端末に MPEG-2 のデコーダがあれば (多くのテレビにはある)
- **AV1 のソフトデコードは入れない。** Media3 1.9 から dav1d ベースの `decoder_av1` があるが、
  **Maven には出ておらず、NDK でソースから焼く決まり** ([decoder_av1 の README](https://github.com/androidx/media/tree/release/libraries/decoder_av1)、
  [Media3 1.9.0 の紹介](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html))。
  AV1 は**端末がハードで解けるときだけ**選び (MediaCodecList で調べる)、解けなければ H.264 を頼む。
  denpa はどちらも出せるので、ソフトデコードを抱える理由が無い

## AndroidX 以外に入れたもの

### HTTP: OkHttp 5.5.0 (Retrofit も Ktor も入れない)

| 候補 | 版 (日付) | 見たところ |
| --- | --- | --- |
| **OkHttp** | 5.5.0 (2026-08) | Media3 (`media3-datasource-okhttp`) と Coil (`coil-network-okhttp`) が**同じ OkHttp を差して使える**。API・画像・映像の接続の溜めが1つになる |
| Retrofit | 3.0.0 (2025-05) | OkHttp の上に載る。叩く口が `services` / `recordings` / `resume` / `health` の4つしかなく、インターフェースを宣言する手間のほうが大きい |
| Ktor Client | 3.6.0 (2026-09) | Kotlin らしく書けるが、Android ではエンジンに結局 OkHttp を使う。Media3 と Coil に OkHttp が要るので、Ktor を足すと二重になる |

→ **OkHttp だけ**。4つの口は `DenpaApi` に手で書いた (50 行ほど)。

### JSON: kotlinx.serialization 1.11.0

| 候補 | 版 (日付) | 見たところ |
| --- | --- | --- |
| **kotlinx.serialization** | 1.11.0 (1.12.0-RC が 2026-09) | Kotlin のコンパイラプラグインで直列化のコードを作る。反射も KSP も要らない。**Navigation 3 の行き先にも同じ `@Serializable` を使う**ので、どのみち入る |
| Moshi | 1.15.2 (2024-12) | codegen には KSP が要る。最後のリリースから2年近く動いていない |

→ **kotlinx.serialization**。知らない鍵は無視する設定にしてある (denpa は JSON に鍵を足すことがある)。

### 画像: Coil 3.6.3

| 候補 | 版 (日付) | 見たところ |
| --- | --- | --- |
| **Coil 3** | 3.6.3 (2026-09) | Compose が第一。`AsyncImage` で済み、OkHttp を差せる (`coil-network-okhttp`) |
| Glide | 5.0.9 / Compose 統合 1.0.0-beta10 (2026-07) | Compose との繋ぎがまだ beta |

→ **Coil 3**。局ロゴと録画のポスターを出すだけ。

### DI: 入れない (手で渡す)

| 候補 | 版 | 見たところ |
| --- | --- | --- |
| Hilt | 2.60.1 | KSP とアノテーション処理が要り、ビルドが重くなる。画面が4つのアプリには大きすぎる |
| Koin | 4.2.2 (2026-06) | 軽いが、実行時に解決するので間違いがビルドで見つからない |
| **手で渡す** | — | `DenpaApp` (Application) が OkHttp・API・設定・デコーダの情報を1つずつ持ち、画面に渡す |

→ **入れない**。持つものが5つしかなく、追いやすさを取った。

### コルーチン: kotlinx-coroutines-android 1.11.0

DataStore と通信の待ちに使う。AndroidX が既に依存しているので、版を明示しているだけ。

## テストだけで使うもの

- JUnit 4.13.2 — Android のローカルテストの標準
- kotlinx-coroutines-test — `runTest`
- OkHttp の `mockwebserver3` — denpa の JSON を返す偽のサーバ (OkHttp と同じ版)
