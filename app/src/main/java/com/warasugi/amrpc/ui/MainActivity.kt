package com.warasugi.amrpc.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.warasugi.amrpc.data.BridgeStatus
import com.warasugi.amrpc.data.ConnectionState
import com.warasugi.amrpc.data.Settings
import com.warasugi.amrpc.service.MediaListenerService
import com.warasugi.amrpc.service.RpcBridgeService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // システム設定に関わらずダークで統一する。
            MaterialTheme(colorScheme = darkColorScheme()) {
                ScreenRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenRoot() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val status by BridgeStatus.state.collectAsState()

    // 入力フィールドの状態(初期値は保存済み設定から)
    var enabled by remember { mutableStateOf(settings.enabled) }
    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var token by remember { mutableStateOf(settings.token) }
    var sourceId by remember { mutableStateOf(settings.sourceId) }
    var sourceName by remember { mutableStateOf(settings.sourceName) }
    var targetPkgs by remember { mutableStateOf(settings.targetPackagesRaw) }
    var country by remember { mutableStateOf(settings.country) }
    var keepalive by remember { mutableStateOf(settings.keepaliveSeconds.toString()) }
    var artworkEnabled by remember { mutableStateOf(settings.artworkEnabled) }
    var artworkSize by remember { mutableStateOf(settings.artworkSize.toString()) }

    // 権限状態(ON_RESUME で再評価)
    var notifAccess by remember { mutableStateOf(isNotifAccessGranted(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAccess = isNotifAccessGranted(context)
                batteryOk = isIgnoringBattery(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 結果は通知表示可否に反映される */ }

    fun save() {
        settings.enabled = enabled
        settings.host = host
        settings.port = port.toIntOrNull() ?: 13520
        settings.token = token
        settings.sourceId = sourceId
        settings.sourceName = sourceName
        settings.targetPackagesRaw = targetPkgs
        settings.country = country
        settings.keepaliveSeconds = keepalive.toIntOrNull() ?: 20
        settings.artworkEnabled = artworkEnabled
        settings.artworkSize = artworkSize.toIntOrNull() ?: 512
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Apple Music → Discord RPC") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ステータス
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("状態", style = MaterialTheme.typography.titleMedium)
                    Text("接続: ${stateLabel(status.connection)} — ${status.detail}")
                    status.lastNowPlaying?.let {
                        Text("再生中: $it" + if (status.lastArtworkResolved) "  (アートワーク取得済)" else "")
                    }
                }
            }

            // 権限
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("権限", style = MaterialTheme.typography.titleMedium)
                    Text("通知アクセス: " + if (notifAccess) "許可済み" else "未許可(必須)")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("通知アクセス設定を開く") }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = {
                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("通知の表示を許可") }
                    }

                    Text("電池最適化の除外: " + if (batteryOk) "除外済み" else "未除外(推奨)")
                    OutlinedButton(onClick = { requestIgnoreBattery(context) }) {
                        Text("電池最適化から除外する")
                    }
                }
            }

            // 接続設定
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("接続設定", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                        Text("有効", modifier = Modifier.padding(top = 12.dp))
                    }
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("PC ホスト (Twingate の IP / Resource DNS)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("ポート") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("BRIDGE_TOKEN") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ソース / 検出
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ソース / 検出", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = sourceId, onValueChange = { sourceId = it },
                        label = { Text("source_id (PC 側と一致)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sourceName, onValueChange = { sourceName = it },
                        label = { Text("source_name (GUI 表示名)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = targetPkgs, onValueChange = { targetPkgs = it },
                        label = { Text("対象パッケージ (カンマ区切り)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // アートワーク
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("アートワーク (iTunes Search API)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = artworkEnabled, onCheckedChange = { artworkEnabled = it })
                        Text("有効(曲名/アーティストを Apple に送って画像URLを取得)", modifier = Modifier.padding(top = 12.dp))
                    }
                    OutlinedTextField(
                        value = country, onValueChange = { country = it },
                        label = { Text("ストアフロント (jp など)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = artworkSize, onValueChange = { artworkSize = it },
                        label = { Text("解像度 (100-1024)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = keepalive, onValueChange = { keepalive = it },
                        label = { Text("再送間隔 秒 (TTL維持・28以下)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("保存") }
                Button(onClick = {
                    save()
                    val intent = Intent(context, RpcBridgeService::class.java)
                        .setAction(RpcBridgeService.ACTION_RELOAD)
                    ContextCompat.startForegroundService(context, intent)
                }, modifier = Modifier.weight(1f)) { Text("接続/再接続") }
            }
            OutlinedButton(
                onClick = { context.stopService(Intent(context, RpcBridgeService::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("停止") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun stateLabel(s: ConnectionState): String = when (s) {
    ConnectionState.READY -> "接続済み"
    ConnectionState.CONNECTING -> "接続中"
    ConnectionState.ERROR -> "未接続/エラー"
    ConnectionState.IDLE -> "停止中"
}

private fun isNotifAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
