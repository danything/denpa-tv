package io.github.danything.denpatv

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.danything.denpatv.data.Update
import io.github.danything.denpatv.data.UpdateRejected
import io.github.danything.denpatv.data.UpdateSource
import io.github.danything.denpatv.data.Version
import io.github.danything.denpatv.data.isDevBuild
import io.github.danything.denpatv.data.selectUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/** アップデートのいま。録画の一覧の頭の1行と設定の画面が出す */
sealed interface UpdateState {
    data object Idle : UpdateState
    /** 設定で「確かめる」を押した */
    data object Checking : UpdateState
    /** 新しい版は無い (`latest` は見えたいちばん新しい版。リリースが無ければ null) */
    data class UpToDate(val latest: String?) : UpdateState
    /** 手元で焼いた版 (0.0.0-dev)。リリースと署名が違うので上げない */
    data class DevBuild(val latest: String?) : UpdateState
    data class CheckFailed(val message: String) : UpdateState
    data class Available(val update: Update) : UpdateState
    data class Downloading(val update: Update, val percent: Int) : UpdateState
    /** 確かめた APK を入れている。`confirming` はテレビに確認の画面を出したところ */
    data class Installing(val update: Update, val file: File, val confirming: Boolean = false) : UpdateState
    /** 取れない・合わない・入らない。押すとやり直す (取れた APK があればそれを入れ直す) */
    data class Failed(val update: Update, val message: String, val file: File? = null) : UpdateState
}

/** 押せる1行に出す文。出さないときは null */
fun UpdateState.notice(): String? = when (this) {
    is UpdateState.Available -> "${update.label} があります"
    is UpdateState.Downloading -> "${update.label} を取ってきています $percent%"
    is UpdateState.Installing ->
        if (confirming) "${update.label}: 出てきた確認の画面で入れてください" else "${update.label} を入れています…"
    is UpdateState.Failed -> message
    else -> null
}

/**
 * アプリの中から新しい版に上げる (README の「アップデート」)。**設定は増やさない**: 開いたとき (12 時間に1回まで) に
 * GitHub のリリースを見て、新しければ録画の一覧の頭に1行出す。押すと取ってきて SHA256SUMS と照らし、
 * PackageInstaller のセッションで入れる (リリースは同じ鍵で署名しているので上書きで入る)。
 * 開いたときの確かめは、届かなくても黙っている (ログだけ)。設定の「確かめる」は出す
 */
class Updater(private val app: DenpaApp) {
    val current: String = BuildConfig.VERSION_NAME
    val dev: Boolean = isDevBuild(current)

    private val source = UpdateSource()
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(app.cacheDir, "update")
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    /** 開いたとき。手元で焼いた版では見ない */
    fun checkOnStart() {
        if (dev) return
        app.scope.launch {
            // 前に取ってきた APK (入れ終えた・やめた) は捨てる
            if (_state.value == UpdateState.Idle) dir.deleteRecursively()
            val (at, saved) = app.settings.lastUpdateCheck()
            val now = System.currentTimeMillis()
            if (now - at in 0 until CHECK_INTERVAL_MS) {
                // 確かめたばかり。そのとき見つけた版があれば (まだ上げていなければ) 知らせだけ出す
                val update = saved?.let { runCatching { json.decodeFromString(Update.serializer(), it) }.getOrNull() }
                if (update != null && isNewer(update)) offer(update)
                return@launch
            }
            try {
                val update = fetch()
                if (update != null) offer(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "新しい版を確かめられませんでした", e)
            }
        }
    }

    /** 設定の「アップデートを確かめる」。取ってきている・入れている間は何もしない */
    fun checkNow() {
        val busy = _state.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing }
        if (busy) return
        _state.value = UpdateState.Checking
        app.scope.launch {
            _state.value = try {
                if (dev) {
                    UpdateState.DevBuild(selectUpdate(source.releases(), Version(0, 0, 0))?.label)
                } else {
                    fetch()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate(latest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "新しい版を確かめられませんでした", e)
                UpdateState.CheckFailed("確かめられませんでした (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /** 1行を押した: 取ってきて入れる。取ってきている間は何もしない */
    fun act() {
        when (val state = _state.value) {
            is UpdateState.Available -> start(state.update, null)
            is UpdateState.Failed -> start(state.update, state.file?.takeIf { it.exists() })
            // 確認の画面を戻るで閉じると、OS から何も返らないことがある。押せばもう一度出す
            is UpdateState.Installing -> start(state.update, state.file.takeIf { it.exists() })
            else -> Unit
        }
    }

    private fun start(update: Update, downloaded: File?) {
        // 先に「不明なアプリのインストール」を確かめる (取ってきてから断られると無駄になる)
        if (!allowedToInstall()) {
            _state.value = UpdateState.Failed(update, askPermission(), downloaded)
            return
        }
        // 続けて押されても2本取らないよう、先に「取ってきている」にする
        if (downloaded == null) _state.value = UpdateState.Downloading(update, 0)
        app.scope.launch {
            val file = downloaded ?: try {
                dir.deleteRecursively()
                source.download(update, dir) { _state.value = UpdateState.Downloading(update, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateRejected) {
                _state.value = UpdateState.Failed(update, e.message ?: "入れません")
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "APK を取れませんでした", e)
                _state.value = UpdateState.Failed(update, "${update.label} を取れませんでした (押すとやり直します)")
                return@launch
            }
            install(update, file)
        }
    }

    private suspend fun fetch(): Update? {
        val releases = source.releases()
        latest = selectUpdate(releases, Version(0, 0, 0))?.label
        val update = selectUpdate(releases, Version.parse(current) ?: return null)
        app.settings.setUpdateCheck(System.currentTimeMillis(), update?.let { json.encodeToString(Update.serializer(), it) })
        return update
    }

    /** 最後に見えたいちばん新しい版 (「最新です」に添える) */
    @Volatile private var latest: String? = null

    private fun isNewer(update: Update): Boolean {
        val mine = Version.parse(current) ?: return false
        return (Version.parse(update.version) ?: return false) > mine
    }

    /** 知らせを出す。もう取ってきている・入れているときは触らない */
    private fun offer(update: Update) {
        val now = _state.value
        if (now is UpdateState.Idle || now is UpdateState.UpToDate || now is UpdateState.Available) {
            _state.value = UpdateState.Available(update)
        }
    }

    private fun allowedToInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.packageManager.canRequestPackageInstalls()
        } else {
            // Android 7.x は端末全体の「提供元不明のアプリ」
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(app.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    /** 許可の画面を開いて、出す1行を返す */
    private fun askPermission(): String {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        val opened = open(intent) || open(Intent(Settings.ACTION_SETTINGS))
        // 頭の1行に収まる長さで。開けなければ、どこで許可するかを足す
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (if (opened) "" else "テレビの設定で ") + "「不明なアプリのインストール」を許可してから、もう一度押してください"
        } else {
            (if (opened) "" else "テレビの設定の「セキュリティ」で ") + "「提供元不明のアプリ」を許可してから、もう一度押してください"
        }
    }

    private fun open(intent: Intent): Boolean = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** PackageInstaller のセッションで入れる。結果は `onStatus` に返る */
    private fun install(update: Update, file: File) {
        _state.value = UpdateState.Installing(update, file)
        try {
            registerReceiver()
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                // 前にここから入れた (= このアプリが入れた元) なら、確認なしで上げられる。そうでなければ OS が確認を出す
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // OS が結果を書き足すので MUTABLE。宛先はこのアプリに限る
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(app, id, Intent(ACTION_STATUS).setPackage(app.packageName), flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "入れられませんでした", e)
            _state.value = UpdateState.Failed(update, "${update.label} を入れられませんでした (${e.message ?: e.javaClass.simpleName})", file)
        }
    }

    private var registered = false

    private fun registerReceiver() {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onStatus(intent)
        }
        // ほかのアプリから偽の結果 (確認の画面と称した Intent) を送られないよう、外には開かない。
        // ContextCompat は Android 12 以前でも、このアプリだけが送れる権限で守って登録する
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onStatus(intent: Intent) {
        val installing = _state.value as? UpdateState.Installing ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val failed = { message: String -> _state.value = UpdateState.Failed(installing.update, message, installing.file) }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null && open(confirm)) {
                    _state.value = installing.copy(confirming = true)
                } else {
                    failed("${installing.update.label} の確認の画面を出せませんでした")
                }
            }
            // 入ると、このアプリは OS に閉じられる (ここにはまず来ない)
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> failed("${installing.update.label} を入れるのをやめました (押すともう一度)")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                failed("署名が違うので上書きできません (docs/install.md の「署名について」)")
            PackageInstaller.STATUS_FAILURE_STORAGE -> failed("空きが足りないので入れられません")
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "入れられませんでした: $status $detail")
                failed("${installing.update.label} を入れられませんでした" + (detail?.let { " ($it)" } ?: ""))
            }
        }
    }

    private companion object {
        const val TAG = "DenpaUpdate"
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val ACTION_STATUS = "io.github.danything.denpatv.UPDATE_STATUS"
    }
}
