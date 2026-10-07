package com.osmosync.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osmosync.app.App
import com.osmosync.app.ble.DeviceHit
import com.osmosync.app.ble.LinkState
import com.osmosync.app.protocol.Dji
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(modifier: Modifier = Modifier) {
    val app = App.instance
    val cameras by app.cameraManager.cameras.collectAsState()
    val found by app.cameraManager.found.collectAsState()
    val scanning by app.cameraManager.scanning.collectAsState()
    val scanError by app.cameraManager.scanError.collectAsState()
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    val connectedCount by app.cameraManager.connectedCount.collectAsState()
    val sleepingCount by app.cameraManager.sleepingConnectedCount.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        // ---- 已连接相机 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的相机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { app.cameraManager.wakeAndReconnect() },
                    enabled = connectedCount == 0 || sleepingCount > 0,
                ) { Text("唤醒并重连") }
            }
            if (connectedCount > 0) {
                Text(
                    if (sleepingCount > 0) "检测到 $sleepingCount 台休眠相机，点击按钮直接通过蓝牙唤醒"
                    else "相机在线且未休眠，无需唤醒",
                    fontSize = 11.sp, color = Color.Gray,
                )
            }
        }
        if (cameras.isEmpty()) {
            item {
                Text(
                    "还没有连接相机。打开相机电源后点击下方\"扫描\"，\n在扫描结果里点击\"连接\"（首次配对需在相机屏幕上确认）。",
                    fontSize = 13.sp, color = Color.Gray,
                )
            }
        }
        items(cameras, key = { "cam-" + it.mac }) { cam ->
            val ui by cam.ui.collectAsState()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.model.ifBlank { ui.name },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        StateChip(ui.state)
                    }
                    Text(ui.mac, fontSize = 12.sp, color = Color.Gray)
                    if (ui.state == LinkState.CONNECTED) {
                        Spacer(Modifier.height(4.dp))
                        val parts = buildList {
                            if (ui.batteryPercent in 0..100) add("电量 ${ui.batteryPercent}%")
                            if (ui.cameraMode >= 0) add(Dji.modeName(ui.cameraMode))
                            if (ui.camStatus >= 0) add(Dji.statusName(ui.camStatus))
                            if (ui.remainCapacityMb >= 0) add("剩余 ${ui.remainCapacityMb / 1024f}GB")
                        }
                        Text(parts.joinToString(" · "), fontSize = 13.sp)
                    }
                    ui.errorMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 12.sp, color = Color(0xFFB3261E))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { scope.launch { cam.takePhoto() } }, enabled = ui.state == LinkState.CONNECTED) {
                            Text("拍一张")
                        }
                        OutlinedButton(onClick = { scope.launch { cam.switchMode(Dji.MODE_PHOTO) } }, enabled = ui.state == LinkState.CONNECTED) {
                            Text("切拍照")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                if (!cam.startRecording()) {
                                    app.cameraManager.postMessage("录像指令未应答，请确认相机已连接且不在拍摄中")
                                }
                            }
                        }, enabled = ui.state == LinkState.CONNECTED) {
                            Text("录像")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { scope.launch { cam.stopRecording() } }, enabled = ui.state == LinkState.CONNECTED) {
                            Text("停止录像")
                        }
                        OutlinedButton(onClick = { scope.launch { cam.sleepCamera() } }, enabled = ui.state == LinkState.CONNECTED) {
                            Text("睡眠")
                        }
                        OutlinedButton(onClick = { app.cameraManager.disconnect(cam.mac) }) {
                            Text("断开")
                        }
                    }
                }
            }
        }

        // ---- GPS 注入 ----
        item { GpsCard() }

        // ---- Insta360 遥控模式 ----
        item { Insta360Card() }

        // ---- 扫描 ----
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("扫描附近相机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (scanning) "扫描中，请确认相机已开机..." else "相机开机后靠近手机（30秒自动停止）",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                }
                Switch(checked = scanning, onCheckedChange = { on ->
                    if (on) app.cameraManager.startScan() else app.cameraManager.stopScan()
                })
            }
            scanError?.let { Text(it, fontSize = 12.sp, color = Color(0xFFB3261E)) }
        }

        // 扫描列表里隐藏已添加到"我的相机"的设备，避免重复连接
        val connectedMacs = cameras.map { it.mac }.toSet()
        val visible = (if (showAll) found else found.filter { it.isDjiCamera })
            .filter { it.mac !in connectedMacs }
        if (found.any { !it.isDjiCamera }) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示全部蓝牙设备", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }
        }
        items(visible, key = { "hit-" + it.mac }) { hit ->
            DeviceHitRow(hit) { app.cameraManager.connect(hit) }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (scanning) "（未发现设备）" else "（打开开关开始扫描）",
                    fontSize = 13.sp, color = Color.LightGray,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GpsCard() {
    val app = App.instance
    val pushing by app.cameraManager.gpsPushing.collectAsState()
    val fix by app.gps.fix.collectAsState()
    val status by app.gps.status.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GPS 注入到相机照片", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            pushing && fix != null -> "推送中 · $status"
                            pushing -> status.ifBlank { "等待定位..." }
                            else -> "开启后手机定位按 1Hz 推给相机（写入照片元数据）"
                        },
                        fontSize = 12.sp, color = if (pushing && fix != null) Color(0xFF1B873B) else Color.Gray,
                    )
                }
                Switch(checked = pushing, onCheckedChange = { on -> app.cameraManager.setGpsPush(on) })
            }
        }
    }
}

@Composable
private fun Insta360Card() {
    val app = App.instance
    val enabled by app.insta360.enabled.collectAsState()
    val cams by app.insta360.cams.collectAsState()
    val status by app.insta360.status.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Insta360 遥控模式", fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "正以 \"Insta360 GPS Remote\" 对外广播，等待相机接入"
                        else "把手机模拟成影石 GPS 遥控器（影石相机主动连手机）",
                        fontSize = 12.sp, color = if (enabled) Color(0xFF1B873B) else Color.Gray,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { on -> if (on) app.insta360.start() else app.insta360.stop() })
            }
            if (status.isNotBlank()) {
                Text(status, fontSize = 12.sp, color = if (cams.isEmpty()) Color.Gray else Color(0xFF1B873B))
            }
            cams.forEach { cam ->
                Text(
                    "${cam.name} · ${cam.mac} · ${if (cam.subscribed) "可遥控" else "接入中..."}",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (cams.any { it.subscribed }) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { app.insta360.shutter() }) { Text("快门") }
                    OutlinedButton(onClick = { app.insta360.switchMode() }) { Text("切模式") }
                    OutlinedButton(onClick = { app.insta360.toggleScreen() }) { Text("息屏") }
                    OutlinedButton(onClick = { app.insta360.powerOff() }) { Text("关机") }
                }
            }
            Text(
                "使用：开启本开关后，在相机 设置→蓝牙/遥控器连接 里选择 \"Insta360 GPS Remote\"。支持 X3/ONE RS 等兼容 GPS 遥控器的机型；相机需开机（不支持唤醒关机）",
                fontSize = 11.sp, color = Color.Gray,
            )
        }
    }
}

@Composable
private fun DeviceHitRow(hit: DeviceHit, onConnect: () -> Unit) {
    var connecting by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hit.name.ifBlank { "未命名设备" }, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    if (hit.isDjiCamera) {
                        Text(
                            "DJI",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFF000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text("${hit.mac} · ${hit.rssi} dBm", fontSize = 12.sp, color = Color.Gray)
            }
            if (hit.isDjiCamera) {
                Button(onClick = {
                    connecting = true
                    onConnect()
                }) { Text("连接") }
            } else {
                TextButton(onClick = onConnect) { Text("连接") }
            }
        }
    }
}

@Composable
private fun StateChip(state: LinkState) {
    val (text, color) = when (state) {
        LinkState.CONNECTED -> "已连接" to Color(0xFF1B873B)
        LinkState.GATT_CONNECTING -> "连接中..." to Color(0xFFB58500)
        LinkState.DISCOVERING -> "发现服务..." to Color(0xFFB58500)
        LinkState.HANDSHAKING -> "配对中..." to Color(0xFFB58500)
        LinkState.DISCONNECTED -> "未连接" to Color.Gray
    }
    Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
}
