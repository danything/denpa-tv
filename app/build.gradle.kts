import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.danything.denpatv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.danything.denpatv"
        // Navigation 3 の下限 (Android 7.0)。Media3 と AndroidX の 23 より1つ上 (docs/libraries.md)
        minSdk = 24
        targetSdk = 37
        // タグ (v0.2.0) から CI が渡す。手元では 0.0.0-dev
        versionName = System.getenv("DENPA_TV_VERSION") ?: "0.0.0-dev"
        versionCode = System.getenv("DENPA_TV_VERSION_CODE")?.toInt() ?: 1
    }

    /*
     * リリースの署名。鍵は CI のシークレットから渡す (.github/workflows/release.yml、docs/release.md)。
     * **鍵が同じでないと上書きで入れられない** ので、一度決めた鍵を使い続ける。
     * 鍵が無ければ署名の設定を作らない (release は署名なしになり、CI は debug の APK を出す)。
     * keystore は PKCS12 で、鍵のパスワードは keystore と同じ (PKCS12 は分けられない)。別名は秘密ではないのでここに書く
     */
    val keystore = System.getenv("DENPA_TV_KEYSTORE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DENPA_TV_KEYSTORE_PASSWORD")
                keyAlias = "denpa-tv"
                keyPassword = storePassword
            }
        }
    }

    /*
     * 新しい版を引く GitHub のリリース (Updater)。**debug だけ** 焼くときに `DENPA_TV_UPDATE_API` で差し替えられる
     * (手元のエミュレータで、偽のリリースから上げる流れを確かめる。docs/release.md の「アプリの中のアップデートを確かめる」)。
     * release・minified は差し替えない
     */
    val githubReleases = "https://api.github.com/repos/danything/denpa-tv"
    defaultConfig.buildConfigField("String", "UPDATE_API", "\"$githubReleases\"")

    buildTypes {
        debug {
            System.getenv("DENPA_TV_UPDATE_API")?.let { buildConfigField("String", "UPDATE_API", "\"$it\"") }
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        /*
         * 端末で確かめる版 (:smoke の SmokeTest)。**release と同じに R8 で縮め、署名だけ debug の鍵** にする
         * (Macrobenchmark の `benchmark` と同じ作り)。縮めた APK にしか出ない落ち方がある (denpa-tv#24) ので debug では
         * 確かめられず、release の鍵は CI のシークレットにしか無い
         */
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 新しい版と比べるのに VERSION_NAME を使う (Updater)
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

/*
 * 字幕の字 (denpa が字幕を焼いていたのと同じ丸ゴシック。Denpa Font = danything/denpa-font のリリース、SIL OFL 1.1)。
 * 4.2MB あるのでリポジトリには置かず、**焼くときに取ってきて assets に入れる** (ui/CaptionFont.kt が読む)。
 * 版はタグ、中身は sha256 で留める (denpa の Dockerfile の DENPA_FONT_VERSION と揃える)。**Renovate が版を上げたら、
 * そのリリースの SHA256SUMS の denpa-font.ttf の値を下へ写す** (写さないと焼けない)
 */
// renovate: datasource=github-releases depName=danything/denpa-font
val denpaFontVersion = "v2.0"
val denpaFontSha256 = "dd0dea4fe80fd37239a92fd2b89e95be092d02bf84abaf2694235c7e2386c1dd"

abstract class FetchCaptionFont : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha256.get()) { "字幕の字の sha256 が違います: ${url.get()} ($got)" }
        dir.resolve("denpa-font.ttf").writeBytes(bytes)
    }
}

val fetchCaptionFont = tasks.register<FetchCaptionFont>("fetchCaptionFont") {
    url.set("https://github.com/danything/denpa-font/releases/download/$denpaFontVersion/denpa-font.ttf")
    sha256.set(denpaFontSha256)
    outputDir.set(layout.buildDirectory.dir("generated/captionFont"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(fetchCaptionFont, FetchCaptionFont::outputDir) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
