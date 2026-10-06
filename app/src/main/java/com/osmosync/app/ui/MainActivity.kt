package com.osmosync.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.osmosync.app.App

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrashReportDialog()
                    PermissionGate {
                        MainTabs()
                    }
                }
            }
        }
    }
}

/** 如果上次发生了崩溃，启动时弹窗显示堆栈，方便远程定位问题 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var crashFile by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(Unit) { crashFile = com.osmosync.app.util.CrashGuard.latestCrash(context) }
    crashFile?.let { f ->
        AlertDialog(
            onDismissRequest = { crashFile = null },
            title = { Text("检测到上次异常退出") },
            text = {
                Text(
                    try { f.readText().take(3000) } catch (_: Exception) { "（无法读取）" },
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", f.readText()))
                    } catch (_: Exception) {}
                    com.osmosync.app.util.CrashGuard.clear(context)
                    crashFile = null
                }) { Text("复制堆栈") }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.osmosync.app.util.CrashGuard.clear(context)
                    crashFile = null
                }) { Text("清除") }
            },
        )
    }
}

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(checkPermissions(context)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = checkPermissions(context)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
        if (granted) App.instance.phone.bind()
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("需要以下权限才能工作：", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "· 蓝牙（扫描并连接大疆相机）\n· 相机（手机同步拍摄）\n· 通知（连拍保活进度）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(requiredPermissions()) }) {
                Text("授予权限")
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_CONNECT
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_ADVERTISE
    } else {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    list += Manifest.permission.CAMERA
    if (Build.VERSION.SDK_INT >= 33) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}

private fun checkPermissions(context: android.content.Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun MainTabs() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    label = { Text("相机管理") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    label = { Text("定时连拍") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.GroupWork, contentDescription = null) },
                    label = { Text("协同") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> DevicesScreen(Modifier.padding(padding))
            1 -> ShootScreen(Modifier.padding(padding))
            else -> MeshScreen(Modifier.padding(padding))
        }
    }
}
