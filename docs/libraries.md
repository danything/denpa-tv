# 使っているライブラリと、選んだ理由

調べた日: 2026-10-04 (エミュレータで動かすテストは 2026-10-05)。版は Google Maven / Maven Central の `maven-metadata.xml` と各公式の
リリースノートで確かめたもの。**依存は少なく**を基本にして、入れるものは1つずつ理由を書く。

## 土台 (Android / AndroidX)

| もの | 版 | メモ |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | 2026-09 の安定版。Gradle 9.6 以上・JDK 17 以上・compileSdk は 37 まで ([AGP 9.4 リリースノート](https://developer.android.com/build/releases/agp-9-4-0-release-notes))。AGP 9 は Kotlin を内蔵しているので `org.jetbrains.kotlin.android` は付けない |
| Gradle | 9.8.0 | 2026-10 時点の最新 ([services.gradle.org](https://services.gradle.org/versions/current)) |
| Kotlin (Compose / serialization のコンパイラプラグイン) | 2.4.20 | Maven Central の最新の安定版 |
| Compose BOM | 2026.09.00 | |
| Compose for TV (`androidx.tv:tv-material`) | 1.1.0 | 2026-05-06 の安定版 ([リリースノート](https://developer.android.com/jetpack/androidx/releases/tv))。TV 向けのフォーカスの見せ方 (拡大・縁取り) を持つ Card / Button / ListItem / Surface と、いちばん上の **NavigationDrawer** を使う。Android TV のデザインの指針は、行き先を 5〜6 までのナビゲーション ドロワーにまとめ、畳んだ状態 (アイコンの帯) も見せるよう勧めていて、NavigationDrawer / ModalNavigationDrawer はどちらも実験扱いではない ([ナビゲーション ドロワーの指針](https://developer.android.com/design/ui/tv/guides/components/navigation-drawer)、2026-10-04 に確認)。メニューの3つのアイコンは Material Symbols、再生の操作の札の印は Material Icons (どちらも Apache License 2.0) を vector drawable として置いた (アイコンのライブラリは入れない) |
| `androidx.tv:tv-foundation` | **使わない** | TV 用の Lazy レイアウトは alpha11 で非推奨、alpha12 で削除済み。普通の Compose の `LazyRow` / `LazyColumn` で足りる (同ノート) |
| Navigation 3 (`androidx.navigation3`) | 1.2.0 | 戻る履歴をただのリストとして持つ Compose 向けの新しい Navigation。行き先は `@Serializable` のクラス。**minSdk 24 を求める** ので、アプリの minSdk もこれに合わせた |
| DataStore Preferences | 1.2.1 | 繋ぐ先 (とトークン)・ライブの画質と最後に観た局・CM を飛ばすか・録画の速さ・字幕・音声 (名前とデュアルモノの側)・アップデートを確かめた時刻を覚える。SharedPreferences の後継 |
| Media3 (ExoPlayer) | 1.11.1 | 2026-09-10 の安定版 ([リリースノート](https://developer.android.com/jetpack/androidx/releases/media3))。minSdk 23 |

### minSdk 24 (Android 7.0)

Media3 と AndroidX の下限は 23 だが、Navigation 3 が 24 を求める。Android TV で 7.0 より前の
端末はもう少なく、23 のために Navigation を古い作りにする値打ちは無いと判断した。

### Media3 で何が再生できるか

- **fragmented MP4 (H.264 / AV1)** — denpa のライブ (`api/services/<id>/live`) と追っかけ (`api/recordings/<id>/chase`)。ExoPlayer の
  `FragmentedMp4Extractor` が読む。流しっぱなし (長さ不明) でもそのまま再生できる
- **Matroska (.mkv)** — denpa の焼いた録画。`MatroskaExtractor` が読む。**字幕の PGS
  (`S_HDMV/PGS`) にも対応している** ([MatroskaExtractor のソース](https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java))。
  絵の字幕なので、出すのは Media3 の `SubtitleView` (View)。Compose の部品はまだ絵の字幕を描けない
- **MPEG-TS (MPEG-2)** — 焼く前の録画と、ライブ・追っかけの MPEG-2 (`?codec=raw`、いちばん遅れが少ない)。端末に MPEG-2 のデコーダがあれば
- **ARIB の字幕は Media3 では解かない。** Media3 に ARIB の字幕の読み手は無く、libaribcaption を NDK で抱えるのは重い。
  denpa が描いた絵 (放送の PTS 付き) を受け取って、Compose の Canvas で重ねる (`ui/RawCaptions.kt`)。
  時計は **TsExtractor に自分の `TimestampAdjuster` を渡して**控え、寄せ幅 (`getTimestampOffsetUs`) で再生位置を放送の
  PTS に戻す。読み手は `DefaultExtractorsFactory` と同じ作りで、ほかの形はそのまま (`TsClock`)。
  PES を自分で覗いて最初の PTS を拾う手もあるが、Media3 がどの PES の PTS を 0 にしたかと食い違いうる (映像と音声で数百 ms 違う)。
  寄せ幅そのものを読めばシーク (寄せ直し) も一周 (Media3 は伸ばし続ける) もそのまま合う
- **チャプター** — Matroska の Chapters を `Chapter` として track の `Metadata` に出す (1.11.0 から。
  [リリースノート](https://github.com/androidx/media/blob/release/RELEASENOTES.md))。CM 飛ばしはこれを読むので、
  自前の EBML 読みも denpa の API も要らない
- **AV1 のソフトデコードは入れない。** Media3 1.9 から dav1d ベースの `decoder_av1` があるが、
  **Maven には出ておらず、NDK でソースから焼く決まり** ([decoder_av1 の README](https://github.com/androidx/media/tree/release/libraries/decoder_av1)、
  [Media3 1.9.0 の紹介](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html))。
  AV1 は**端末がハードで解けるときだけ**選び (MediaCodecList で調べる)、解けなければ H.264 を頼む。
  denpa はどちらも出せるので、ソフトデコードを抱える理由が無い
- **二か国語 (デュアルモノ) の片側を両耳へ配るのは、音の出口の手前に挟む自前の `AudioProcessor`** (`ui/DualMono.kt`。
  混ぜ方は Media3 の `ChannelMixingMatrix` / `AudioMixingUtil`)。生の TS だけで、焼いたライブ・追っかけは denpa に頼み直す (`?audio=<id>`)

## AndroidX 以外に入れたもの

### HTTP: 入れない (映像は Media3 の HttpEngine / DefaultHttpDataSource、API は HttpURLConnection)

**2026-10-04 に OkHttp を外した。** 「ライブラリはできるだけ少なく、最新のドキュメントの勧めどおりに」
という方針で見直した。

Media3 の [ネットワーク スタックの頁](https://developer.android.com/media/media3/exoplayer/network-stacks?hl=ja)
(2026-10-04 に確認) の勧めは次の順:

| 候補 | 見たところ |
| --- | --- |
| **HttpEngine** | いちばんの勧め。Android 14 (S 拡張 7) から OS に入っていて APK が増えない。HTTP/2・HTTP/3 (QUIC)。Media3 の `HttpEngineDataSource` は `media3-datasource` (ExoPlayer が既に引いている) にあり、**依存が増えない** |
| Cronet (Google Play 開発者サービス経由) | 2番目。HTTP/3 を話し APK は 100 KB 未満だが、**Play 開発者サービスが要る** (Fire TV には無い)。`media3-datasource-cronet` と Play 開発者サービスの依存が増える |
| Cronet (同梱) | Play 開発者サービスが無くても動くが APK が約 8 MB 増える |
| OkHttp | HTTP/2 まで。APK 1 MB 未満。`media3-datasource-okhttp` と OkHttp が増える |
| **DefaultHttpDataSource** | OS の HttpURLConnection。APK が増えない |

→ **映像は Android 14 からは HttpEngine、それより前は DefaultHttpDataSource** (どちらも Media3 の中の
もので依存は増えない)。どちらも頁の勧めどおり `DefaultDataSource.Factory` で包み、HttpEngine は
アプリで1つを使い回す (`DenpaApp.httpEngine`)。

→ **API の JSON は OS の HttpURLConnection** (`data/Http.kt`、IO の上で呼ぶ。時間切れは接続 10 秒・
読み 30 秒と書いてある)。叩くのは局と録画の一覧・番組の中身 (`detail`)・観た位置・録画の削除・`health`・テレビの登録 (`api/device/*`) くらい。
生の TS の字幕の絵 (`api/…/captions`、長さ付きのこまが続く本文) も同じ HttpURLConnection で読み続ける (`data/Captions.kt`)。

→ **アプリの中のアップデート (GitHub のリリースを引く・APK を取る) も HttpURLConnection** (`data/Update.kt`)。入れるのは OS の
`PackageInstaller` のセッション (`Updater.kt`)。アップデートのライブラリは入れない。

→ **denpa の知らせ (`api/events`、Server-Sent Events) も HttpURLConnection** で読む (`data/Sse.kt`)。EventSource の
ライブラリ (OkHttp の `okhttp-sse` など) は入れない。行の読み分けは数十行で、60 秒何も届かなければ死んだ繋ぎと見なすのは
読みの時間切れ (`readTimeout`) で足りる。繋ぐのはアプリが前に出ている間だけで、Compose の `LifecycleStartEffect`
(`androidx.lifecycle:lifecycle-runtime-compose`。Compose UI が既に引いているので依存は増えない) で Activity に沿わせる
(`ProcessLifecycleOwner` の `lifecycle-process` は使わない)。

OkHttp (と Retrofit / Ktor) を採らない理由:

- **denpa は家の LAN の素の HTTP/1.1。** HTTP/2・HTTP/3 が効くのは CDN 越しの適応配信で
  (同じ頁もそう書いている)、LAN の1本の流れでは OkHttp でも OS の HTTP でも同じ
- 前は「API・画像・映像で同じ OkHttp を分け合える」のが理由だったが、画像のライブラリも外したので
  (下記) その理由が無くなった。Media3 の勧め (HttpEngine) に乗るほうが依存が少ない

**キャッシュ (`CacheDataSource`) は使わない。** ライブは流しっぱなしの1本で、溜めても二度と読まない。
録画は LAN の denpa から読むので、端末に溜めても速くならず、テレビの少ない容量を食うだけ。

### JSON: kotlinx.serialization 1.11.0

| 候補 | 版 (日付) | 見たところ |
| --- | --- | --- |
| **kotlinx.serialization** | 1.11.0 (1.12.0-RC が 2026-09) | Kotlin のコンパイラプラグインで直列化のコードを作る。反射も KSP も要らない。**Navigation 3 の行き先にも同じ `@Serializable` を使う**ので、どのみち入る |
| Moshi | 1.15.2 (2024-12) | codegen には KSP が要る。最後のリリースから2年近く動いていない |

→ **kotlinx.serialization**。知らない鍵は無視する設定にしてある (denpa は JSON に鍵を足すことがある)。

### 画像: 入れない (HttpURLConnection + BitmapFactory + LruCache)

**2026-10-04 に Coil を外した。** 出すのは局ロゴと録画のポスターだけ (小さい絵) で、
要るのは「出す大きさに縮めて読む」と「読んだものを覚えておく」の2つ。どちらも Android の
[大きな画像を効率よく読み込む](https://developer.android.com/topic/performance/graphics/load-bitmap) の
やり方 (`inSampleSize` で 2 の冪に縮める) と `LruCache` で 60 行ほどに収まる (`data/Images.kt`、
`ui/RemoteImage.kt`)。Coil 3.6.3 / Glide 5 の持つディスクキャッシュ・変換・GIF などは使わない。

録画の一覧の上に敷く大きな絵 (合わせている録画のポスター) と、詳しくの後ろのうすい絵も同じもので出す。
どちらもぼかして暗くするので、**読むときに 64×36 まで縮めて箱でぼかし、描くときは引き伸ばすだけ** (`Images.loadBlurred`)。
描くたびにぼかす RenderEffect (`Modifier.blur`、Android 12 から) は使わない — 軽く、Android の版で見た目も変わらない。
ほかに、**出す大きさちょうどに読む** (2 の冪で縮めたあと BitmapFactory の拡大率で)、**ポスターは RGB_565**
(透けないので ARGB の半分)、覚えておく量は端末のメモリの級の 1/8 (8〜64MB)。どれも BitmapFactory だけで足りる。
一覧の上の段の組み方は Google TV の「没入型の一覧」に倣ったが、その部品 (`ImmersiveList`) は
tv-material 1.1.0 には無い (alpha の頃にあって外された) ので、Box に絵と見出しと格子を重ねて組んだ (`ui/RecordingsScreen.kt`)

### Baseline Profile: 入れない (手で書いた `app/src/main/baseline-prof.txt`)

入れたときに、よく通るコードを先に機械語にしておく ([Baseline Profile](https://developer.android.com/topic/performance/baselineprofiles/overview))。
**ライブラリは足していない。** APK に入った profile を端末に置く `androidx.profileinstaller` は Compose がもともと連れてきていて
(Play ストアを通さずに入れても効く)、Compose・tv-material・Media3 は自分の profile を持っている。足したのはアプリの分だけで、
`app/src/main/baseline-prof.txt` に起動・録画の一覧・ライブで通るクラス (`ui/**`・`data/**`・`DenpaApp`・`MainActivity`) を書いた。
AGP がライブラリの分とまとめ、R8 で縮めた名前に書き換えて `assets/dexopt/baseline.prof` に入れる (縮めた APK で確かめた)。

`androidx.baselineprofile` (Gradle の plugin と、Macrobenchmark で端末を動かして profile を作る組) は入れない。
作るには root の取れる (userdebug の) エミュレータで Macrobenchmark を走らせる別のモジュールが要り、CI の手間に見合わない。
アプリのコードは小さいので、包みごと書いておけば足りる

### QR コード: Project Nayuki の QR Code generator を同梱する (MIT)

繋ぐ画面で、スマホに読ませる QR を出すのに使う (2026-10-04 に決めた)。

| 候補 | 版 (日付) | 見たところ |
| --- | --- | --- |
| ZXing (`com.google.zxing:core`) | 3.5.4 (2025-11) | 定番。読み取り (カメラ) まで入った大きなライブラリで、要るのは書き出しの一部だけ。依存が1つ増える |
| **[QR Code generator](https://github.com/nayuki/QR-Code-generator) (Project Nayuki)** | main 3c6d0b3 (2026-08-31) | 書き出しだけの小さなもの。Java 版は依存なしの 4 ファイル (約 1,300 行)。MIT |

→ **Nayuki の Java 版を `app/src/main/java/io/nayuki/qrcodegen/` に同梱**
(`QrCode` / `QrSegment` / `BitBuffer` / `DataTooLongException`。漢字向けの `QrSegmentAdvanced` は使わないので入れない)。
依存を増やさず、描くのは Compose の Canvas (`ui/QrCodeView.kt`)。各ファイルの頭に元の著作権表示と MIT の許諾文を残してある。
更新は Renovate では追えないので、上流に直しが入ったら手で写す。

### トークンの置き場: 暗号化しない DataStore

テレビを denpa に登録して受け取るトークンは、ほかの設定と同じ DataStore (アプリの領域) に置く。
EncryptedSharedPreferences (`androidx.security:security-crypto`) は 1.1.0-beta01 (2025-06) で**全部非推奨**になり、
「プラットフォームの API と Android Keystore を直に使え」とされている
([リリースノート](https://developer.android.com/jetpack/androidx/releases/security))。アプリの領域は他のアプリから読めず、
トークンは denpa の画面から (またはこのアプリの設定の「繋ぐ先」の札で) いつでも無効にできるので、暗号化の仕組みは足さない。

### ホームの「続きを視聴」: 入れない (OS の `TvContract.WatchNextPrograms`)

2026-10-05 に決めた。録画を途中で閉じたら、Google TV / Android TV のホームの「続きを視聴」に出す (`data/WatchNext.kt`、`data/WatchNextRows.kt`)。

| 候補 | 見たところ |
| --- | --- |
| `androidx.tvprovider` (`TvContractCompat` / `WatchNextProgram.Builder`) | 列の名前と ContentValues を組む Builder。API 26 未満でも呼べる形だが、**Watch Next そのものが Android 8.0 (API 26) からで、7.x では何も出せない**のは同じ。依存が1つ増える |
| **OS の `TvContract.WatchNextPrograms` を ContentResolver で直に** | API 26 から OS にある。書くのは十数列の ContentValues・読むのは自分の行 (TvProvider がパッケージで絞る) だけ。**依存が増えない** |

→ **OS の API を直に**。Android 7.x (minSdk 24・25) では何もしない。権限は要らない (自分の行だけ)。何を書く・直す・消すかは
素の関数にして単体テストで確かめ (`WatchNextTest`)、ContentResolver に書く所は薄くした。

**ポスターはアプリが取って `content://` で渡す** (`WatchNextPosters`、読むだけの ContentProvider)。denpa のポスターの URL を
そのまま渡すと、家の外の denpa (トークンが要る) ではホームが読めず、家の LAN でもホームのアプリが素の HTTP を読むとは限らない。
ホームが読めるよう exported にしてあり、ほかのアプリからも「続きを視聴」に出ている録画のポスターは読める (出すのはそれだけ)。

### DI: 入れない (手で渡す)

| 候補 | 版 | 見たところ |
| --- | --- | --- |
| Hilt | 2.60.1 | KSP とアノテーション処理が要り、ビルドが重くなる。画面が数枚のアプリには大きすぎる |
| Koin | 4.2.2 (2026-06) | 軽いが、実行時に解決するので間違いがビルドで見つからない |
| **手で渡す** | — | `DenpaApp` (Application) が HttpEngine・設定・デコーダの情報を、`Repository` が繋ぐ先ごとの API を持ち、画面に渡す |

→ **入れない**。持つものが数個しかなく、追いやすさを取った。

### コルーチン: kotlinx-coroutines-android 1.11.0

DataStore と通信の待ちに使う。AndroidX が既に依存しているので、版を明示しているだけ。

## まとめ: AndroidX / Kotlin の外から入れているもの

なし。kotlinx.serialization と kotlinx.coroutines は Kotlin 公式 (JetBrains) のライブラリで、
Navigation 3 と DataStore が既に使っている。QR の符号化だけはソースを同梱している (上の「QR コード」)。

## テストだけで使うもの

- JUnit 4.13.2 — Android のローカルテストの標準
- kotlinx-coroutines-test — `runTest`
- 偽の denpa は JDK の `com.sun.net.httpserver.HttpServer` (依存を足さない)

### エミュレータで動かすテスト (`smoke/`)

2026-10-05 に足した。v0.6.0〜0.6.1 は Android TV 12 で再生を始めた瞬間に落ちていた (denpa-tv#24、`HttpEngine` の
NoClassDefFoundError)。**R8 で縮めた APK を古い Android で動かしたときだけ**出る落ち方で、debug の APK の単体テストでは
見つからない。そこで release と同じ縮め方の APK を、CI で API 24・28・31・34・36 の Android TV のエミュレータに入れて
ライブと録画を映す (`.github/workflows/ci.yml` の `emulator`)。v0.6.1 に当てると、API 31 で `live` が
`NoClassDefFoundError: Failed resolution of: Landroid/net/http/HttpEngine;` で落ちることを確かめてある。

| 決めたこと | 理由 |
| --- | --- |
| **縮めた APK は `minified` の build type** (`initWith(release)`、署名だけ debug の鍵) | Macrobenchmark の `benchmark` と同じ作り。release の鍵は CI のシークレットにしか無く、PR では使えない。R8 の設定は release そのまま |
| **テストはアプリと別の APK・別のプロセス** (`com.android.test` の self-instrumenting。これも Macrobenchmark と同じ) | アプリの `androidTest` は縮めたアプリと同じプロセスで動き、テストが使うクラス (Kotlin の標準ライブラリ・`androidx.tracing`) を R8 が消してしまう (試したら `ClassNotFoundException: androidx.tracing.Trace` で起き上がらなかった)。アプリに keep を足すと縮め方が release とずれる。外から触れば縮め方はそのままで、アプリが落ちてもテストは生き残り、落ちた記録 (logcat の crash) を失敗の文に入れられる |
| **アプリのクラスには触らない** | 縮めると名前が変わり、使わないものは消える。触るのは Android の口だけ: 起動とリンク (`denpa://…`) は Intent、見るのは画面の文字 (アクセシビリティの木) と画面の絵 (`UiAutomation.takeScreenshot`)、キーは `UiAutomation`。繋ぐ先も DataStore に直に書けない (別のプロセスで、アプリは debuggable でない) ので、繋ぐ画面の欄に URL を入れて「繋ぐ」を押す |
| **映像が出たかは画面の絵の色で見る** | 偽の映像は地を1色にしてあり (`scripts/smoke-media.sh`)、画面を撮ってその色の点が 3 割を超えたら出ている。Player の状態は外から読めない |
| **偽の denpa はテストのプロセスで 127.0.0.1 に立てる** (`java.net.ServerSocket` で HTTP/1.1 を数十行) | Android には `com.sun.net.httpserver` が無い。MockWebServer (OkHttp) は Range・SSE を出し分けるのに Dispatcher を書くことになり、手で書くのと手間が変わらないので足さない |
| **映像は H.264 だけ** (fragmented MP4 と Matroska、合わせて約 100 KB をリポジトリに置く) | Android TV のエミュレータは MPEG-2 のデコーダを有効にしていない (`c2.android.mpeg2.decoder` は `domain="tv"` で、エミュレータでは使えない)。生の TS (MPEG-2) の再生は確かめられない |

入れたもの (テストの APK だけ。アプリには入らない):

- **`androidx.test:runner` 1.7.0** — `AndroidJUnitRunner` (端末で JUnit 4 を走らせる標準)。`InstrumentationRegistry` (`androidx.test:monitor`) もこれが引く
- **JUnit 4.13.2** — 単体テストと同じ

入れなかったもの: `androidx.test.ext:junit` (`@RunWith(AndroidJUnit4::class)` は無くても `AndroidJUnitRunner` が JUnit 4 のクラスを走らせる)・
`androidx.test:core` (`ActivityScenario` は同じプロセスのアプリにしか使えない)・UiAutomator (要るのは文字を探す・押す・キーを送るだけで、
OS の `UiAutomation` で足りる)・Compose の `ui-test` (縮めたアプリの Compose には外から繋げない)
