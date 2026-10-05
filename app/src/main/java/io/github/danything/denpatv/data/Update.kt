package io.github.danything.denpatv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * アプリの中から新しい版に上げる。**scripts/install.sh と同じ選び方・確かめ方**: GitHub のリリース (試し版も含む) の
 * `denpa-tv-<版>.apk` を取り、同じリリースの `SHA256SUMS` とハッシュが合わなければ (無ければ) 入れない。
 * Android の部分 (PackageInstaller・覚えておくところ) は `Updater`
 */

/** 版 (semver)。`v0.4.0`・`0.4.0-rc.1` を読む。比べ方は semver の決まりどおり (試し版は同じ数字の正式版より前) */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // 試し版の印が無いほうが新しい
        if (pre.isEmpty() || other.pre.isEmpty()) return compareValues(other.pre.size.coerceAtMost(1), pre.size.coerceAtMost(1))
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> compareValues(an, bn)
                // 数だけの印は文字の印より前
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    override fun toString() = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

        fun parse(text: String): Version? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return Version(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}

/** 手元で焼いた版 (`0.0.0-dev`)。リリースの鍵と署名が違うので上げない */
fun isDevBuild(versionName: String): Boolean = Version.parse(versionName)?.let { it.major == 0 && it.minor == 0 && it.patch == 0 } ?: true

/** `sha256sum` の出力 (`<hash>  <name>`、バイナリ扱いなら `<hash> *<name>`) を名前 → ハッシュ (小文字) に */
fun parseSha256Sums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
    val m = SUM_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
    m.groupValues[2] to m.groupValues[1].lowercase()
}.toMap()

private val SUM_LINE = Regex("""([0-9A-Fa-f]{64}) [ *](.+)""")

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

/** 上げる先。覚えておいて、次に開いたときも (確かめ直す前でも) 知らせを出す */
@Serializable
data class Update(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val sumsUrl: String?,
    val size: Long = 0,
) {
    val label: String get() = "v$version"
}

/**
 * リリースの中から、**いまより新しい中でいちばん新しい版** を選ぶ。試し版も含む (install.sh と同じ)。
 * `-debug` の APK (鍵が無いときに debug の署名で出したもの) は上書きできないので選ばない。新しいものが無ければ null
 */
fun selectUpdate(releases: List<GitHubRelease>, current: Version): Update? = releases.asSequence()
    .filter { !it.draft }
    .mapNotNull { release ->
        val version = Version.parse(release.tagName) ?: return@mapNotNull null
        val apk = release.assets.firstOrNull { it.name == "denpa-tv-$version.apk" }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.endsWith("-debug.apk") }
            ?: return@mapNotNull null
        val sums = release.assets.firstOrNull { it.name == "SHA256SUMS" }
        version to Update(version.toString(), apk.name, apk.url, sums?.url, apk.size)
    }
    .filter { (version, _) -> version > current }
    .maxByOrNull { (version, _) -> version }
    ?.second

/** 入れずに断る (ハッシュが合わない・無い)。1行で出す */
class UpdateRejected(message: String) : IOException(message)

/**
 * GitHub のリリースを引き、APK を取ってくる。`api` は手元のテストで差し替える
 */
class UpdateSource(private val api: String = "https://api.github.com/repos/danything/denpa-tv") {
    private val json = Json { ignoreUnknownKeys = true }

    /** いちばん新しい版の候補 (試し版も含めて新しい順に何本か)。並びは作った順なので、版で選び直す */
    fun releases(): List<GitHubRelease> {
        val url = URI("$api/releases?per_page=$RELEASES")
        val res = Http.request(url, headers = GITHUB_HEADERS)
        if (!res.ok) throw IOException("${res.code} $url")
        return json.decodeFromString(ListSerializer(GitHubRelease.serializer()), res.text())
    }

    /**
     * APK を `dir` に取ってきて、SHA256SUMS と照らす。合わなければ (無ければ) 消して `UpdateRejected`。
     * `progress` は 0..100 (大きさが分からなければ呼ばない)
     */
    fun download(update: Update, dir: File, progress: (Int) -> Unit): File {
        // 先にハッシュを取る (無いリリースのために大きな APK を落とさない)
        val sumsUrl = update.sumsUrl ?: throw UpdateRejected("リリースに SHA256SUMS が無いので入れません")
        val sums = Http.request(URI(sumsUrl), headers = DOWNLOAD_HEADERS)
        if (!sums.ok) throw IOException("SHA256SUMS を取れません (${sums.code})")
        val expected = parseSha256Sums(sums.text())[update.apkName]
            ?: throw UpdateRejected("SHA256SUMS に ${update.apkName} が無いので入れません")

        dir.mkdirs()
        val file = File(dir, update.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URI(update.apkUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            DOWNLOAD_HEADERS.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("APK を取れません ($code)")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != last) progress(percent).also { last = percent }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (actual != expected) {
            file.delete()
            throw UpdateRejected("APK のハッシュが合わないので入れません (もう一度押すと取り直します)")
        }
        return file
    }

    private companion object {
        const val RELEASES = 10
        val GITHUB_HEADERS = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "denpa-tv")
        val DOWNLOAD_HEADERS = mapOf("User-Agent" to "denpa-tv")
    }
}
