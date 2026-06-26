package com.warasugi.amrpc.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.warasugi.amrpc.App
import com.warasugi.amrpc.data.BridgeStatus
import com.warasugi.amrpc.data.ConnectionState
import com.warasugi.amrpc.data.PlaybackRepository
import com.warasugi.amrpc.data.PlaybackSnapshot
import com.warasugi.amrpc.data.Settings
import com.warasugi.amrpc.net.ArtworkResolver
import com.warasugi.amrpc.net.BridgeClient
import com.warasugi.amrpc.net.PresenceBuilder
import com.warasugi.amrpc.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * 常駐ブリッジ。再生がある間だけ起動し、WS で presence を送る。
 *
 * 設計上の肝(PROTOCOL / receiver / mapper の挙動に対応):
 *  - PC 側 TTL は既定 30s。曲が長くても無音だと失効するので keepaliveSeconds
 *    (既定 20s)ごとに「現在位置入りで」再送して TTL を更新する。
 *  - 再送ごとに position_ms を現在値にするため PC 側 start=now-position がずれない。
 *  - 曲変更/再生・一時停止/シーク時は即送信。位置だけの細かい更新は送らない。
 *  - 再生が止まったら grace 後に clear を送って自分を止める(TTL 待ちで残らない)。
 *  - 再接続直後(ready)は PC 側でソースが失効しているため現在状態を必ず再送。
 *  - アートワークは非同期解決。まずテキストだけ送り、URL が取れたら貼り直す。
 */
class RpcBridgeService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: Settings
    private lateinit var client: BridgeClient
    private lateinit var artwork: ArtworkResolver

    private val sendMutex = Mutex()
    @Volatile private var lastSent: PlaybackSnapshot? = null
    @Volatile private var seq = 0
    @Volatile private var artworkUrl: String? = null
    @Volatile private var artworkKey: String? = null
    private var graceJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = Settings(this)
        artwork = ArtworkResolver(country = settings.country, size = settings.artworkSize)
        startForegroundNotification("再生を待機中")

        client = BridgeClient(
            scope = scope,
            onReady = { resendCurrent() },
            onState = { state, detail -> onConnState(state, detail) },
        )

        if (settings.isConfigured) {
            client.start(settings.wsUrl, settings.token)
        } else {
            BridgeStatus.set { it.copy(connection = ConnectionState.ERROR, detail = "未設定: ホスト/トークンを入力してください") }
        }

        scope.launch { collectPlayback() }
        scope.launch { keepaliveLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) reload()
        return START_STICKY
    }

    /** UI から設定変更後に呼ばれる。新しいホスト/トークン/解像度で接続し直す。 */
    private fun reload() {
        settings = Settings(this)
        artwork = ArtworkResolver(country = settings.country, size = settings.artworkSize)
        artworkKey = null
        artworkUrl = null
        client.stop()
        if (settings.isConfigured) {
            client.start(settings.wsUrl, settings.token)
        } else {
            BridgeStatus.set {
                it.copy(connection = ConnectionState.ERROR, detail = "未設定: ホスト/トークンを入力してください")
            }
        }
    }

    override fun onDestroy() {
        client.stop()
        scope.cancel()
        BridgeStatus.reset()
        isRunning = false
        super.onDestroy()
    }

    // ---- 再生状態の収集 ----------------------------------------------------

    private suspend fun collectPlayback() {
        PlaybackRepository.snapshot.collect { snap ->
            if (snap == null) {
                scheduleStop()
            } else {
                cancelStop()
                handleSnapshot(snap)
            }
        }
    }

    private fun handleSnapshot(snap: PlaybackSnapshot) {
        maybeResolveArtwork(snap)
        if (shouldSend(snap)) {
            scope.launch { pushPresence(snap, force = false) }
        }
    }

    private fun shouldSend(snap: PlaybackSnapshot): Boolean {
        val last = lastSent ?: return true
        if (!snap.isSameTrack(last)) return true
        if (snap.isPlaying != last.isPlaying) return true
        // シーク検知: 直前送信を現在まで外挿した位置と実位置の差。
        val drift = abs(snap.livePositionMs() - last.livePositionMs())
        return drift > 3_000
    }

    private fun resendCurrent() {
        val cur = PlaybackRepository.snapshot.value ?: return
        scope.launch { pushPresence(cur, force = true) }
    }

    // ---- 送信 --------------------------------------------------------------

    private suspend fun pushPresence(snap: PlaybackSnapshot, force: Boolean) {
        sendMutex.withLock {
            val key = trackKey(snap)
            val art = if (settings.artworkEnabled && key == artworkKey) artworkUrl else null
            val json = PresenceBuilder.presence(
                snapshot = snap,
                sourceId = settings.sourceId,
                sourceName = settings.sourceName,
                seq = ++seq,
                artworkUrl = art,
            )
            val ok = client.send(json)
            if (ok) lastSent = snap
            val np = "${snap.artist} - ${snap.title}".trim(' ', '-')
            BridgeStatus.set {
                it.copy(
                    lastNowPlaying = np,
                    lastArtworkResolved = art != null,
                    detail = if (ok) it.detail else "未送信(未接続)。再接続待ち…",
                )
            }
            updateNotification(if (snap.isPlaying) "♪ $np" else "⏸ $np")
        }
    }

    private fun maybeResolveArtwork(snap: PlaybackSnapshot) {
        if (!settings.artworkEnabled) {
            artworkUrl = null
            artworkKey = null
            return
        }
        val key = trackKey(snap)
        if (key == artworkKey && artworkUrl != null) return
        artworkKey = key
        artworkUrl = null
        scope.launch {
            val url = artwork.resolve(snap.title, snap.artist)
            if (artworkKey == key) {
                artworkUrl = url
                val cur = PlaybackRepository.snapshot.value
                if (cur != null && trackKey(cur) == key) pushPresence(cur, force = true)
            }
        }
    }

    private fun trackKey(s: PlaybackSnapshot): String =
        "${s.artist}|${s.album ?: ""}|${s.title}".lowercase()

    // ---- 停止判断 ----------------------------------------------------------

    private fun scheduleStop() {
        if (graceJob?.isActive == true) return
        graceJob = scope.launch {
            delay(settings.graceSeconds * 1000L)
            if (PlaybackRepository.snapshot.value == null) {
                client.send(PresenceBuilder.clear(settings.sourceId))
                lastSent = null
                delay(200) // clear フレームを送り切る猶予
                stopSelf()
            }
        }
    }

    private fun cancelStop() {
        graceJob?.cancel()
        graceJob = null
    }

    // ---- keepalive ---------------------------------------------------------

    private suspend fun keepaliveLoop() {
        while (scope.isActive) {
            delay(settings.keepaliveSeconds * 1000L)
            val cur = PlaybackRepository.snapshot.value ?: continue
            pushPresence(cur, force = true) // TTL 更新(一時停止中も維持)
        }
    }

    // ---- UI 状態・通知 -----------------------------------------------------

    private fun onConnState(state: ConnectionState, detail: String) {
        BridgeStatus.set { it.copy(connection = state, detail = detail) }
        val short = when (state) {
            ConnectionState.READY -> "接続済み"
            ConnectionState.CONNECTING -> "接続中…"
            ConnectionState.ERROR -> "未接続"
            ConnectionState.IDLE -> "停止中"
        }
        updateNotification(short)
    }

    private fun startForegroundNotification(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("Apple Music → Discord")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val ACTION_RELOAD = "com.warasugi.amrpc.action.RELOAD"

        /** 二重起動を避けるためのプロセス内フラグ。NLS から参照する。 */
        @Volatile var isRunning = false
            private set
        private const val NOTIF_ID = 1001
    }
}
