package com.warasugi.amrpc.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NotificationListenerService(検出側)と RpcBridgeService(送信側)は同一プロセスで
 * 動くため、Binder/IPC を使わずプロセス内シングルトンで状態を受け渡す。
 */
object PlaybackRepository {
    private val _snapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    val snapshot: StateFlow<PlaybackSnapshot?> = _snapshot

    fun update(value: PlaybackSnapshot?) {
        _snapshot.value = value
    }
}

/** WS の接続状態。UI にそのまま表示する。 */
enum class ConnectionState { IDLE, CONNECTING, READY, ERROR }

data class BridgeUiState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val detail: String = "停止中",
    val lastNowPlaying: String? = null,
    val lastArtworkResolved: Boolean = false,
)

/** UI 表示用の状態ホルダ。サービスが書き、画面が読む。 */
object BridgeStatus {
    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state

    fun set(transform: (BridgeUiState) -> BridgeUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = BridgeUiState()
    }
}
