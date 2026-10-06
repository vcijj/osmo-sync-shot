package com.osmosync.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.osmosync.app.protocol.Dji
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 扫描到的一台蓝牙设备 */
data class DeviceHit(
    val mac: String,
    val name: String,
    val rssi: Int,
    val isDjiCamera: Boolean,
    val lastSeenMs: Long,
)

/** 单台相机的拍摄结果 */
data class ShotResult(
    val mac: String,
    val name: String,
    val model: String,
    val ok: Boolean,
    val detail: String,
)

/**
 * 多相机管理器：扫描识别大疆相机广播、维护多路 BLE 连接、批量群发指令。
 *
 * 相机广播识别标准（官方 Demo ble.c 的 bsp_link_is_dji_camera_adv）：
 * 厂商自定义字段（含 2 字节 Company ID）第 0/1/4 字节分别为 0xAA / 0x08 / 0xFA。
 * Android 的 getManufacturerSpecificData() 不含 Company ID，因此重组后再比对。
 */
@SuppressLint("MissingPermission")
class CameraManager(
    private val context: Context,
    val identity: RemoteIdentity,
    private val gps: com.osmosync.app.gps.GpsProvider,
    private val trackStore: com.osmosync.app.track.TrackStore? = null,
    private val orientation: com.osmosync.app.gps.OrientationProvider? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _found = MutableStateFlow<List<DeviceHit>>(emptyList())
    val found: StateFlow<List<DeviceHit>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    /** 在设备页顶部显示一条提示（也用于唤醒/操作结果提示） */
    fun postMessage(msg: String?) {
        _scanError.value = msg
    }

    private val _cameras = MutableStateFlow<List<OsmoCamera>>(emptyList())
    val cameras: StateFlow<List<OsmoCamera>> = _cameras

    private val foundMap = LinkedHashMap<String, DeviceHit>()

    fun hasPermission(): Boolean {
        val connectOk = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (android.os.Build.VERSION.SDK_INT >= 31) connectOk
        else connectOk || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------- 扫描 ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record: ScanRecord? = try { result.scanRecord } catch (_: Exception) { null }
            val isDji = record.isDjiCameraAdv()
            val name = try { result.device?.name } catch (_: Exception) { null } ?: record?.deviceName ?: ""
            val mac = try { result.device?.address } catch (_: Exception) { null } ?: return
            synchronized(foundMap) {
                val prev = foundMap[mac]
                foundMap[mac] = DeviceHit(
                    mac = mac,
                    name = name.ifBlank { prev?.name ?: "" },
                    rssi = result.rssi,
                    isDjiCamera = isDji || (prev?.isDjiCamera ?: false),
                    lastSeenMs = System.currentTimeMillis(),
                )
                publishFound()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanError.value = "扫描失败 code=$errorCode"
            _scanning.value = false
        }
    }

    private fun ScanRecord?.isDjiCameraAdv(): Boolean {
        if (this == null) return false
        val sparse = try { manufacturerSpecificData } catch (_: Exception) { return false }
        for (i in 0 until sparse.size()) {
            val cid = sparse.keyAt(i)
            val data = sparse.valueAt(i) ?: continue
            // 重组完整厂商字段：2 字节 Company ID（小端）+ 数据
            val full = ByteArray(data.size + 2)
            full[0] = (cid and 0xFF).toByte()
            full[1] = ((cid ushr 8) and 0xFF).toByte()
            data.copyInto(full, 2)
            if (full.size >= 5 && full[0] == 0xAA.toByte() && full[1] == 0x08.toByte() && full[4] == 0xFA.toByte()) {
                return true
            }
        }
        return false
    }

    private fun publishFound() {
        val now = System.currentTimeMillis()
        synchronized(foundMap) {
            foundMap.entries.removeIf { now - it.value.lastSeenMs > 20_000 }
            _found.value = foundMap.values
                .sortedWith(compareByDescending<DeviceHit> { it.isDjiCamera }.thenByDescending { it.rssi })
        }
    }

    fun startScan() {
        if (_scanning.value) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            _scanError.value = "蓝牙未开启"
            return
        }
        if (!hasPermission()) {
            _scanError.value = "缺少蓝牙权限"
            return
        }
        _scanError.value = null
        val scanner = a.bluetoothLeScanner ?: run {
            _scanError.value = "BLE 扫描器不可用"
            return
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            _scanning.value = true
            scope.launch {
                delay(30_000)
                if (_scanning.value) stopScan()
            }
        } catch (e: SecurityException) {
            _scanError.value = "扫描被拒绝: ${e.message}"
        }
    }

    fun stopScan() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        _scanning.value = false
    }

    // ---------------- 连接管理 ----------------

    fun connectedCameras(): List<OsmoCamera> =
        _cameras.value.filter { it.ui.value.state == LinkState.CONNECTED }

    fun cameraByMac(mac: String): OsmoCamera? = _cameras.value.firstOrNull { it.mac == mac }

    fun connect(hit: DeviceHit) {
        if (cameraByMac(hit.mac) != null) {
            cameraByMac(hit.mac)?.connect()
            return
        }
        val cam = OsmoCamera(context, hit.mac, hit.name.ifBlank { "DJI相机" }, identity, object : OsmoCamera.Listener {
            override fun onHandshakeSucceeded(camera: OsmoCamera) {
                identity.markPaired(camera.mac)
                scope.launch {
                    delay(100)
                    val connected = connectedCameras()
                    connected.forEachIndexed { i, c -> c.setIndex(if (connected.size == 1) 0 else i + 1) }
                }
            }

            override fun onLinkLost(camera: OsmoCamera) {
                // 已在相机内部处理重连/报错
            }
        })
        _cameras.value = _cameras.value + cam
        cam.connect()
    }

    fun disconnect(mac: String) {
        cameraByMac(mac)?.disconnect()
    }

    fun disconnectAll() {
        _cameras.value.forEach { it.disconnect() }
    }

    fun forget(mac: String) {
        disconnect(mac)
        _cameras.value = _cameras.value.filterNot { it.mac == mac }
    }

    // ---------------- 批量指令 ----------------

    private var gpsJob: kotlinx.coroutines.Job? = null

    private val _gpsPushing = MutableStateFlow(false)
    val gpsPushing: StateFlow<Boolean> = _gpsPushing

    /** 开关：把手机 GPS 按 1Hz 推给所有已连接相机（相机写入照片元数据），同时记录轨迹 */
    fun setGpsPush(enabled: Boolean) {
        if (enabled) {
            if (gpsJob != null) return
            gps.start()
            orientation?.start()
            _gpsPushing.value = true
            gpsJob = scope.launch {
                while (true) {
                    val fix = gps.fix.value
                    if (fix != null) {
                        val payload = buildGpsPayload(fix)
                        connectedCameras().forEach { cam ->
                            try { cam.sendGps(payload) } catch (_: Exception) {}
                        }
                        // 连续轨迹点：每 4 秒记录一次移动轨迹
                        val now = System.currentTimeMillis()
                        if (trackStore != null && now - lastTrackAddMs > 4000) {
                            lastTrackAddMs = now
                            trackStore.add(
                                com.osmosync.app.track.TrackPoint(
                                    timeMs = now,
                                    lat = fix.latitude,
                                    lon = fix.longitude,
                                    headingDeg = null,
                                    altitudeM = fix.altitudeM,
                                    isShot = false,
                                    shotIndex = null,
                                    photoUri = null,
                                ),
                            )
                        }
                    }
                    delay(1000)
                }
            }
        } else {
            gpsJob?.cancel()
            gpsJob = null
            gps.stop()
            orientation?.stop()
            _gpsPushing.value = false
        }
    }

    @Volatile private var lastTrackAddMs = 0L

    private fun buildGpsPayload(f: com.osmosync.app.gps.GpsFix): ByteArray {
        val cal = java.util.Calendar.getInstance()
        val ymd = cal.get(java.util.Calendar.YEAR) * 10000 +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH)
        val hms = cal.get(java.util.Calendar.HOUR_OF_DAY) * 10000 +
                cal.get(java.util.Calendar.MINUTE) * 100 + cal.get(java.util.Calendar.SECOND)
        val bearingRad = Math.toRadians(f.bearingDeg.toDouble())
        val speedCms = f.speedMS * 100f
        val north = (speedCms * kotlin.math.cos(bearingRad)).toFloat()
        val east = (speedCms * kotlin.math.sin(bearingRad)).toFloat()
        return com.osmosync.app.protocol.Dji.gpsPush(
            ymd = ymd,
            hms = hms,
            lonE7 = (f.longitude * 1e7).toInt(),
            latE7 = (f.latitude * 1e7).toInt(),
            heightMm = (f.altitudeM * 1000).toLong(),
            speedNorthCms = north,
            speedEastCms = east,
            speedDownCms = 0f,
            vertAccMm = (f.accuracyM * 1000).toLong().coerceAtLeast(1),
            horizAccMm = (f.accuracyM * 1000).toLong().coerceAtLeast(1),
            speedAccCms = (f.accuracyM * 100).toLong().coerceAtLeast(1),
            satellites = f.satellites.toLong(),
        )
    }

    suspend fun shutterAll(): List<ShotResult> = coroutineScope {
        val cams = connectedCameras()
        cams.map { cam ->
            async {
                try {
                    val ok = cam.takePhoto()
                    ShotResult(cam.mac, cam.ui.value.name, cam.ui.value.model, ok, if (ok) "已触发" else "发送失败")
                } catch (e: Exception) {
                    ShotResult(cam.mac, cam.ui.value.name, cam.ui.value.model, false, e.message ?: "异常")
                }
            }
        }.awaitAll()
    }

    suspend fun switchAllToPhoto(): List<ShotResult> = batch { it.switchMode(Dji.MODE_PHOTO) }

    suspend fun recordAll(start: Boolean): List<ShotResult> = batch { it.recordControl(start) }

    private suspend fun batch(action: suspend (OsmoCamera) -> Boolean): List<ShotResult> = coroutineScope {
        connectedCameras().map { cam ->
            async {
                try {
                    val ok = action(cam)
                    ShotResult(cam.mac, cam.ui.value.name, cam.ui.value.model, ok, if (ok) "成功" else "无应答")
                } catch (e: Exception) {
                    ShotResult(cam.mac, cam.ui.value.name, cam.ui.value.model, false, e.message ?: "异常")
                }
            }
        }.awaitAll()
    }
}
