package com.warasugi.amrpc.data

import android.content.Context

/**
 * 設定の保管。依存を増やさないため SharedPreferences を直接使う。
 * 既定値は Waras-discordRPC の config.example.json に合わせてある
 * (port=13520 / source_id="phone-music" / source_name="Phone • Music")。
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("amrpc", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** PC の到達先。Twingate の Resource DNS か IP。例: 100.x.x.x または pc.twingate */
    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HOST, v.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 13520)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /** PC 側 .env の BRIDGE_TOKEN と一致させる。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var sourceId: String
        get() = prefs.getString(KEY_SOURCE_ID, "phone-music") ?: "phone-music"
        set(v) = prefs.edit().putString(KEY_SOURCE_ID, v.ifBlank { "phone-music" }).apply()

    var sourceName: String
        get() = prefs.getString(KEY_SOURCE_NAME, "Phone \u2022 Music") ?: "Phone \u2022 Music"
        set(v) = prefs.edit().putString(KEY_SOURCE_NAME, v).apply()

    /** 検出対象のパッケージ(カンマ区切り)。既定は Android 版 Apple Music。 */
    var targetPackagesRaw: String
        get() = prefs.getString(KEY_TARGET_PKGS, DEFAULT_PKG) ?: DEFAULT_PKG
        set(v) = prefs.edit().putString(KEY_TARGET_PKGS, v).apply()

    val targetPackages: List<String>
        get() = targetPackagesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** iTunes Search API でアートワーク URL を解決するか。OFF なら外部通信なし(画像なし)。 */
    var artworkEnabled: Boolean
        get() = prefs.getBoolean(KEY_ART_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ART_ENABLED, v).apply()

    /** iTunes 検索のストアフロント。日本は "jp"。 */
    var country: String
        get() = prefs.getString(KEY_COUNTRY, "jp") ?: "jp"
        set(v) = prefs.edit().putString(KEY_COUNTRY, v.ifBlank { "jp" }).apply()

    /** アートワークの希望解像度(mzstatic URL の置換に使う)。 */
    var artworkSize: Int
        get() = prefs.getInt(KEY_ART_SIZE, 512)
        set(v) = prefs.edit().putInt(KEY_ART_SIZE, v.coerceIn(100, 1024)).apply()

    /** TTL(PC 既定 30s)を維持するための再送間隔。30 未満に保つこと。 */
    var keepaliveSeconds: Int
        get() = prefs.getInt(KEY_KEEPALIVE, 20)
        set(v) = prefs.edit().putInt(KEY_KEEPALIVE, v.coerceIn(5, 28)).apply()

    /** 再生終了後、clear を送ってサービスを止めるまでの猶予。 */
    var graceSeconds: Int
        get() = prefs.getInt(KEY_GRACE, 45)
        set(v) = prefs.edit().putInt(KEY_GRACE, v.coerceIn(5, 300)).apply()

    val wsUrl: String
        get() = "ws://$host:$port/ws"

    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()

    companion object {
        private const val DEFAULT_PKG = "com.apple.android.music"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SOURCE_ID = "source_id"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val KEY_TARGET_PKGS = "target_pkgs"
        private const val KEY_ART_ENABLED = "art_enabled"
        private const val KEY_COUNTRY = "country"
        private const val KEY_ART_SIZE = "art_size"
        private const val KEY_KEEPALIVE = "keepalive"
        private const val KEY_GRACE = "grace"
    }
}
