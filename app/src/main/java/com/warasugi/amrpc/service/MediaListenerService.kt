package com.warasugi.amrpc.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
import com.warasugi.amrpc.data.BridgeStatus
import com.warasugi.amrpc.data.ConnectionState
import com.warasugi.amrpc.data.PlaybackRepository
import com.warasugi.amrpc.data.PlaybackSnapshot
import com.warasugi.amrpc.data.Settings

/**
 * メディアセッション監視の本体。getActiveSessions() は有効な
 * NotificationListenerService の ComponentName を要求するため、検出ロジックは
 * この NLS 内に置く(通知アクセス許可がアンカー)。
 *
 * 対象パッケージ(既定: Apple Music)のコントローラを選び、コールバックで
 * 曲・再生状態の変化を拾って PlaybackRepository に流す。再生があれば
 * 常駐サービス(RpcBridgeService)を起動する。送信と停止判断は向こうの責務。
 */
class MediaListenerService : NotificationListenerService() {

    private lateinit var settings: Settings
    private lateinit var msm: MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var trackedController: MediaController? = null
    private var trackedToken: android.media.session.MediaSession.Token? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            refresh(controllers ?: emptyList())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = emitFromTracked()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emitFromTracked()
        override fun onSessionDestroyed() {
            detach()
            reevaluate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            reevaluate()
            Log.i(TAG, "listener connected; session monitoring active")
        } catch (e: SecurityException) {
            Log.e(TAG, "通知アクセス未許可: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        detach()
        PlaybackRepository.update(null)
        super.onListenerDisconnected()
    }

    private fun reevaluate() {
        val component = ComponentName(this, MediaListenerService::class.java)
        val controllers = runCatching { msm.getActiveSessions(component) }.getOrNull() ?: emptyList()
        refresh(controllers)
    }

    private fun refresh(controllers: List<MediaController>) {
        if (!settings.enabled) {
            detach()
            PlaybackRepository.update(null)
            return
        }

        val targets = settings.targetPackages
        val candidates = controllers.filter { it.packageName in targets }
        val chosen = candidates.firstOrNull { c ->
            c.playbackState?.let { PlaybackSnapshot.isPlayingState(it.state) } == true
        } ?: candidates.firstOrNull()

        if (chosen == null) {
            detach()
            PlaybackRepository.update(null)
            return
        }

        if (chosen.sessionToken != trackedToken) {
            detach()
            trackedController = chosen
            trackedToken = chosen.sessionToken
            chosen.registerCallback(controllerCallback, mainHandler)
        }
        emitFromTracked()
    }

    private fun emitFromTracked() {
        val controller = trackedController ?: run {
            PlaybackRepository.update(null)
            return
        }
        val snapshot = buildSnapshot(controller)
        PlaybackRepository.update(snapshot)
        if (snapshot != null) ensureBridgeRunning()
    }

    private fun buildSnapshot(controller: MediaController): PlaybackSnapshot? {
        val md = controller.metadata ?: return null
        val ps = controller.playbackState

        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        if (title.isBlank()) return null

        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val duration = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)

        return PlaybackSnapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            positionMs = ps?.position ?: 0L,
            positionUpdateTimeMs = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            playbackSpeed = ps?.playbackSpeed ?: 1f,
            isPlaying = ps != null && PlaybackSnapshot.isPlayingState(ps.state),
        )
    }

    private fun detach() {
        trackedController?.runCatching { unregisterCallback(controllerCallback) }
        trackedController = null
        trackedToken = null
    }

    private fun ensureBridgeRunning() {
        if (RpcBridgeService.isRunning) return
        val intent = Intent(this, RpcBridgeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ では電池最適化が未除外だと background からの FGS 起動が
            // ForegroundServiceStartNotAllowedException で弾かれる。除外が exemption。
            Log.w(TAG, "FGS 起動失敗(電池最適化の除外が必要かも): ${e.message}")
            BridgeStatus.set {
                it.copy(
                    connection = ConnectionState.ERROR,
                    detail = "自動起動に失敗。アプリを開き『電池最適化から除外』を許可してください。",
                )
            }
        }
    }

    companion object {
        private const val TAG = "MediaListener"
    }
}
