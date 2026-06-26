package com.warasugi.amrpc.net

import android.util.Log
import com.warasugi.amrpc.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PC ブリッジへの WebSocket 接続(主経路 /ws)。
 *
 * - 認証は WS ハンドシェイクの HTTP ヘッダ Authorization: Bearer <token> で行う
 *   (PROTOCOL.md / receiver.py の _extract_token に対応)。
 * - サーバは接続直後に {"op":"ready"} を返す。これを再送トリガにする。
 * - 認証失敗時はサーバが close(4001) する。
 * - 切断時は指数バックオフで再接続する(再接続のたび PC 側はソースを失効
 *   させるため、ready を受け取ったら現在状態を必ず再送する)。
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onState: (ConnectionState, String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // WS 制御フレームで TCP の死活を検知
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS は読み続ける
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var shouldRun = false
    @Volatile private var url: String = ""
    @Volatile private var token: String = ""
    private var reconnectJob: Job? = null
    private var backoffMs = 1_000L

    val isConnected: Boolean get() = ws != null

    fun start(url: String, token: String) {
        this.url = url
        this.token = token
        shouldRun = true
        backoffMs = 1_000L
        connect()
    }

    fun stop() {
        shouldRun = false
        reconnectJob?.cancel()
        ws?.close(1000, "client stop")
        ws = null
        onState(ConnectionState.IDLE, "停止中")
    }

    /** 接続中なら送信して true。未接続なら false。 */
    fun send(text: String): Boolean = ws?.send(text) ?: false

    private fun connect() {
        if (!shouldRun) return
        onState(ConnectionState.CONNECTING, "接続中… ($url)")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        ws = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(reason: String) {
        ws = null
        if (!shouldRun) return
        onState(ConnectionState.ERROR, reason)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // ハンドシェイク成功。認証可否は ready / close(4001) で確定する。
            Log.d(TAG, "ws open (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val op = runCatching { JSONObject(text).optString("op") }.getOrNull() ?: return
            when (op) {
                "ready" -> {
                    backoffMs = 1_000L
                    onState(ConnectionState.READY, "接続済み")
                    onReady() // 再接続直後でも現在の再生状態を送り直す
                }
                "ack" -> Log.d(TAG, "ack: $text")
                "pong" -> {}
                "error" -> {
                    val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    Log.w(TAG, "server error: $msg")
                    onState(ConnectionState.READY, "接続済み(直近の更新で警告: $msg)")
                }
                else -> Log.d(TAG, "unknown op: $text")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (code == 4001) {
                backoffMs = 30_000L // 認証失敗は再試行を急がない
                scheduleReconnect("認証失敗: トークンが一致しません")
            } else {
                scheduleReconnect("切断されました (code=$code)")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect("接続失敗: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "BridgeClient"
    }
}
