package io.github.danything.denpatv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.runBlocking

/**
 * 新しいアプリ (パッケージ名 `io.doany.denpatv`) へ移る。**このパッケージ名 (`io.github.danything.denpatv`) で出す最後のバージョンだけが持つ。**
 * パッケージ名が違うと Android からは別のアプリなので、上書きで更新できず、設定 (繋ぐ先とアクセストークン) も引き継がれない。
 * そこで、v1.0.0 からのリリース (新しいアプリ) を見つけたら、上書きの代わりに新しいアプリを入れて開き (`Updater`)、
 * 新しいアプリが初めて開いたときに、ここの設定を [SettingsExport] から読ませる (docs/install.md の「古いアプリから移る」)
 */
const val NEW_PACKAGE = "io.doany.denpatv"

/** 新しいアプリの最初のバージョン。これから先のリリースは、全部新しいアプリ */
val NEW_APP_SINCE = Version(1, 0, 0)

/** 上書きの更新ではなく、新しいアプリへ移るリリース */
fun Update.movesToNewApp(): Boolean = Version.parse(version)?.let { it >= NEW_APP_SINCE } == true

/** 新しいアプリが入っているか (`<queries>` で見えるようにしてある) */
fun newAppInstalled(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(NEW_PACKAGE, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/** 新しいアプリを開く Intent。テレビのランチャーの入口を先に探す。入っていなければ null */
fun newAppLaunchIntent(context: Context): Intent? {
    val pm = context.packageManager
    return (pm.getLeanbackLaunchIntentForPackage(NEW_PACKAGE) ?: pm.getLaunchIntentForPackage(NEW_PACKAGE))
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** 渡す設定の1行。`type` は string・boolean・long・float、`value` は文字列にしたもの */
data class SettingRow(val key: String, val type: String, val value: String)

/** 渡さないキー (`Settings` のアップデートの確認の記録) */
val UPDATE_KEYS = setOf("update_checked_at", "update", "update_install_requested", "update_install_requested_at")

/** [SettingsExport] が返す列 */
val SETTING_COLUMNS = arrayOf("key", "type", "value")

/**
 * 新しいアプリに渡す設定。繋ぐ先・アクセストークン・ライブの画質・字幕などを全部渡し、
 * アップデートの確認の記録 ([UPDATE_KEYS]。このアプリのバージョンと取ってきた APK のこと) だけは渡さない
 */
fun exportRows(prefs: Preferences): List<SettingRow> = prefs.asMap().mapNotNull { (key, value) ->
    if (key.name in UPDATE_KEYS) return@mapNotNull null
    val type = when (value) {
        is String -> "string"
        is Boolean -> "boolean"
        is Long -> "long"
        is Float -> "float"
        else -> return@mapNotNull null
    }
    SettingRow(key.name, type, value.toString())
}.sortedBy { it.key }

/**
 * 新しいアプリに設定を渡す ContentProvider (`content://io.github.danything.denpatv.settings`)。読むだけ。
 * **同じ鍵で署名した新しいアプリだけに返す**: 呼んだ uid が新しいアプリのもので、署名がこのアプリと一致するときだけ。
 * ほかのアプリには SecurityException を返す (アクセストークンが入っている)。
 * signature の権限 (`<permission android:protectionLevel="signature">`) にしないのは、権限を定めるアプリより先に
 * 使うアプリが入ると、Android のバージョンによっては後から許可されないため。呼ばれたときに署名を比べれば、入れた順に関わらない
 */
class SettingsExport : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val context = context ?: throw IllegalStateException("context がありません")
        val uid = Binder.getCallingUid()
        if (!trusted(context.packageManager, uid)) throw SecurityException("uid $uid には設定を渡しません")
        val rows = runBlocking { Settings(context).exportRows() }
        return MatrixCursor(SETTING_COLUMNS).apply { rows.forEach { addRow(arrayOf(it.key, it.type, it.value)) } }
    }

    private fun trusted(pm: PackageManager, uid: Int): Boolean =
        NEW_PACKAGE in pm.getPackagesForUid(uid).orEmpty() && pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
