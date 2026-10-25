package io.github.danything.denpatv

import io.github.danything.denpatv.data.Licenses
import io.github.danything.denpatv.data.Licenses.licenseName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 設定の「ライセンス」の一覧 (assets の licenses/licenses.json)。依存のグループとの突き合わせは
 * Gradle の `checkLicenses` (app/build.gradle.kts) がする。ここでは一覧そのものの形と、全文のファイルがあるかを見る
 */
class LicensesTest {
    /** 単体テストはモジュールの頭 (app/) で走る */
    private val dir = File("src/main/assets/licenses")
    private val manifest = Licenses.parse(File(dir, Licenses.MANIFEST).readText())

    /** 全文のファイル。`LICENSE` だけはビルドのときにリポジトリの頭から写す (copyAppLicense) */
    private fun file(name: String) = if (name == "LICENSE") File("../LICENSE") else File(dir, name)

    @Test
    fun 読み分ける() {
        val parsed = Licenses.parse(
            """
            {
              "licenses": { "MIT": "MIT License" },
              "components": [
                { "name": "a", "license": "MIT", "file": "MIT-a.txt", "groups": ["com.example.*"], "extra": 1 },
                { "name": "b", "license": "BSD-3-Clause", "file": "b.txt", "notice": "NOTICE-b.txt" }
              ]
            }
            """.trimIndent(),
        )
        val (a, b) = parsed.components
        assertEquals("MIT License", parsed.licenseName(a))
        assertEquals(listOf("com.example.*"), a.groups)
        assertNull(a.notice)
        // 名前の無い ID はそのまま出す
        assertEquals("BSD-3-Clause", parsed.licenseName(b))
        assertEquals("NOTICE-b.txt", b.notice)
        assertEquals(emptyList<String>(), b.groups)
    }

    @Test
    fun いちばん上はこのアプリでソースコードの置き場がある() {
        val app = manifest.components.first()
        assertEquals("denpa-tv", app.name)
        assertEquals("AGPL-3.0", app.license)
        assertEquals("https://github.com/danything/denpa-tv", app.source)
        assertTrue(file(app.file).readText().contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun どれも名前の付いたライセンスと全文がある() {
        for (component in manifest.components) {
            assertTrue("${component.name}: ${component.license} の名前が licenses に無い", component.license in manifest.licenses)
            for (name in listOfNotNull(component.file, component.notice)) {
                assertTrue("${component.name}: $name が無い", file(name).isFile)
            }
        }
        // licenses/ に置いた全文は、どれかが使っている (書き残しを置かない)
        val used = manifest.components.flatMap { listOfNotNull(it.file, it.notice) }.toSet() + Licenses.MANIFEST
        val unused = dir.list().orEmpty().filterNot { it in used }
        assertEquals(emptyList<String>(), unused)
    }

    @Test
    fun 同梱しているソースと字も載っている() {
        val byLicense = manifest.components.associate { it.name to it.license }
        assertEquals("MIT", byLicense["QR Code generator (Project Nayuki)"])
        assertTrue(byLicense.entries.any { it.key.startsWith("Denpa Font") && it.value == "OFL-1.1" })
        // MIT と OFL の全文には、元の著作権表示が入っている
        assertTrue(File(dir, "MIT-qrcodegen.txt").readText().contains("Copyright (c) Project Nayuki."))
        assertTrue(File(dir, "OFL-1.1-denpa-font.txt").readText().contains("Copyright 2022 The BIZ UDGothic Project Authors"))
    }
}
