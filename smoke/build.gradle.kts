/*
 * 縮めた APK (:app の minified) をエミュレータで動かすテスト (SmokeTest、CI の emulator の列)。
 *
 * **アプリとは別の APK・別のプロセスで動かす** (`com.android.test` の self-instrumenting。Macrobenchmark と同じ作り)。
 * アプリの中で動かす androidTest だと、テストが使うクラス (Kotlin の標準ライブラリ・androidx.tracing) を R8 が消すので
 * アプリに keep を足すことになり、縮め方が release とずれる。外から触れば release と同じ縮め方のまま確かめられ、
 * アプリが落ちてもテストは生き残って落ちた跡 (logcat の crash) を拾える
 */
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.danything.denpatv.smoke"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // :app の minified を入れて確かめる
        create("minified") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// debug のアプリに向けては走らせない (縮めた APK を確かめるためのものなので)
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.junit)
}
