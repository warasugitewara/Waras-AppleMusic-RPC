package com.warasugi.amrpc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application 本体。常駐サービス用の通知チャンネルを 1 つ用意する。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Apple Music RPC",
                NotificationManager.IMPORTANCE_LOW, // 音は出さない。常駐表示のみ。
            ).apply {
                description = "再生中の曲を Discord に中継している間だけ表示されます。"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "amrpc_bridge"
    }
}
