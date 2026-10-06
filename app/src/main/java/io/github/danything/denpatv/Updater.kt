package io.github.danything.denpatv

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.danything.denpatv.data.ApkCache
import io.github.danything.denpatv.data.HashMismatch
import io.github.danything.denpatv.data.InstallRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.danything.denpatv.data.lenientJson
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * 「不明なアプリのインストール」の許可の画面を開いた。**戻れば、押さなくても続けて入れる** (`resume`)。
     * 許可が見えなくても入れてみる (テレビによっては許可しても見えない。本当に無ければ OS が尋ねるか断る。`installStep`)。
     * 許可の画面を開けなかった (`opened` が false) ときは、戻っても勝手に始めない。どちらでも、押せば (30 分のうちは) そのまま入れてみる
     */
    data class NeedsPermission(
        val update: Update,
        val message: String,
        val file: File? = null,
        val opened: Boolean = true,
    ) : UpdateState
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
    is UpdateState.NeedsPermission -> message
    is UpdateState.Failed -> message
    else -> null
}

/** 許可の画面へ送ったあとに開き直したとき、覚えておいた頼み (`InstallRequest`) をどうするか */
enum class Resume {
    /** 頼まれた版を入れられる (許可が見えなくても続けて入れてみる) */
    Start,
    /**
     * まだ分からない (確かめられなかった・裏で取れなかった)。頼みは残し、期限で切れる。
     * 取ってきている・入れている (開き直してから押された・設定で確かめている) ときは、そちらが入れる
     * (開き直したときの裏の取り込みは確かめの中で取り終えるので、ここでは取ってきている途中にならない)
     */
    Wait,
    /** 古い頼み・ほかの版になった (もっと新しい版が出た)。忘れる */
    Forget,
}

/**
 * 開き直して確かめ終えたときの `state` で、頼み `request` をどうするか。**確かめられなかったときに頼みを捨てない**
 * (許可の画面にいる間に閉じられ、開き直したときにたまたま届かなくても、次に開いたときに続けられるように)
 */
fun resumePlan(state: UpdateState, request: InstallRequest, now: Long): Resume {
    if (!request.fresh(now)) return Resume.Forget
    val update = when (state) {
        is UpdateState.Ready -> state.update
        is UpdateState.Available -> state.update
        else -> return Resume.Wait
    }
    return if (update.version == request.version) Resume.Start else Resume.Forget
}

/** 押した (許可の画面から戻った) ときに、どう進めるか */
enum class InstallStep {
    /** PackageInstaller のセッションで入れる (許可が要れば、OS が自分で尋ねる) */
    Install,
    /** 「不明なアプリのインストール」の許可の画面を開く */
    AskPermission,
}

/**
 * 許可が見えるか (`allowed` = `canRequestPackageInstalls()`) と、この版でもう許可の画面へ送ったか (`asked`) から決める。
 * **許可が見えなくても、一度送ったあとはセッションで入れてみる**: テレビによっては許可しても false のまま (denpa-tv#32 の
 * BRAVIA。設定に denpa が2つ並ぶ)、許可の画面と行き来するだけになる。本当に許可されていなければ、OS が確認の画面で尋ねるか断る
 */
fun installStep(allowed: Boolean, asked: InstallRequest?, version: String, now: Long): InstallStep = when {
    allowed -> InstallStep.Install
    asked != null && asked.version == version && asked.fresh(now) -> InstallStep.Install
    else -> InstallStep.AskPermission
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

    private val source = UpdateSource(BuildConfig.UPDATE_API)
    private val cache = ApkCache(File(app.cacheDir, "update"))
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    private val started = AtomicBoolean(false)

    /**
     * 開いたとき (プロセスごとに1回。プロセスが落ちて画面が作り直されたときも)。手元で焼いた版では見ない。
     * 許可の画面へ送ったあとに開き直したのなら、続けて入れる (許可が見えなくても入れてみる) (`resumeRequest`)
     */
    fun checkOnStart() {
        if (dev || !started.compareAndSet(false, true)) return
        app.scope.launch {
            val (at, saved) = app.settings.lastUpdateCheck()
            val now = System.currentTimeMillis()
            if (now - at in 0 until CHECK_INTERVAL_MS) {
                // 確かめたばかり。そのとき見つけた版があれば (まだ上げていなければ)、取ってある APK を使う (無ければ取ってくる)
                val update = saved?.let { runCatching { lenientJson.decodeFromString(Update.serializer(), it) }.getOrNull() }
                    ?.takeIf { isNewer(it) }
                prepare(update, fresh = false, manual = false)
            } else {
                try {
                    prepare(fetch(), fresh = true, manual = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "新しい版を確かめられませんでした", e)
                }
            }
            resumeRequest()
        }
    }

    /**
     * 画面に戻った (MainActivity の onResume)。許可の画面から戻ったら**もう一度押さなくても続けて入れる**
     * (denpa-tv#32。前は「もう一度押してください」と出したまま待っていた)。許可が見えなくても入れてみる (`installStep`)
     */
    fun resume() {
        val state = _state.value as? UpdateState.NeedsPermission ?: return
        // 許可の画面を開けなかった: ほかのアプリ・ホームから戻っただけなので、勝手に始めない
        if (!state.opened) return
        // 許可が見えない・送ってから長い (ホームへ出て、ずっと後に開いた): 許可の画面を勝手に開き直さず、押すのを待つ
        if (installStep(allowedToInstall(), asked, state.update.version, System.currentTimeMillis()) != InstallStep.Install) {
            Log.i(TAG, "許可の画面から戻りましたが、送ってから時間がたったので押すのを待ちます")
            return
        }
        Log.i(TAG, "許可の画面から戻りました")
        start(state.update, state.file?.takeIf { it.exists() })
    }

    /**
     * 許可の画面へ送ったあと、アプリが閉じられて開き直した (テレビによっては許可の画面にいる間・許可したときに閉じられる)。
     * 頼まれた版がまだ新しく、頼んでから間もなければ、続けて入れる。許可が見えなくても入れてみる (本当に無ければ OS が尋ねる。`installStep`)
     */
    private suspend fun resumeRequest() {
        val request = app.settings.installRequest() ?: return
        // 開き直す前に許可の画面へ送っていた。押したときにまた送らないよう覚えておく (`installStep`)
        if (asked == null) asked = request
        val state = _state.value
        val plan = resumePlan(state, request, System.currentTimeMillis())
        Log.i(TAG, "開き直しました。v${request.version} を頼まれていました: $plan (${permissionReport()})")
        when (plan) {
            // 許可が見えなくても入れてみる (頼まれた版で間もないので、`start` の `installStep` は Install になる)
            Resume.Start -> when (state) {
                is UpdateState.Ready -> start(state.update, state.file.takeIf { it.exists() })
                is UpdateState.Available -> start(state.update, null)
                else -> Unit
            }
            Resume.Wait -> Unit
            // 確かめている間に押されて新しい頼みを覚えていたら、それは消さない
            Resume.Forget -> if (asked == request) remember(null)
        }
    }

    /** 許可の画面へ送った版を覚える・忘れる。書いた順に効くよう1本ずつ書く (続けて書くと後のほうが先に効くことがある) */
    private fun remember(request: InstallRequest?) {
        asked = request
        app.scope.launch(requestWriter) { app.settings.setInstallRequest(request) }
    }

    /** 書いておいた頼みだけ消す (`asked` は残す) */
    private fun forgetSaved() {
        app.scope.launch(requestWriter) { app.settings.setInstallRequest(null) }
    }

    /** 許可の画面へ送った版 (書いたもの・開き直したときに読んだもの)。押したときにすぐ見る */
    @Volatile private var asked: InstallRequest? = null

    private val requestWriter = Dispatchers.IO.limitedParallelism(1)

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
            is UpdateState.NeedsPermission -> start(state.update, state.file?.takeIf { it.exists() })
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
        // 許可して戻ったら続けて入れる (`resume`)。アプリが閉じられても開き直したときに続けられるよう、先に覚えておく。
        // 一度送ったあとは、許可が見えなくても入れてみる (`installStep`。denpa-tv#32)
        val allowed = allowedToInstall()
        val step = installStep(allowed, asked, update.version, System.currentTimeMillis())
        Log.i(TAG, "${update.label} を入れます: $step (${permissionReport()}、APK ${if (downloaded == null) "なし" else "あり"})")
        if (step == InstallStep.AskPermission) {
            remember(InstallRequest(update.version, System.currentTimeMillis()))
            val (opened, message) = askPermission()
            // 開けなければ戻っても開き直しても勝手に始めない (押すのを待つ)。押したときに入れてみるよう asked だけ残す
            if (!opened) forgetSaved()
            _state.value = UpdateState.NeedsPermission(update, message, downloaded, opened)
            return
        }
        // 入れはじめたので、開き直したときに勝手に始めないよう覚えた頼みは消す。ただ許可が見えないまま入れるときは、
        // このプロセスの中では覚えておく (確認の画面を閉じて押し直しても、許可の画面へ戻さない)
        if (allowed) remember(null) else forgetSaved()
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

    /**
     * 許可まわりをログに書くための1行 (denpa-tv#32: 許可したのに canRequestPackageInstalls が false のテレビがある)。
     * `appop` は REQUEST_INSTALL_PACKAGES の mode (0 許可・1 無視・2 断る・3 既定)、`制限` はユーザーの制限
     */
    private fun permissionReport(): String = buildString {
        append("canRequestPackageInstalls=").append(allowedToInstall())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mode = runCatching {
                val ops = app.getSystemService(AppOpsManager::class.java)
                val uid = Process.myUid()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(OPSTR_REQUEST_INSTALL_PACKAGES, uid, app.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(OPSTR_REQUEST_INSTALL_PACKAGES, uid, app.packageName)
                }
            }
            append(" appop=").append(mode.getOrElse { it.javaClass.simpleName })
        }
        val users = app.getSystemService(UserManager::class.java)
        if (users?.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) == true) append(" 制限=DISALLOW_INSTALL_UNKNOWN_SOURCES")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            users?.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY) == true
        ) {
            append(" 制限=DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY")
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

    /** 許可の画面を開いて、開けたかと出す1行を返す (許可して戻れば続けて入れる) */
    private fun askPermission(): Pair<Boolean, String> {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        val opened = open(intent) || open(Intent(Settings.ACTION_SETTINGS))
        Log.i(TAG, "許可の画面を開きました: ${if (opened) "開けた" else "開けない"}")
        // 頭の1行に収まる長さで。開けなければ、どこで許可するかを足す
        // 開けなければ戻っても勝手に始めない (`resume`) ので、押してもらう
        val then = if (opened) "して戻ると、続けて入れます" else "してから押すと入れます"
        return opened to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (if (opened) "" else "テレビの設定で ") + "「不明なアプリのインストール」を許可$then"
        } else {
            (if (opened) "" else "テレビの設定の「セキュリティ」で ") + "「提供元不明のアプリ」を許可$then"
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
            Log.i(TAG, "セッション $id に ${file.length()} バイト書きます")
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
            Log.i(TAG, "セッション $id を渡しました")
            waitForStatus(update, file, id)
        } catch (e: Exception) {
            Log.w(TAG, "入れられませんでした (${permissionReport()})", e)
            abandon(installer, id)
            if (session == id) session = NO_SESSION
            _state.value = if (e is SecurityException && !allowedToInstall()) {
                // セッションを作れない・渡せない: 許可が無いせい。次に押すと許可の画面を開き直す (頼みを忘れて AskPermission に)
                remember(null)
                UpdateState.Failed(update, "${update.label}: 許可が効いていません。押すと許可の画面を開きます ($TWO_ENTRIES)", file)
            } else {
                UpdateState.Failed(update, "${update.label} を入れられませんでした (${e.message ?: e.javaClass.simpleName})", file)
            }
        }
    }

    /**
     * 渡したセッションの結果 (確認の画面を出す・入った・断られた) が OS から来ないまま「入れています…」で止まらないよう、
     * しばらく待って来なければ、押してやり直せる1行にする (セッションは捨てない。後から入ればアプリが閉じる)
     */
    private fun waitForStatus(update: Update, file: File, id: Int) {
        app.scope.launch {
            delay(STATUS_TIMEOUT_MS)
            val now = _state.value
            // その間に結果が来た・押し直した (ほかの状態・ほかのセッションになった) ら何もしない
            if (now is UpdateState.Installing && !now.confirming && session == id &&
                _state.compareAndSet(now, UpdateState.Failed(update, "${update.label}: テレビから返事がありません (押すともう一度)", file))
            ) {
                Log.w(TAG, "セッション $id の結果が ${STATUS_TIMEOUT_MS / 1000} 秒来ません")
            }
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
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "セッション $session の結果: $status $detail (${permissionReport()})")
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
                    Log.i(TAG, "確認の画面を出しました: ${confirm.action}")
                    _state.value = installing.copy(confirming = true)
                } else {
                    failed("${installing.update.label} の確認の画面を出せませんでした")
                }
            }
            // 入ると、このアプリは OS に閉じられる (ここにはまず来ない)
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            // 許可が見えないときにやめたのは、OS の「このアプリからは入れられません」かもしれない。ただ確認の画面を閉じても同じ
            // (User rejected permissions) で見分けられないので、許可の画面へは戻さず (押すとまた OS が尋ねる)、2つ並ぶことだけ添える
            PackageInstaller.STATUS_FAILURE_ABORTED -> failed(
                "${installing.update.label} を入れるのをやめました (押すともう一度" + (if (allowedToInstall()) ")" else "。$TWO_ENTRIES)"),
            )
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                failed("署名が違うので上書きできません (docs/install.md の「署名について」)")
            PackageInstaller.STATUS_FAILURE_STORAGE -> failed("空きが足りないので入れられません")
            else -> {
                Log.w(TAG, "入れられませんでした: $status $detail")
                failed("${installing.update.label} を入れられませんでした" + (detail?.let { " ($it)" } ?: ""))
            }
        }
    }

    private companion object {
        const val TAG = "DenpaUpdate"
        const val NO_SESSION = -1
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val STATUS_TIMEOUT_MS = 30_000L
        const val ACTION_STATUS = "io.github.danything.denpatv.UPDATE_STATUS"
        /** テレビの設定に同じアプリが2つ並ぶことがあり (denpa-tv#32)、片方だけ許可しても効かないことがある */
        const val TWO_ENTRIES = "設定に denpa が2つ並んでいたら両方を許可"
        /** AppOpsManager の OPSTR_REQUEST_INSTALL_PACKAGES (SDK には出ていない名前) */
        const val OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"
    }
}
