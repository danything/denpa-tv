package io.doany.denpatv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 古いアプリ (パッケージ名 `io.github.danything.denpatv`、v0.30.0 まで) から移る (docs/install.md の「古いアプリから移る」)。
 * パッケージ名が違うと Android からは別のアプリなので、設定 (繋ぐ先とアクセストークン) は引き継がれない。
 * 古いアプリの最後のバージョン (v0.30.0) は、同じ鍵で署名したこのアプリにだけ設定を渡す ContentProvider
 * (`content://io.github.danything.denpatv.settings`) を持つので、**初めて開いたときにそこから読む** ([OldAppMigration])。
 * 読み終えたら、古いアプリを削除する1行を出す ([OldApp])
 */
const val OLD_PACKAGE = "io.github.danything.denpatv"

/** 古いアプリが設定を渡す ContentProvider。列は key・type (string・boolean・long・float)・value (文字列にしたもの) */
private const val OLD_SETTINGS = "content://$OLD_PACKAGE.settings"

/** 古いアプリから受け取った設定の1行 */
data class SettingRow(val key: String, val type: String, val value: String)

/** 古いアプリを確かめ終えた印 (受け取った・古いアプリが無かった)。印があれば、もう読みに行かない */
val oldAppCheckedKey = booleanPreferencesKey("old_app_checked")

/** 繋ぐ先 (`Settings` と同じキー)。もう繋いでいれば、古いアプリの設定で上書きしない */
private val serverKey = stringPreferencesKey("server")

/**
 * 受け取った設定を `prefs` に書く。アップデートの確認の記録 (`update` で始まるキー。古いアプリの版のこと) と、
 * 読めない型・値の行は捨てる
 */
fun applyOldSettings(prefs: MutablePreferences, rows: List<SettingRow>) {
    for (row in rows) {
        if (row.key.startsWith("update") || row.key == oldAppCheckedKey.name) continue
        when (row.type) {
            "string" -> prefs[stringPreferencesKey(row.key)] = row.value
            "boolean" -> row.value.toBooleanStrictOrNull()?.let { prefs[booleanPreferencesKey(row.key)] = it }
            "long" -> row.value.toLongOrNull()?.let { prefs[longPreferencesKey(row.key)] = it }
            "float" -> row.value.toFloatOrNull()?.let { prefs[floatPreferencesKey(row.key)] = it }
        }
    }
}

/** 古いアプリの設定を読んだ結果 */
sealed interface OldSettings {
    /** 古いアプリが入っていない */
    data object Missing : OldSettings

    /** 入っているが読めない (v0.30.0 より前で ContentProvider が無い・署名が違って断られた) */
    data object Unavailable : OldSettings

    data class Rows(val rows: List<SettingRow>) : OldSettings
}

/**
 * まだ繋いでいなければ、DataStore を初めて読む前に古いアプリの設定を受け取る (DataStore の DataMigration)。
 * 受け取った・古いアプリが無いときは印 ([oldAppCheckedKey]) を書き、もう読みに行かない。
 * 古いアプリが v0.30.0 より前で読めないときは印を書かず、次に開いたときにまた読む (そのあいだに古いアプリを v0.30.0 に上げれば受け取れる)
 */
class OldAppMigration(private val read: () -> OldSettings) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[oldAppCheckedKey] == null && currentData[serverKey] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = read()
        return when (old) {
            OldSettings.Unavailable -> currentData
            OldSettings.Missing -> currentData.toMutablePreferences().apply { this[oldAppCheckedKey] = true }
            is OldSettings.Rows -> currentData.toMutablePreferences().apply {
                applyOldSettings(this, old.rows)
                this[oldAppCheckedKey] = true
            }
        }
    }

    override suspend fun cleanUp() = Unit
}

/** 古いアプリの ContentProvider から設定を読む */
fun readOldSettings(context: Context): OldSettings {
    if (!OldApp.installed(context)) return OldSettings.Missing
    return try {
        context.contentResolver.query(OLD_SETTINGS.toUri(), null, null, null, null)?.use { cursor ->
            val key = cursor.getColumnIndexOrThrow("key")
            val type = cursor.getColumnIndexOrThrow("type")
            val value = cursor.getColumnIndexOrThrow("value")
            val rows = buildList {
                while (cursor.moveToNext()) add(SettingRow(cursor.getString(key), cursor.getString(type), cursor.getString(value)))
            }
            Log.i(TAG, "古いアプリから設定を ${rows.size} 行受け取りました")
            OldSettings.Rows(rows)
        } ?: OldSettings.Unavailable.also { Log.i(TAG, "古いアプリが設定を渡しません (v0.30.0 より前)") }
    } catch (e: Exception) {
        // 署名が違う (debug とリリース) と SecurityException
        Log.w(TAG, "古いアプリの設定を読めません", e)
        OldSettings.Unavailable
    }
}

private const val TAG = "DenpaOldApp"

/**
 * 古いアプリが入っているか。入っていれば、録画の一覧の頭と設定に「古いアプリを削除」を出す。
 * 削除の確認画面から戻ったとき (MainActivity の onResume) に確かめ直す
 */
class OldApp(private val context: Context) {
    private val _installed = MutableStateFlow(installed(context))
    val installed: StateFlow<Boolean> get() = _installed

    fun refresh() {
        _installed.value = installed(context)
    }

    /** 削除の確認画面を開く (REQUEST_DELETE_PACKAGES) */
    fun uninstall() {
        try {
            context.startActivity(Intent(Intent.ACTION_DELETE, "package:$OLD_PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "削除の確認画面を開けません", e)
        }
    }

    companion object {
        /** `<queries>` で古いアプリが見えるようにしてある */
        fun installed(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(OLD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
