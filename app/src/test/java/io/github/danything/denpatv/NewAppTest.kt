package io.github.danything.denpatv

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danything.denpatv.data.SettingRow
import io.github.danything.denpatv.data.Update
import io.github.danything.denpatv.data.exportRows
import io.github.danything.denpatv.data.movesToNewApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新しいアプリ (io.doany.denpatv) へ移る: どのリリースで移るか・何を渡すか */
class NewAppTest {
    private fun update(version: String) = Update(version, "denpa-tv-$version.apk", "https://example.com/denpa-tv-$version.apk", null)

    @Test
    fun v1から新しいアプリへ移る() {
        assertFalse(update("0.30.0").movesToNewApp())
        assertFalse(update("0.99.0").movesToNewApp())
        // 試し版の 1.0.0-rc.1 は 1.0.0 より前なので、まだ上書きの更新
        assertFalse(update("1.0.0-rc.1").movesToNewApp())
        assertTrue(update("1.0.0").movesToNewApp())
        assertTrue(update("1.2.3").movesToNewApp())
    }

    @Test
    fun 設定を渡す_アップデートの記録は渡さない() {
        val prefs = preferencesOf(
            stringPreferencesKey("server") to "http://192.168.1.10:3000/",
            stringPreferencesKey("token") to "secret",
            booleanPreferencesKey("skip_cm") to false,
            longPreferencesKey("last_service") to 3273601024L,
            floatPreferencesKey("playback_speed") to 1.5f,
            longPreferencesKey("update_checked_at") to 1L,
            stringPreferencesKey("update") to "{}",
            stringPreferencesKey("update_install_requested") to "0.30.0",
        )
        assertEquals(
            listOf(
                SettingRow("last_service", "long", "3273601024"),
                SettingRow("playback_speed", "float", "1.5"),
                SettingRow("server", "string", "http://192.168.1.10:3000/"),
                SettingRow("skip_cm", "boolean", "false"),
                SettingRow("token", "string", "secret"),
            ),
            exportRows(prefs),
        )
    }
}
