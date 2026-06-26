package com.warasugi.amrpc.net

import com.warasugi.amrpc.data.PlaybackSnapshot
import org.json.JSONObject

/**
 * docs/PROTOCOL.md(通信契約 v1)に従って WS メッセージを組み立てる。
 *
 * music スキーマは title/artist が必須(各 <=128 文字)。進捗バーは
 * position_ms/duration_ms を渡せば PC 側が start/end を算出するので、
 * クライアントは start/end を計算しない。再送のたびに「その瞬間の
 * 現在位置」を入れることで PC 側の start = now - position がずれない。
 */
object PresenceBuilder {

    private const val MAX_LEN = 128

    fun presence(snapshot: PlaybackSnapshot, sourceId: String, sourceName: String, seq: Int, artworkUrl: String?): String {
        val data = JSONObject().apply {
            put("title", trunc(snapshot.title.ifBlank { "Unknown" }))
            put("artist", trunc(snapshot.artist))
            snapshot.album?.takeIf { it.isNotBlank() }?.let { put("album", trunc(it)) }
            artworkUrl?.takeIf { it.isNotBlank() }?.let { put("artwork_url", it) }
            if (snapshot.durationMs > 0) {
                put("duration_ms", snapshot.durationMs)
                put("position_ms", snapshot.livePositionMs())
            }
            put("paused", !snapshot.isPlaying)
            put("app_name", "Apple Music")
        }

        return JSONObject().apply {
            put("op", "presence")
            put("kind", "music")
            put("source_id", sourceId)
            put("source_name", sourceName)
            put("seq", seq)
            put("data", data)
        }.toString()
    }

    fun clear(sourceId: String): String =
        JSONObject().apply {
            put("op", "clear")
            put("source_id", sourceId)
        }.toString()

    fun ping(): String =
        JSONObject().apply { put("op", "ping") }.toString()

    private fun trunc(s: String): String =
        if (s.length <= MAX_LEN) s else s.substring(0, MAX_LEN)
}
