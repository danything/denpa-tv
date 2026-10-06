package io.github.danything.denpatv

import io.github.danything.denpatv.data.ApkCache
import io.github.danything.denpatv.data.GitHubAsset
import io.github.danything.denpatv.data.GitHubRelease
import io.github.danything.denpatv.data.HashMismatch
import io.github.danything.denpatv.data.INSTALL_REQUEST_TTL_MS
import io.github.danything.denpatv.data.InstallRequest
import io.github.danything.denpatv.data.MAX_SILENT_FAILURES
import io.github.danything.denpatv.data.Prefetch
import io.github.danything.denpatv.data.Update
import io.github.danything.denpatv.data.UpdateRejected
import io.github.danything.denpatv.data.UpdateSource
import io.github.danything.denpatv.data.Version
import io.github.danything.denpatv.data.isDevBuild
import io.github.danything.denpatv.data.offerAfterFailure
import io.github.danything.denpatv.data.parseSha256Sums
import io.github.danything.denpatv.data.prefetchPlan
import io.github.danything.denpatv.data.selectUpdate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** アプリの中のアップデート: 版の比べ方・SHA256SUMS の読み方・リリースの選び方 (scripts/install.sh と同じ) */
class UpdateTest {
    private val github = FakeDenpa()

    @After fun stop() = github.close()

    private fun v(text: String) = Version.parse(text)!!

    @Test
    fun 版を比べる() {
        assertTrue(v("v0.4.0") > v("0.3.0"))
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertEquals(0, v("v0.3.0").compareTo(v("0.3.0")))
        // 試し版は同じ数字の正式版より前、前の版よりは後
        assertTrue(v("0.4.0-rc.1") < v("0.4.0"))
        assertTrue(v("0.4.0-rc.1") > v("0.3.0"))
        // semver の例の並び
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        assertEquals(ordered, ordered.shuffled().sortedBy { v(it) })
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("0.4"))
    }

    @Test
    fun 手元で焼いた版は上げない() {
        assertTrue(isDevBuild("0.0.0-dev"))
        assertTrue(isDevBuild("なにか"))
        assertFalse(isDevBuild("0.3.0"))
        assertFalse(isDevBuild("0.4.0-rc.1"))
    }

    @Test
    fun SHA256SUMS_を読む() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = parseSha256Sums("$a  denpa-tv-0.4.0.apk\n$b *denpa-tv-0.4.0-debug.apk\n\nごみ\n")
        assertEquals(mapOf("denpa-tv-0.4.0.apk" to a, "denpa-tv-0.4.0-debug.apk" to "b".repeat(64)), sums)
        assertEquals(emptyMap<String, String>(), parseSha256Sums("abc  x.apk"))
    }

    private fun release(tag: String, vararg assets: String, prerelease: Boolean = false, draft: Boolean = false) =
        GitHubRelease(tag, draft, prerelease, assets.map { GitHubAsset(it, "https://example.invalid/$tag/$it") })

    @Test
    fun いちばん新しい版を選ぶ_試し版も含む() {
        val releases = listOf(
            release("v0.5.0", "denpa-tv-0.5.0.apk", "SHA256SUMS", draft = true),
            release("v0.4.1-rc.1", "denpa-tv-0.4.1-rc.1.apk", "SHA256SUMS", prerelease = true),
            release("v0.4.0", "denpa-tv-0.4.0.apk", "SHA256SUMS"),
            release("v0.3.0", "denpa-tv-0.3.0.apk", "SHA256SUMS"),
        )
        val update = selectUpdate(releases, v("0.3.0"))!!
        assertEquals("0.4.1-rc.1", update.version)
        assertEquals("v0.4.1-rc.1", update.label)
        assertEquals("denpa-tv-0.4.1-rc.1.apk", update.apkName)
        assertEquals("https://example.invalid/v0.4.1-rc.1/SHA256SUMS", update.sumsUrl)
        // 並びが作った順でなくても版で選ぶ
        assertEquals("0.4.1-rc.1", selectUpdate(releases.reversed(), v("0.3.0"))!!.version)
        // 新しいものが無ければ null
        assertNull(selectUpdate(releases, v("0.4.1-rc.1")))
        assertNull(selectUpdate(releases, v("0.4.1")))
        assertNull(selectUpdate(emptyList(), v("0.3.0")))
    }

    @Test
    fun debug_の署名の_APK_しかないリリースは選ばない() {
        val releases = listOf(
            release("v0.5.0", "denpa-tv-0.5.0-debug.apk", "SHA256SUMS"),
            release("v0.4.0", "denpa-tv-0.4.0.apk"),
        )
        val update = selectUpdate(releases, v("0.3.0"))!!
        assertEquals("0.4.0", update.version)
        // SHA256SUMS の無いリリースも選ぶ (取るときに断って、そう出す)
        assertNull(update.sumsUrl)
    }

    /** GitHub の答えの形 (知らない鍵は無視する) */
    @Test
    fun リリースを引く() {
        github.enqueue(
            """[{"url":"https://api.github.com/repos/danything/denpa-tv/releases/1","tag_name":"v0.4.0","name":"v0.4.0",
               "draft":false,"prerelease":false,"assets":[
                 {"name":"denpa-tv-0.4.0.apk","size":123,"browser_download_url":"https://github.com/danything/denpa-tv/releases/download/v0.4.0/denpa-tv-0.4.0.apk"},
                 {"name":"SHA256SUMS","size":84,"browser_download_url":"https://github.com/danything/denpa-tv/releases/download/v0.4.0/SHA256SUMS"}]}]""",
        )
        val releases = UpdateSource(github.url("/repos/danything/denpa-tv").trimEnd('/')).releases()
        assertEquals("/repos/danything/denpa-tv/releases?per_page=10", github.requests.take().target)
        val update = selectUpdate(releases, v("0.3.0"))!!
        assertEquals(123L, update.size)
        assertEquals("https://github.com/danything/denpa-tv/releases/download/v0.4.0/denpa-tv-0.4.0.apk", update.apkUrl)
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun update(sums: Boolean = true) = Update(
        "0.4.0",
        "denpa-tv-0.4.0.apk",
        github.url("/apk"),
        if (sums) github.url("/SHA256SUMS") else null,
    )

    @Test
    fun ハッシュが合えば取ってくる() {
        val apk = "PK fake apk"
        github.enqueue("${sha256(apk)}  denpa-tv-0.4.0.apk\n")
        github.enqueue(apk)
        val dir = Files.createTempDirectory("update").toFile()
        val progress = mutableListOf<Int>()
        val file = UpdateSource(github.url()).download(update(), dir) { progress += it }
        assertEquals(apk, file.readText())
        assertEquals(100, progress.last())
        // 照らした行を隣に置き、途中のファイルは残さない
        assertEquals("${sha256(apk)}  denpa-tv-0.4.0.apk\n", dir.resolve("denpa-tv-0.4.0.apk.sha256").readText())
        assertFalse(dir.resolve("denpa-tv-0.4.0.apk.part").exists())
        dir.deleteRecursively()
    }

    @Test
    fun ハッシュが合わない_無いときは断って消す() {
        val dir = Files.createTempDirectory("update").toFile()
        val source = UpdateSource(github.url())

        github.enqueue("${sha256("別もの")}  denpa-tv-0.4.0.apk\n")
        github.enqueue("PK fake apk")
        assertThrows(HashMismatch::class.java) { source.download(update(), dir) {} }
        assertFalse(dir.resolve("denpa-tv-0.4.0.apk").exists())
        assertFalse(dir.resolve("denpa-tv-0.4.0.apk.part").exists())
        assertFalse(dir.resolve("denpa-tv-0.4.0.apk.sha256").exists())

        // SHA256SUMS に名前が無い
        github.enqueue("${sha256("x")}  denpa-tv-0.4.0-debug.apk\n")
        assertThrows(UpdateRejected::class.java) { source.download(update(), dir) {} }

        // リリースに SHA256SUMS が無い (取りにも行かない)
        assertThrows(UpdateRejected::class.java) { source.download(update(sums = false), dir) {} }
        github.requests.clear()
        assertTrue(github.requests.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun 照らした_APK_があれば取り直さない() {
        // 手元にあれば (SHA256SUMS の有無や失敗の数にかかわらず) それを使う
        assertEquals(Prefetch.Reuse, prefetchPlan(cached = true, hasSums = true, failures = 0, fresh = false))
        assertEquals(Prefetch.Reuse, prefetchPlan(cached = true, hasSums = false, failures = 9, fresh = true))
        // 無ければ裏で取る。SHA256SUMS の無いリリースは取らずに知らせだけ (押すと断る)
        assertEquals(Prefetch.Download, prefetchPlan(cached = false, hasSums = true, failures = 0, fresh = false))
        assertEquals(Prefetch.Offer, prefetchPlan(cached = false, hasSums = false, failures = 0, fresh = true))
        // 何度も取れなければ、開くたびには取らず知らせだけ。GitHub を引き直したときはもう一度取る
        assertEquals(Prefetch.Download, prefetchPlan(cached = false, hasSums = true, failures = MAX_SILENT_FAILURES - 1, fresh = false))
        assertEquals(Prefetch.Offer, prefetchPlan(cached = false, hasSums = true, failures = MAX_SILENT_FAILURES, fresh = false))
        assertEquals(Prefetch.Download, prefetchPlan(cached = false, hasSums = true, failures = MAX_SILENT_FAILURES, fresh = true))
    }

    @Test
    fun 裏で取れないときは黙っていて_続けば知らせる() {
        assertFalse(offerAfterFailure(1, manual = false))
        assertFalse(offerAfterFailure(MAX_SILENT_FAILURES - 1, manual = false))
        assertTrue(offerAfterFailure(MAX_SILENT_FAILURES, manual = false))
        // 設定で「確かめる」を押したときは出す
        assertTrue(offerAfterFailure(1, manual = true))
    }

    /** 許可の画面へ送って戻ったとき・開き直したとき (denpa-tv#32) */
    @Test
    fun 許可の画面へ送った版を_続けて入れるか() {
        val at = 1_000_000L
        val request = InstallRequest("0.4.0", at)
        val update = Update("0.4.0", "denpa-tv-0.4.0.apk", "", null)
        assertTrue(request.resumes(update, at))
        assertTrue(request.resumes(update, at + INSTALL_REQUEST_TTL_MS))
        // 頼んでからずっと後 (許可せずに戻って、何日も後に開いた) は勝手に入れ始めない。時計が戻ったときも
        assertFalse(request.resumes(update, at + INSTALL_REQUEST_TTL_MS + 1))
        assertFalse(request.resumes(update, at - 1))
        // その間にもっと新しい版が出た・新しい版が無くなった (入れ終えた) ときは、頼まれた版ではないので続けない
        assertFalse(request.resumes(update.copy(version = "0.4.1"), at))
        assertFalse(request.resumes(null, at))
    }

    @Test
    fun 許可を待つ間の1行() {
        val update = Update("0.4.0", "denpa-tv-0.4.0.apk", "", null)
        val message = "「不明なアプリのインストール」を許可して戻ると、続けて入れます"
        assertEquals(message, UpdateState.NeedsPermission(update, message).notice())
        assertEquals("v0.4.0 があります", UpdateState.Available(update).notice())
    }

    @Test
    fun 取ってある_APK_を照らし直す() {
        val root = Files.createTempDirectory("update").toFile()
        val cache = ApkCache(root)
        val update = update()
        assertNull(cache.verified(update))

        val apk = "PK fake apk"
        github.enqueue("${sha256(apk)}  denpa-tv-0.4.0.apk\n")
        github.enqueue(apk)
        val file = UpdateSource(github.url()).download(update, cache.dir(update)) {}
        assertEquals(file, cache.verified(update))

        // 中身が変わっていたら使わずに消す
        file.writeText("PK changed")
        assertNull(cache.verified(update))
        assertFalse(file.exists())
        assertFalse(cache.dir(update).resolve("denpa-tv-0.4.0.apk.sha256").exists())

        // 照らした行が無い (途中で落ちた) ものも使わない
        file.writeText(apk)
        assertNull(cache.verified(update))
        root.deleteRecursively()
    }

    @Test
    fun ほかの版の_APK_は捨てる() {
        val root = Files.createTempDirectory("update").toFile()
        val cache = ApkCache(root)
        val old = Update("0.3.0", "denpa-tv-0.3.0.apk", "", null)
        val new = update()
        listOf(old, new).forEach { cache.dir(it).mkdirs(); cache.dir(it).resolve(it.apkName).writeText("x") }
        // 前の作り (update/ の直下に APK)
        root.resolve("denpa-tv-0.2.0.apk").writeText("x")

        assertEquals(0, cache.failures(new))
        assertEquals(1, cache.recordFailure(new))
        assertEquals(2, cache.recordFailure(new))
        cache.prune(new)
        assertEquals(listOf("0.4.0"), root.list()!!.toList())
        assertNotNull(cache.dir(new).resolve(new.apkName).takeIf { it.exists() })
        assertEquals(2, cache.failures(new))
        cache.clearFailures(new)
        assertEquals(0, cache.failures(new))

        // 新しい版が無い (入れ終えた) ときは全部
        cache.prune(null)
        assertEquals(emptyList<String>(), root.list()!!.toList())
        root.deleteRecursively()
    }
}
