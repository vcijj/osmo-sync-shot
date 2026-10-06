package com.osmosync.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osmosync.app.App
import com.osmosync.app.mesh.MeshRole
import com.osmosync.app.shooter.ShootConfig
import com.osmosync.app.shooter.ShooterState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MeshScreen(modifier: Modifier = Modifier) {
    val app = App.instance
    val mesh = app.mesh
    val role by mesh.role.collectAsState()
    val netStatus by mesh.netStatus.collectAsState()
    val slaves by mesh.slaves.collectAsState()
    val progress by mesh.progress.collectAsState()
    val slaveState by mesh.slaveState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // ---- 角色选择 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("多手机协同", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "多台手机各连各的相机，跨机同步开拍。主机开热点（设置里手动开启），从机连入同一网络。",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoleButton("单机", role == MeshRole.NONE, Modifier.weight(1f)) { mesh.stopMaster(); mesh.stopSlave() }
                        RoleButton("主机", role == MeshRole.MASTER, Modifier.weight(1f)) { mesh.startMaster() }
                        RoleButton("从机", role == MeshRole.SLAVE, Modifier.weight(1f)) { mesh.startSlave(null) }
                    }
                    if (netStatus.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(netStatus, fontSize = 12.sp, color = Color(0xFF1B873B))
                    }
                }
            }
        }

        // ---- 主机：从机列表 + 会话 ----
        if (role == MeshRole.MASTER) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("已接入从机（${slaves.size}）", fontWeight = FontWeight.Bold)
                        if (slaves.isEmpty()) {
                            Text("等待从机连入...（从机切到\"从机\"模式即可自动发现）", fontSize = 12.sp, color = Color.Gray)
                        }
                        slaves.forEach { s ->
                            Text(
                                "${s.name} · 相机 ${s.cams} 台 · 时钟偏差 ${s.offsetMs}ms · 延迟 ${s.rttMs}ms",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item { MasterSessionCard() }
        }

        // ---- 从机：连接状态 + 会话信息 ----
        if (role == MeshRole.SLAVE) {
            item { SlaveCard() }
        }

        // ---- 会话进度（主机） ----
        if (role == MeshRole.MASTER && progress.isNotEmpty()) {
            item { Text("会话进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(progress.asReversed(), key = { "${it.label}-${it.atMs}-${it.shot}" }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                        Text(
                            p.label + (if (p.shot > 0) " · 第 ${p.shot} 张" else ""),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                        )
                        if (p.shot > 0) {
                            Text(
                                "相机✓${p.ok} ✗${p.fail}" + if (p.phone) " · 手机✓" else "",
                                fontSize = 12.sp,
                                color = if (p.fail == 0) Color(0xFF1B873B) else Color(0xFFB3261E),
                            )
                        }
                        Spacer(Modifier.padding(2.dp))
                        Text(sdf.format(Date(p.atMs)), fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RoleButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(text) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}

@Composable
private fun MasterSessionCard() {
    val app = App.instance
    val mesh = app.mesh
    val shooter = app.shooter
    val shooterState by shooter.state.collectAsState()

    var intervalText by remember { mutableStateOf("5") }
    var shotsText by remember { mutableStateOf("0") }
    var delayText by remember { mutableStateOf("0") }
    var clockText by remember { mutableStateOf("") }
    var phoneCapture by remember { mutableStateOf(true) }
    var switchPhotoFirst by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val cfg = ShootConfig(
        intervalSeconds = intervalText.toDoubleOrNull()?.coerceIn(0.5, 3600.0) ?: 5.0,
        totalShots = shotsText.toIntOrNull()?.coerceIn(0, 99999) ?: 0,
        startDelaySeconds = delayText.toIntOrNull()?.coerceIn(0, 86400) ?: 0,
        startAtClock = clockText,
        phoneCapture = phoneCapture,
        switchToPhotoFirst = switchPhotoFirst,
    )
    val running = shooterState is ShooterState.Running

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会话参数", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = intervalText, onValueChange = { intervalText = it },
                    label = { Text("间隔(秒)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = shotsText, onValueChange = { shotsText = it },
                    label = { Text("张数(0=不限)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = delayText, onValueChange = { delayText = it },
                    label = { Text("倒计时(秒)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = clockText, onValueChange = { clockText = it },
                    label = { Text("定时开始 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("各机手机同步拍照", Modifier.weight(1f))
                Switch(checked = phoneCapture, onCheckedChange = { phoneCapture = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开始前统一切拍照模式", Modifier.weight(1f))
                Switch(checked = switchPhotoFirst, onCheckedChange = { switchPhotoFirst = it })
            }
            if (running) {
                val s = shooterState as ShooterState.Running
                Text(
                    "进行中：第 ${s.currentShot} 张" + (if (s.totalShots > 0) " / ${s.totalShots}" else ""),
                    color = Color(0xFF1B873B), fontWeight = FontWeight.Medium,
                )
                Button(
                    onClick = { mesh.stopMasterSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                ) { Text("停止全部") }
            } else {
                Button(onClick = {
                    if (mesh.startMasterSession(cfg)) errorText = null
                    else errorText = "开始时刻无效（定时格式应为 HH:mm）"
                }) { Text("开始协同连拍") }
            }
            errorText?.let { Text(it, fontSize = 12.sp, color = Color(0xFFB3261E)) }
        }
    }
}

@Composable
private fun SlaveCard() {
    val app = App.instance
    val mesh = app.mesh
    val state by mesh.slaveState.collectAsState()
    val netStatus by mesh.netStatus.collectAsState()
    val shooterState by app.shooter.state.collectAsState()

    var manualIp by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("从机状态", fontWeight = FontWeight.Bold)
            state?.let { s ->
                Text("主机: ${s.masterName}", fontSize = 13.sp)
                Text("时钟偏差: ${s.offsetMs} ms · 往返延迟: ${s.rttMs} ms", fontSize = 13.sp)
                s.session?.let { cfg ->
                    Text(
                        "收到会话：间隔 ${cfg.intervalSeconds}s · 张数 ${if (cfg.totalShots == 0) "不限" else cfg.totalShots}" +
                                " · 开始 ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(s.sessionStartAt))}",
                        fontSize = 13.sp, color = Color(0xFF1B873B), fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (shooterState is ShooterState.Running) {
                val s = shooterState as ShooterState.Running
                Text(
                    "拍摄中：第 ${s.currentShot} 张" + (if (s.totalShots > 0) " / ${s.totalShots}" else ""),
                    color = Color(0xFF1B873B), fontWeight = FontWeight.Medium,
                )
            }
            if (netStatus.isNotBlank()) Text(netStatus, fontSize = 12.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp, onValueChange = { manualIp = it },
                    label = { Text("主机IP(可选)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedButton(onClick = { mesh.startSlave(manualIp.ifBlank { null }) }) {
                    Text("重新连接")
                }
            }
            OutlinedButton(onClick = { mesh.stopSlave() }) { Text("退出从机模式") }
        }
    }
}
