package io.doany.denpatv

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.doany.denpatv.data.OldAppMigration
import io.doany.denpatv.data.OldSettings
import io.doany.denpatv.data.SettingRow
import io.doany.denpatv.data.oldAppCheckedKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 古いアプリ (io.github.danything.denpatv) から設定を受け取る */
class OldAppTest {
    private val server = stringPreferencesKey("server")

    private val rows = listOf(
        SettingRow("server", "string", "http://192.168.1.10:3000/"),
        SettingRow("token", "string", "secret"),
        SettingRow("skip_cm", "boolean", "false"),
        SettingRow("last_service", "long", "3273601024"),
        SettingRow("playback_speed", "float", "1.5"),
        // 古いアプリの版のこと。受け取らない
        SettingRow("update_checked_at", "long", "1"),
        // 読めない型・値は捨てる
        SettingRow("jikkyo", "boolean", "yes"),
        SettingRow("names", "strings", "a"),
    )

    @Test
    fun 受け取った設定を書いて_印を付ける() = runTest {
        val migrated = OldAppMigration { OldSettings.Rows(rows) }.migrate(emptyPreferences())
        assertEquals("http://192.168.1.10:3000/", migrated[server])
        assertEquals("secret", migrated[stringPreferencesKey("token")])
        assertEquals(false, migrated[booleanPreferencesKey("skip_cm")])
        assertEquals(3273601024L, migrated[longPreferencesKey("last_service")])
        assertEquals(1.5f, migrated[floatPreferencesKey("playback_speed")])
        assertNull(migrated[longPreferencesKey("update_checked_at")])
        assertNull(migrated[booleanPreferencesKey("jikkyo")])
        assertEquals(true, migrated[oldAppCheckedKey])
        // 印があれば、もう読みに行かない
        assertFalse(OldAppMigration { error("読まない") }.shouldMigrate(migrated))
    }

    @Test
    fun 古いアプリが無ければ印だけ付ける() = runTest {
        val migrated = OldAppMigration { OldSettings.Missing }.migrate(emptyPreferences())
        assertNull(migrated[server])
        assertEquals(true, migrated[oldAppCheckedKey])
    }

    @Test
    fun 読めなければ印を付けず_次に開いたときにまた読む() = runTest {
        val migration = OldAppMigration { OldSettings.Unavailable }
        val migrated = migration.migrate(emptyPreferences())
        assertNull(migrated[oldAppCheckedKey])
        assertTrue(migration.shouldMigrate(migrated))
    }

    @Test
    fun もう繋いでいれば読まない() = runTest {
        assertFalse(OldAppMigration { error("読まない") }.shouldMigrate(preferencesOf(server to "http://192.168.1.20:3000/")))
    }
}
