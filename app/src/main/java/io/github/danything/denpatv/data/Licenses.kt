package io.github.danything.denpatv.data

import android.content.res.AssetManager
import kotlinx.serialization.Serializable

/**
 * 設定の「ライセンス」に出すもの。中身は assets の `licenses/licenses.json` (と同じ所の全文のファイル) にある。
 *
 * - `licenses`: ライセンスの ID (SPDX) と、画面に出す名前
 * - `components`: このアプリ (いちばん上) と、APK に入っているもの。`file` は全文、`notice` は NOTICE (同梱するものにあれば)。
 *   `groups` は Maven のグループ (`androidx.*` は前方一致)。`app/build.gradle.kts` の `checkLicenses` が、
 *   リリースの APK に入る依存のグループと突き合わせる (足りなくても、余っていてもビルドを止める)
 *
 * `LICENSE` (このアプリの AGPL) はリポジトリの頭の LICENSE を、ビルドのときに assets へ写したもの
 */
@Serializable
data class LicenseManifest(
    val licenses: Map<String, String>,
    val components: List<LicensedComponent>,
)

@Serializable
data class LicensedComponent(
    val name: String,
    /** ライセンスの ID (`LicenseManifest.licenses` の鍵) */
    val license: String,
    /** 全文のファイル (`licenses/` の中) */
    val file: String,
    val notice: String? = null,
    /** ソースコードの置き場 (このアプリだけ) */
    val source: String? = null,
    val groups: List<String> = emptyList(),
)

object Licenses {
    const val DIR = "licenses"
    const val MANIFEST = "licenses.json"

    fun parse(text: String): LicenseManifest = lenientJson.decodeFromString(text)

    /** ライセンスの ID を画面に出す名前に。知らない ID はそのまま */
    fun LicenseManifest.licenseName(component: LicensedComponent): String = licenses[component.license] ?: component.license

    fun load(assets: AssetManager): LicenseManifest = parse(read(assets, MANIFEST))

    /** 画面に出す文。NOTICE があれば先に置き、全文を続ける */
    fun text(assets: AssetManager, component: LicensedComponent): String =
        listOfNotNull(component.notice?.let { read(assets, it) }, read(assets, component.file)).joinToString("\n\n")

    private fun read(assets: AssetManager, name: String): String = assets.open("$DIR/$name").use { it.readBytes().decodeToString() }
}
