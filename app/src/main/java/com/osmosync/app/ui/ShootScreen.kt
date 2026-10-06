package com.osmosync.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.osmosync.app.App
import com.osmosync.app.service.CaptureService
import com.osmosync.app.shooter.ShootConfig
import com.osmosync.app.shooter.ShooterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShootScreen(modifier: Modifier = Modifier) {
    val app = App.instance
    val shooter = app.shooter
    val state by shooter.state.collectAsState()
    val logs by shooter.logs.collectAsState()
    val connectedCount = app.cameraManager.connectedCameras().size
    val scope = rememberCoroutineScope()

    var intervalText by remember { mutableStateOf("5") }
    var shotsText by remember { mutableStateOf("0") }
    var delayText by remember { mutableStateOf("0") }
    var clockText by remember { mutableStateOf("") }
    var phoneCapture by remember { mutableStateOf(true) }
    var switchPhotoFirst by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val running = state is ShooterState.Running
    val cfg = ShootConfig(
        intervalSeconds = intervalText.toDoubleOrNull()?.coerceIn(0.5, 3600.0) ?: 5.0,
        totalShots = shotsText.toIntOrNull()?.coerceIn(0, 99999) ?: 0,
        startDelaySeconds = delayText.toIntOrNull()?.coerceIn(0, 86400) ?: 0,
        startAtClock = clockText,
        phoneCapture = phoneCapture,
        switchToPhotoFirst = switchPhotoFirst,
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // ---- 手机预览 ----
        if (phoneCapture) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("手机同步画面（后置）", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).also { app.phone.attachPreviewView(it) }
                                },
                                onRelease = { app.phone.detachPreviewView() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        // ---- 参数设置 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("拍摄设置", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it },
                            label = { Text("间隔(秒)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = shotsText,
                            onValueChange = { shotsText = it },
                            label = { Text("张数(0=不限)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = { delayText = it },
                            label = { Text("倒计时(秒)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = clockText,
                            onValueChange = { clockText = it },
                            label = { Text("定时开始 HH:mm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("手机同步拍照", Modifier.weight(1f))
                        Switch(checked = phoneCapture, onCheckedChange = { phoneCapture = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("开始前切到拍照模式")
                            Text("避免相机处于录像模式", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(checked = switchPhotoFirst, onCheckedChange = { switchPhotoFirst = it })
                    }
                }
            }
        }

        // ---- 状态与控制 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (val s = state) {
                        is ShooterState.Running -> {
                            val now by produceState(System.currentTimeMillis()) {
                                while (true) { value = System.currentTimeMillis(); delay(200) }
                            }
                            if (now < s.actualStartAtMs) {
                                val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                                Text(
                                    "将于 ${sdf.format(Date(s.actualStartAtMs))} 开始",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("等待中...", color = Color.Gray, fontSize = 13.sp)
                            } else {
                                Text(
                                    "第 ${s.currentShot} 张" + if (s.totalShots > 0) " / ${s.totalShots}" else "（不限）",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                val remain = ((s.nextShotAtMs - now) / 1000.0).coerceAtLeast(0.0)
                                Text(
                                    if (s.currentShot == 0) "即将开始" else "下一张: %.1f 秒后".format(remain),
                                    color = Color.Gray, fontSize = 13.sp,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    shooter.stop()
                                    CaptureService.stop(app)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                            ) { Text("停止连拍") }
                        }
                        else -> {
                            Text(
                                if (connectedCount > 0) "已连接 $connectedCount 台相机" else "尚未连接相机（请先到\"相机管理\"连接）",
                                fontSize = 13.sp,
                                color = if (connectedCount > 0) Color(0xFF1B873B) else Color.Gray,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (shooter.computeStartAt(cfg) == null) {
                                        errorText = "定时开始时间格式错误（应为 HH:mm，如 07:30）"
                                    } else {
                                        errorText = null
                                        CaptureService.start(app)
                                        shooter.start(cfg)
                                    }
                                },
                            ) { Text("开始定时连拍") }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { scope.launch { shooter.fireOnce(0, cfg) } }) {
                                Text("立即群拍一张（测试）")
                            }
                        }
                    }
                    errorText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 12.sp, color = Color(0xFFB3261E))
                    }
                }
            }
        }

        // ---- 拍摄记录 ----
        item {
            Text("拍摄记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        val reversed = logs.asReversed()
        items(reversed, key = { "${it.index}-${it.timeMs}" }) { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row {
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                        Text(
                            (if (log.index == 0) "手动测试" else "第 ${log.index} 张") + "  " + sdf.format(Date(log.timeMs)),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                log.phoneSaved -> "手机✓"
                                log.phoneDetail == "未启用" -> "手机—"
                                else -> "手机✗"
                            },
                            fontSize = 12.sp,
                            color = if (log.phoneSaved) Color(0xFF1B873B) else Color(0xFFB3261E),
                        )
                    }
                    if (log.cameraResults.isEmpty()) {
                        Text("（无已连接相机）", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        log.cameraResults.forEach { r ->
                            Text(
                                "${r.model.ifBlank { r.name }}: ${if (r.ok) "✓ ${r.detail}" else "✗ ${r.detail}"}",
                                fontSize = 12.sp,
                                color = if (r.ok) Color.Unspecified else Color(0xFFB3261E),
                            )
                        }
                    }
                    if (!log.phoneSaved && log.phoneDetail != "未启用") {
                        Text(log.phoneDetail, fontSize = 12.sp, color = Color(0xFFB3261E))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
