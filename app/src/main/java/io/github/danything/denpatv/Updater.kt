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
import io.github.danything.denpatv.data.ApkCache
import io.github.danything.denpatv.data.HashMismatch
import io.github.danything.denpatv.data.Prefetch
import io.github.danything.denpatv.data.Update
import io.github.danything.denpatv.data.UpdateRejected
import io.github.danything.denpatv.data.UpdateSource
import io.github.danything.denpatv.data.Version
import io.github.danything.denpatv.data.isDevBuild
import io.github.danything.denpatv.data.offerAfterFailure
import io.github.danything.denpatv.data.prefetchPlan
import io.github.danything.denpatv.data.selectUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.danything.denpatv.data.lenientJson
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
    /** 新しい版がある。裏で取れなかった (取らない) ときの知らせで、押すと取ってきて入れる */
    data class Available(val update: Update) : UpdateState
    /** 新しい版を見つけて、裏で取ってきて照らしている。**録画の一覧の頭には出さない** (設定にだけ進みを出す) */
    data class Preparing(val update: Update, val percent: Int) : UpdateState
    /** 照らし終えた APK が手元にある。押すとすぐ入れる */
    data class Ready(val update: Update, val file: File) : UpdateState
    /** 押してから取ってきている (裏で取れなかったとき) */
    data class Downloading(val update: Update, val percent: Int) : UpdateState
    /** 確かめた APK を入れている。`confirming` はテレビに確認の画面を出したところ */
    data class Installing(val update: Update, val file: File, val confirming: Boolean = false) : UpdateState
    /** 取れない・合わない・入らない。押すとやり直す (取れた APK があればそれを入れ直す) */
    data class Failed(val update: Update, val message: String, val file: File? = null) : UpdateState
}

/** 押せる1行に出す文。出さないときは null (裏で取ってきている間も出さない) */
fun UpdateState.notice(): String? = when (this) {
    is UpdateState.Available -> "${update.label} があります"
    is UpdateState.Ready -> "${update.label} を入れられます (押すと入れます)"
    is UpdateState.Downloading -> "${update.label} を取ってきています $percent%"
    is UpdateState.Installing ->
        if (confirming) "${update.label}: 出てきた確認の画面で入れてください" else "${update.label} を入れています…"
    is UpdateState.Failed -> message
    else -> null
}

/**
 * アプリの中から新しい版に上げる (README の「アップデート」)。**設定は増やさない**: 開いたとき (12 時間に1回まで) に
 * GitHub のリリースを見て、新しければ**黙って裏で取ってきて SHA256SUMS と照らし**、照らし終えたら録画の一覧の頭に1行出す。
 * 押すと PackageInstaller のセッションで入れる (リリースは同じ鍵で署名しているので上書きで入る)。
 * 照らした APK は cache に版ごとに置き、開き直しても (ハッシュを計り直して合えば) 取り直さない。
 * 開いたときの確かめと裏の取り込みは、届かなくても黙っている (ログだけ。次に確かめたときに取り直す)。
 * 何度も取れなければ前のとおり「新しい版があります」を出し、押したら取ってくる。設定の「確かめる」は出す
 */
class Updater(private val app: DenpaApp) {
    val current: String = BuildConfig.VERSION_NAME
    val dev: Boolean = isDevBuild(current)

    private val source = UpdateSource()
    private val cache = ApkCache(File(app.cacheDir, "update"))
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    /** 開いたとき。手元で焼いた版では見ない */
    fun checkOnStart() {
        if (dev) return
        app.scope.launch {
            val (at, saved) = app.settings.lastUpdateCheck()
            val now = System.currentTimeMillis()
            if (now - at in 0 until CHECK_INTERVAL_MS) {
                // 確かめたばかり。そのとき見つけた版があれば (まだ上げていなければ)、取ってある APK を使う (無ければ取ってくる)
                val update = saved?.let { runCatching { lenientJson.decodeFromString(Update.serializer(), it) }.getOrNull() }
                    ?.takeIf { isNewer(it) }
                prepare(update, fresh = false, manual = false)
                return@launch
            }
            try {
                prepare(fetch(), fresh = true, manual = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "新しい版を確かめられませんでした", e)
            }
        }
    }

    /** 設定の「アップデートを確かめる」。取ってきている・入れている間は何もしない */
    fun checkNow() {
        val busy = _state.value.let {
            it is UpdateState.Checking || it is UpdateState.Preparing || it is UpdateState.Downloading || it is UpdateState.Installing
        }
        if (busy) return
        _state.value = UpdateState.Checking
        app.scope.launch {
            try {
                if (dev) {
                    _state.value = UpdateState.DevBuild(selectUpdate(source.releases(), Version(0, 0, 0))?.label)
                } else {
                    val update = fetch()
                    // 待つ間に開いたときの確かめが別の版を取り始めていたら、それを消さない
                    if (update == null) _state.compareAndSet(UpdateState.Checking, UpdateState.UpToDate(latest))
                    prepare(update, fresh = true, manual = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "新しい版を確かめられませんでした", e)
                _state.value = UpdateState.CheckFailed("確かめられませんでした (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /** 1行を押した: 入れる (照らした APK が無ければ取ってきてから)。取ってきている間は何もしない */
    fun act() {
        when (val state = _state.value) {
            is UpdateState.Ready -> start(state.update, state.file.takeIf { it.exists() })
            is UpdateState.Available -> start(state.update, null)
            is UpdateState.Failed -> start(state.update, state.file?.takeIf { it.exists() })
            // 確認の画面を戻るで閉じると、OS から何も返らないことがある。押せばもう一度出す
            // (セッションを書いている最中 = 確認の画面を出す前は何もしない)
            is UpdateState.Installing -> if (state.confirming) start(state.update, state.file.takeIf { it.exists() })
            else -> Unit
        }
    }

    /**
     * 新しい版 `update` を見つけた (null なら無い)。ほかの版の APK を捨て、照らした APK があればそれで「入れられます」に、
     * 無ければ裏で取ってきて照らす。取ってきている・入れている・知らせを押したあとは触らない
     */
    private fun prepare(update: Update?, fresh: Boolean, manual: Boolean) {
        // 入れ終えた・もっと新しい版が出た・新しい版が無い: 要らない APK を捨てる
        if (!settled(_state.value)) return
        cache.prune(update)
        if (update == null) return
        val cached = cache.verified(update)
        when (prefetchPlan(cached != null, update.sumsUrl != null, cache.failures(update), fresh)) {
            Prefetch.Reuse -> claim(UpdateState.Ready(update, cached!!))
            Prefetch.Offer -> claim(UpdateState.Available(update))
            Prefetch.Download -> if (claim(UpdateState.Preparing(update, 0))) prefetch(update, manual)
        }
    }

    /** 知らせを出したり裏で取り始めたりしてよいとき (ほかに何もしていない) */
    private fun settled(state: UpdateState) = state is UpdateState.Idle || state is UpdateState.Checking ||
        state is UpdateState.UpToDate || state is UpdateState.CheckFailed || state is UpdateState.Available || state is UpdateState.Ready

    /** 何もしていなければ `next` にする。開いたときと「確かめる」が重なっても、2本取らない */
    private fun claim(next: UpdateState): Boolean {
        while (true) {
            val now = _state.value
            if (!settled(now)) return false
            if (_state.compareAndSet(now, next)) return true
        }
    }

    /** 裏で取ってきて照らす (app.scope の中で、いまのコルーチンのまま)。失敗はログだけで、次に確かめたときに取り直す */
    private fun prefetch(update: Update, manual: Boolean) {
        val file = try {
            source.download(update, cache.dir(update)) { percent ->
                // 裏で取っている間だけ進みを書く (ほかに移っていたら上書きしない)
                _state.update { if (it is UpdateState.Preparing && it.update == update) UpdateState.Preparing(update, percent) else it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HashMismatch) {
            Log.w(TAG, "${update.label} の APK のハッシュが合いません (消しました。次に確かめたときに取り直します)", e)
            fallBack(update, manual)
            return
        } catch (e: UpdateRejected) {
            // SHA256SUMS に名前が無いなど。取り直しても変わらないので知らせを出す (押すと断る文が出る)
            Log.w(TAG, "${update.label} は入れられません: ${e.message}")
            _state.value = UpdateState.Available(update)
            return
        } catch (e: Exception) {
            Log.w(TAG, "${update.label} を裏で取れませんでした (次に確かめたときに取り直します)", e)
            fallBack(update, manual)
            return
        }
        cache.clearFailures(update)
        _state.value = UpdateState.Ready(update, file)
    }

    private fun fallBack(update: Update, manual: Boolean) {
        val failures = cache.recordFailure(update)
        _state.value = if (offerAfterFailure(failures, manual)) UpdateState.Available(update) else UpdateState.Idle
    }

    private fun start(update: Update, downloaded: File?) {
        // 先に「不明なアプリのインストール」を確かめる (取ってきてから断られると無駄になる)
        if (!allowedToInstall()) {
            _state.value = UpdateState.Failed(update, askPermission(), downloaded)
            return
        }
        // 続けて押されても2本取らないよう、先に「取ってきている」にする
        _state.value = if (downloaded == null) UpdateState.Downloading(update, 0) else UpdateState.Installing(update, downloaded)
        app.scope.launch {
            val file = downloaded ?: try {
                source.download(update, cache.dir(update)) { _state.value = UpdateState.Downloading(update, it) }
                    .also { cache.clearFailures(update) }
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
        app.settings.setUpdateCheck(System.currentTimeMillis(), update?.let { lenientJson.encodeToString(Update.serializer(), it) })
        return update
    }

    /** 最後に見えたいちばん新しい版 (「最新です」に添える) */
    @Volatile private var latest: String? = null

    private fun isNewer(update: Update): Boolean {
        val mine = Version.parse(current) ?: return false
        return (Version.parse(update.version) ?: return false) > mine
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
        val installer = app.packageManager.packageInstaller
        // 前のセッション (確認の画面を閉じた・やめた) は片づける。その結果が後から届いても、いまの id と違うので見ない
        abandon(installer, session)
        var id = NO_SESSION
        try {
            registerReceiver()
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                // 前にここから入れた (= このアプリが入れた元) なら、確認なしで上げられる。そうでなければ OS が確認を出す
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            id = installer.createSession(params)
            session = id
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
            abandon(installer, id)
            if (session == id) session = NO_SESSION
            _state.value = UpdateState.Failed(update, "${update.label} を入れられませんでした (${e.message ?: e.javaClass.simpleName})", file)
        }
    }

    /** いま入れているセッション。結果はこの id のものだけ受ける */
    @Volatile private var session = NO_SESSION

    private fun abandon(installer: PackageInstaller, id: Int) {
        if (id == NO_SESSION) return
        try {
            installer.abandonSession(id)
        } catch (_: Exception) {
            // 終わった・もう無いセッション
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
        // 前に作って捨てたセッションの結果は見ない
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION) != session) return
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
        const val NO_SESSION = -1
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val ACTION_STATUS = "io.github.danything.denpatv.UPDATE_STATUS"
    }
}
