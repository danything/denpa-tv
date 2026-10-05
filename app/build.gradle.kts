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

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
