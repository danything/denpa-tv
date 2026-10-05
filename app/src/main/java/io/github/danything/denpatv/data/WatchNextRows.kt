package io.github.danything.denpatv.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * 「続きを視聴」の行を OS の TvProvider (`TvContract.WatchNextPrograms`) に書く。**androidx.tvprovider は入れない**
 * — 書くのは十数列の ContentValues だけで、OS の API (Android 8.0 から) で足りる (docs/libraries.md)。
 * Android 7.x と、TvProvider の無い端末 (Google TV / Android TV でないもの) では何もしない。
 * 何を書くかは `WatchNext.kt` の素の関数が決める。書けなくても (断られても) 観るのは止めない
 */
class WatchNextRows(private val context: Context) {
    private val lock = Mutex()

    /** 録画を閉じた。`posterUrl` は denpa のポスターの URL (トークンを付けて取り、ホームに渡せる形で置く) */
    suspend fun stopped(recording: Recording, positionMs: Long, durationMs: Long, finished: Boolean, posterUrl: String?, token: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        guarded {
            val now = System.currentTimeMillis()
            apply(onStopped(recording, positionMs, durationMs, finished, rows(), now)) { posterUrl?.let { savePoster(recording.id, it, token) } }
        }
    }

    /** 録画の一覧 (全部) を読み直した。`fetchedAt` は一覧を取りはじめたとき */
    suspend fun sync(recordings: List<Recording>, fetchedAt: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        guarded { apply(syncWatchNext(recordings, rows(), System.currentTimeMillis(), fetchedAt)) { null } }
    }

    /** 録画を消した */
    suspend fun remove(recordingId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        guarded { apply(rows().filter { it.recordingId == recordingId }.map { WatchNextChange.Delete(it.rowId, it.recordingId) }) { null } }
    }

    private suspend fun guarded(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // TvProvider が無い・断られた (SecurityException / IllegalArgumentException) など
                Log.w(TAG, "続きを視聴に書けません: $e")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun rows(): List<WatchNextRow> {
        val projection = arrayOf(
            WatchNextPrograms._ID,
            WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_BROWSABLE,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        // アプリが読めるのは自分の行だけ (TvProvider がパッケージで絞る)
        val cursor: Cursor = context.contentResolver.query(WatchNextPrograms.CONTENT_URI, projection, null, null, null)
            ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    // 目印が数でない行 (このアプリのものでない形) は触らない
                    val recordingId = c.getString(1)?.toLongOrNull() ?: continue
                    add(WatchNextRow(c.getLong(0), recordingId, c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getLong(5)))
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun apply(changes: List<WatchNextChange>, poster: (WatchNextItem) -> Uri?) {
        val resolver = context.contentResolver
        for (change in changes) {
            when (change) {
                is WatchNextChange.Insert -> resolver.insert(WatchNextPrograms.CONTENT_URI, values(change.item, poster(change.item)))
                is WatchNextChange.Update -> resolver.update(
                    ContentUris.withAppendedId(WatchNextPrograms.CONTENT_URI, change.rowId),
                    ContentValues().apply {
                        put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, change.positionMs.toIntMs())
                        put(WatchNextPrograms.COLUMN_DURATION_MILLIS, change.durationMs.toIntMs())
                        put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, change.engagedAt)
                    },
                    null,
                    null,
                )
                is WatchNextChange.Delete -> {
                    resolver.delete(ContentUris.withAppendedId(WatchNextPrograms.CONTENT_URI, change.rowId), null, null)
                    WatchNextPosters.file(context, change.recordingId).delete()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun values(item: WatchNextItem, poster: Uri?) = ContentValues().apply {
        put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_TV_EPISODE)
        put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        put(WatchNextPrograms.COLUMN_TITLE, item.title)
        put(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, item.description)
        put(WatchNextPrograms.COLUMN_DURATION_MILLIS, item.durationMs.toIntMs())
        put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, item.positionMs.toIntMs())
        put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, item.engagedAt)
        put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.recordingId.toString())
        // このアプリで開く (同じ denpa:// を受けるほかのアプリに渡らないように、パッケージを決めておく)
        val intent = Intent(Intent.ACTION_VIEW, item.link.toUri()).setPackage(context.packageName)
        put(WatchNextPrograms.COLUMN_INTENT_URI, intent.toUri(Intent.URI_INTENT_SCHEME))
        if (poster != null) {
            put(WatchNextPrograms.COLUMN_POSTER_ART_URI, poster.toString())
            put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
        }
    }

    /** denpa のポスターを取って、ホームが読める所 (`WatchNextPosters`) に置く。取れなければ null (絵なしで出す) */
    private fun savePoster(recordingId: Long, url: String, token: String?): Uri? {
        val bitmap = Images.load(url, POSTER_WIDTH, POSTER_HEIGHT, token) ?: return null
        val file = WatchNextPosters.file(context, recordingId)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) return null }
        if (!tmp.renameTo(file)) return null
        return WatchNextPosters.uri(context, recordingId)
    }

    private companion object {
        const val TAG = "WatchNext"
        const val POSTER_WIDTH = 640
        const val POSTER_HEIGHT = 360
    }
}

/** TvProvider の列は int (ミリ秒)。24 日を超える録画は無いが、溢れないように */
private fun Long.toIntMs(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/**
 * 「続きを視聴」のポスターをホームに渡す。**denpa のポスターの URL はそのまま渡せない** — 家の外の denpa は
 * トークン (`Authorization: Bearer`) が要り、ホームはそれを付けられない。家の LAN でも素の HTTP をホームが読むとは限らない。
 * そこでアプリが取って `files/watchnext/<録画の id>.jpg` に置き、`content://<パッケージ>.watchnext/<id>` で読ませる。
 * 読むだけ・数の名前だけ (ほかの所は開けない)。ホームのアプリが読むので exported にしてある。出すのは録画のポスターだけ
 */
class WatchNextPosters : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("読むだけです")
        val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { uri.pathSegments.size == 1 } ?: throw FileNotFoundException(uri.toString())
        val file = file(context ?: throw FileNotFoundException(uri.toString()), id)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun file(context: Context, recordingId: Long) = File(File(context.filesDir, "watchnext"), "$recordingId.jpg")
        fun uri(context: Context, recordingId: Long): Uri = "content://${context.packageName}.watchnext/$recordingId".toUri()
    }
}
