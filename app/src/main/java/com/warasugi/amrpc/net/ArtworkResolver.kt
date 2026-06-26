package com.warasugi.amrpc.net

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * iTunes Search API で曲のアートワーク URL を解決する。
 *
 * MediaMetadata のアートワークは Bitmap で Discord に渡せないため、
 * title/artist を検索キーに mzstatic の URL を取得する。返ってくる
 * artworkUrl100 は ".../100x100bb.jpg" 形式で、"100x100bb" を希望サイズに
 * 置換すると高解像度版が取れる(長年使われている標準手法)。
 *
 * 注意: この呼び出しだけは Twingate ではなく公衆インターネットに出る
 * (title/artist が Apple に送られる)。設定で無効化できる。
 */
class ArtworkResolver(
    private val country: String,
    private val size: Int,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // 値が空文字 = 「検索したが見つからなかった」ネガティブキャッシュ。
    private val cache = LruCache<String, String>(256)

    suspend fun resolve(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val key = "$artist|$title".lowercase()
        cache.get(key)?.let { return@withContext it.ifEmpty { null } }

        val result = runCatching { lookup(title, artist) }.getOrNull()
        cache.put(key, result ?: "")
        result
    }

    private fun lookup(title: String, artist: String): String? {
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val url = "https://itunes.apple.com/search" +
            "?media=music&entity=song&limit=1&country=$country&term=$term"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AppleMusicRpc/1.0 (personal use)")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.getJSONObject(0)
            val art100 = first.optString("artworkUrl100", "")
            if (art100.isEmpty()) return null
            return upscale(art100)
        }
    }

    private fun upscale(url100: String): String {
        // ".../100x100bb.jpg" -> ".../{size}x{size}bb.jpg"
        return if (url100.contains("100x100bb")) {
            url100.replace("100x100bb", "${size}x${size}bb")
        } else {
            url100 // 想定外フォーマットならそのまま返す
        }
    }
}
