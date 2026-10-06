package com.osmosync.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.osmosync.app.protocol.Dji
import com.osmosync.app.protocol.DjiFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { DISCONNECTED, GATT_CONNECTING, DISCOVERING, HANDSHAKING, CONNECTED }

data class CameraUiState(
    val mac: String,
    val name: String,
    val state: LinkState = LinkState.DISCONNECTED,
    val model: String = "",
    val batteryPercent: Int = -1,
    val cameraMode: Int = -1,
    val camStatus: Int = -1,
    val remainCapacityMb: Long = -1,
    val remainPhotoNum: Long = -1,
    val cameraIndex: Int = 0,
    val pairingCode: Int = -1,   // 首次配对校验码，提示用户在相机屏幕上确认
    val errorMsg: String? = null,
)

@SuppressLint("MissingPermission")
class OsmoCamera(
    private val context: Context,
    val mac: String,
    name: String,
    /** 遥控端（手机）协议身份与配对记录 */
    private val identity: RemoteIdentity,
    private val listener: Listener,
) {
    interface Listener {
        fun onHandshakeSucceeded(camera: OsmoCamera)
        fun onLinkLost(camera: OsmoCamera)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val _ui = MutableStateFlow(CameraUiState(mac = mac, name = name))
    val ui: StateFlow<CameraUiState> = _ui

    @Volatile
    var userRequestedDisconnect = false
        private set

    @Volatile
    var cameraIndex: Int = 0
        private set

    private var retryCount = 0
    private var cameraRequestSeen = false

    private val writeMutex = Mutex()
    private var writeAck = CompletableDeferred<Boolean>()
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<DjiFrame.Parsed>>()

    private val seqLock = Any()
    private var seqValue = 0
    private fun nextSeq(): Int = synchronized(seqLock) { seqValue = (seqValue + 1) and 0xFFFF; seqValue }

    // 通知流重组缓冲（一帧可能跨多个 BLE 通知到达）
    private val rxLock = Any()
    private val rxBuf = mutableListOf<Byte>()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private fun setState(s: LinkState) {
        _ui.value = _ui.value.copy(state = s, errorMsg = if (s == LinkState.CONNECTED) null else _ui.value.errorMsg)
    }

    private fun patchUi(patch: (CameraUiState) -> CameraUiState) {
        _ui.value = patch(_ui.value)
    }

    fun setIndex(i: Int) {
        cameraIndex = i
        patchUi { it.copy(cameraIndex = i) }
    }

    // ---------------- 连接 ----------------

    fun connect() {
        if (adapter == null) {
            patchUi { it.copy(errorMsg = "本机不支持蓝牙") }
            return
        }
        if (_ui.value.state != LinkState.DISCONNECTED) return
        userRequestedDisconnect = false
        setState(LinkState.GATT_CONNECTING)
        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                patchUi { it.copy(errorMsg = "系统拒绝建立蓝牙连接") }
                setState(LinkState.DISCONNECTED)
            }
        } catch (e: Exception) {
            patchUi { it.copy(errorMsg = "连接失败: ${e.message}") }
            setState(LinkState.DISCONNECTED)
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        setState(LinkState.DISCONNECTED)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun cleanupAfterLoss() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        notifyChar = null
        waiters.clear()
        synchronized(rxLock) { rxBuf.clear() }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    retryCount = 0
                    try { g.requestMtu(247) } catch (_: Exception) { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val s = _ui.value.state
                    val wasWorking = s == LinkState.CONNECTED || s == LinkState.GATT_CONNECTING ||
                            s == LinkState.DISCOVERING || s == LinkState.HANDSHAKING
                    cleanupAfterLoss()
                    if (userRequestedDisconnect || !wasWorking) {
                        setState(LinkState.DISCONNECTED)
                    } else {
                        scope.launch {
                            if (retryCount < 3) {
                                retryCount++
                                patchUi { it.copy(errorMsg = "连接中断，正在重连($retryCount/3)...") }
                                setState(LinkState.DISCONNECTED)
                                delay(2000L)
                                connect()
                            } else {
                                patchUi { it.copy(errorMsg = "连接失败，请确认相机已开机且蓝牙可被连接") }
                                setState(LinkState.DISCONNECTED)
                                listener.onLinkLost(this@OsmoCamera)
                            }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                patchUi { it.copy(errorMsg = "发现服务失败 status=$status") }
                g.disconnect()
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val w = svc?.getCharacteristic(WRITE_UUID)
            val n = svc?.getCharacteristic(NOTIFY_UUID)
            if (svc == null || w == null || n == null) {
                patchUi { it.copy(errorMsg = "未找到大疆指令服务(FFF0)") }
                g.disconnect()
                return
            }
            writeChar = w
            notifyChar = n
            setState(LinkState.HANDSHAKING)
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                patchUi { it.copy(errorMsg = "未找到通知描述符") }
                g.disconnect()
                return
            }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) {
                patchUi { it.copy(errorMsg = "订阅通知失败") }
                g.disconnect()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                cameraRequestSeen = false
                scope.launch { runHandshake() }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAck.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            feed(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            feed(value)
        }
    }

    // ---------------- 底层收发 ----------------

    private suspend fun writeBytes(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        return writeMutex.withLock {
            try {
                writeAck = CompletableDeferred()
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = data
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
                if (!accepted) false
                else withTimeoutOrNull(3000) { writeAck.await() } ?: false
            } catch (_: Exception) {
                false
            } finally {
                delay(8) // 写间隔，避免指令洪泛
            }
        }
    }

    private fun feed(chunk: ByteArray) {
        val frames = mutableListOf<DjiFrame.Parsed>()
        synchronized(rxLock) {
            rxBuf.addAll(chunk.toList())
            if (rxBuf.size > 1024) rxBuf.subList(0, rxBuf.size - 1024).clear()
            val arr = rxBuf.toByteArray()
            var off = 0
            var consumed = 0
            while (true) {
                val parsed = DjiFrame.parse(arr, off) ?: break
                frames.add(parsed.first)
                off += parsed.second
                consumed = off
            }
            if (consumed > 0) rxBuf.subList(0, consumed).clear()
        }
        frames.forEach { dispatch(it) }
    }

    private fun dispatch(f: DjiFrame.Parsed) {
        if (f.isAck) {
            waiters.remove(f.seq)?.complete(f)
            return
        }
        when {
            f.cmdSet == Dji.CMD_SET_GENERAL && f.cmdId == Dji.CMD_CONNECT -> handleCameraConnectRequest(f)
            f.cmdSet == Dji.CMD_SET_CAMERA && f.cmdId == Dji.CMD_STATUS_PUSH -> {
                Dji.parseStatusPush(f.payload)?.let { st ->
                    patchUi {
                        it.copy(
                            batteryPercent = st.batteryPercent,
                            cameraMode = st.cameraMode,
                            camStatus = st.camStatus,
                            remainCapacityMb = st.remainCapacityMb,
                            remainPhotoNum = st.remainPhotoNum,
                        )
                    }
                }
            }
        }
    }

    private fun handleCameraConnectRequest(f: DjiFrame.Parsed) {
        val req = Dji.parseCameraConnectRequest(f.payload) ?: return
        if (req.verifyMode != 2) return // 只处理校验结果帧
        cameraRequestSeen = true
        if (req.verifyData == 0) {
            patchUi { it.copy(model = Dji.modelName(req.deviceId), pairingCode = -1) }
            scope.launch {
                val resp = Dji.connectResponse(identity.deviceId, 0, cameraIndex)
                writeBytes(DjiFrame.build(Dji.CMD_SET_GENERAL, Dji.CMD_CONNECT, DjiFrame.ACK_NO_RESPONSE, resp, f.seq))
                setState(LinkState.CONNECTED)
                listener.onHandshakeSucceeded(this@OsmoCamera)
                delay(150)
                // 订阅相机状态（2Hz 周期推送：电量/模式/存储）
                sendCommand(Dji.CMD_SET_CAMERA, Dji.CMD_STATUS_SUBSCRIBE, DjiFrame.CMD_NO_RESPONSE, Dji.statusSubscription())
            }
        } else {
            patchUi { it.copy(errorMsg = "相机拒绝了连接，请在相机蓝牙设置里删除本机配对后重试", pairingCode = -1) }
            scope.launch { gatt?.disconnect() }
        }
    }

    // ---------------- 协议握手 ----------------

    private suspend fun runHandshake() {
        try {
            val firstTime = !identity.isPaired(mac)
            val code = (0..9999).random()
            if (firstTime) patchUi { it.copy(pairingCode = code) }
            val req = Dji.connectionRequest(
                deviceId = identity.deviceId,
                mac = identity.macBytes,
                fwVersion = 0,
                verifyMode = if (firstTime) Dji.VERIFY_PAIR else Dji.VERIFY_RECONNECT,
                verifyData = if (firstTime) code else 0,
            )
            val resp = sendCommand(Dji.CMD_SET_GENERAL, Dji.CMD_CONNECT, DjiFrame.CMD_WAIT_RESULT, req, waitMs = 3000)
            if (resp != null) {
                val cr = Dji.parseConnectResponse(resp.payload)
                if (cr != null && cr.retCode != 0) {
                    patchUi { it.copy(errorMsg = "相机拒绝连接 ret=${cr.retCode}", pairingCode = -1) }
                    gatt?.disconnect()
                    return
                }
            } else if (!cameraRequestSeen) {
                // 相机可能跳过应答帧直接发它的连接请求，再等一会儿
                val ok = withTimeoutOrNull(8000) { while (!cameraRequestSeen) delay(100); true }
                if (ok != true) {
                    patchUi { it.copy(errorMsg = "相机无响应，请确认相机屏幕亮起且未连接其他设备", pairingCode = -1) }
                    gatt?.disconnect()
                    return
                }
            }
        } catch (e: Exception) {
            patchUi { it.copy(errorMsg = "握手异常: ${e.message}") }
            gatt?.disconnect()
        }
    }

    // ---------------- 指令 ----------------

    suspend fun sendCommand(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        payload: ByteArray = ByteArray(0),
        waitMs: Long = 2000,
    ): DjiFrame.Parsed? {
        if (cmdType == DjiFrame.CMD_NO_RESPONSE || cmdType == DjiFrame.ACK_NO_RESPONSE) {
            writeBytes(DjiFrame.build(cmdSet, cmdId, cmdType, payload, nextSeq()))
            return null
        }
        val seq = nextSeq()
        val waiter = CompletableDeferred<DjiFrame.Parsed>()
        waiters[seq] = waiter
        val sent = writeBytes(DjiFrame.build(cmdSet, cmdId, cmdType, payload, seq))
        if (!sent) {
            waiters.remove(seq)
            return null
        }
        return try { withTimeoutOrNull(waitMs) { waiter.await() } } finally { waiters.remove(seq) }
    }

    /** 快门：拍录键短按事件。相机在拍照模式下拍一张；视频模式下开始/停止录像。 */
    suspend fun takePhoto(): Boolean =
        writeBytes(DjiFrame.build(Dji.CMD_SET_GENERAL, Dji.CMD_KEY_REPORT, DjiFrame.CMD_RESPONSE_OR_NOT, Dji.keyReportShutter(), nextSeq()))

    /** 切换相机模式 */
    suspend fun switchMode(mode: Int): Boolean {
        val r = sendCommand(Dji.CMD_SET_CAMERA, Dji.CMD_MODE_SWITCH, DjiFrame.CMD_RESPONSE_OR_NOT, Dji.modeSwitch(identity.deviceId, mode))
        return r != null
    }

    /** 录像开始/停止 */
    suspend fun recordControl(start: Boolean): Boolean {
        val r = sendCommand(Dji.CMD_SET_CAMERA, Dji.CMD_RECORD_CONTROL, DjiFrame.CMD_RESPONSE_OR_NOT, Dji.recordControl(identity.deviceId, start))
        return r != null
    }
}

/**
 * 遥控端（手机）在协议中的持久身份：随机 device_id + 6 字节协议 MAC + 已配对相机列表。
 * 首次运行随机生成并持久化，之后保持不变，这样相机把它当作同一个遥控器，无需重复配对。
 */
class RemoteIdentity(private val prefs: SharedPreferences) {

    val deviceId: Long = prefs.getLong("device_id", 0L).let {
        if (it == 0L) {
            val v = (1L..0xFFFFFFL).random()
            prefs.edit().putLong("device_id", v).apply()
            v
        } else it
    }

    val macBytes: ByteArray = prefs.getString("proto_mac", null)
        ?.split(":")
        ?.mapNotNull { it.toIntOrNull(16)?.toByte() }
        ?.takeIf { it.size == 6 }
        ?.toByteArray()
        ?: run {
            val b = ByteArray(6) { (0..255).random().toByte() }
            prefs.edit().putString("proto_mac", b.joinToString(":") { "%02X".format(it) }).apply()
            b
        }

    fun isPaired(mac: String): Boolean = prefs.getStringSet("paired", emptySet())?.contains(mac) == true

    fun markPaired(mac: String) {
        val s = prefs.getStringSet("paired", emptySet())?.toMutableSet() ?: mutableSetOf()
        s.add(mac)
        prefs.edit().putStringSet("paired", s).apply()
    }
}
