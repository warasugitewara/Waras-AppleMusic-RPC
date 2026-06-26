package com.warasugi.amrpc.data

import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * 監視中のメディアセッションから取り出した「今の再生状態」のスナップショット。
 * アートワークは Bitmap でしか取れず Discord に渡せないため、ここでは持たず
 * title/artist/album を使って iTunes Search API で URL を解決する。
 */
data class PlaybackSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,           // 不明なら <= 0
    val positionMs: Long,           // positionUpdateTimeMs 時点の位置
    val positionUpdateTimeMs: Long, // SystemClock.elapsedRealtime 基準
    val playbackSpeed: Float,
    val isPlaying: Boolean,
) {
    /** 現在時刻における推定再生位置(ms)。再生中はドリフトを加味する。 */
    fun livePositionMs(): Long {
        val raw = if (isPlaying) {
            val elapsed = SystemClock.elapsedRealtime() - positionUpdateTimeMs
            positionMs + (elapsed * playbackSpeed).toLong()
        } else {
            positionMs
        }
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return raw.coerceIn(0L, upper)
    }

    /** 曲が変わったか(再生位置や一時停止状態は無視)。 */
    fun isSameTrack(other: PlaybackSnapshot?): Boolean =
        other != null &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            packageName == other.packageName

    companion object {
        fun isPlayingState(state: Int): Boolean =
            state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING
    }
}
