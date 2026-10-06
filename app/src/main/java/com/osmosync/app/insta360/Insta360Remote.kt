package com.osmosync.app.insta360

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 影石 Insta360 支持：把手机模拟成 "Insta360 GPS Remote" 蓝牙遥控器。
 *
 * 与大疆相反，影石相机是 BLE 主设备（中心），遥控器是外设：
 * 1. 手机作为 GATT Server 提供服务 0xCE80（CE81 写 / CE82 通知 / CE83 读）
 *    和识别服务 D0FF-3C17-D293-8E48-14FE2E4DA212（FFD1..FFE0）
 * 2. 手机以 "Insta360 GPS Remote" 名称广播；用户在相机的蓝牙菜单里
 *    选择连接遥控器后，相机会主动连接手机
 * 3. 指令为对 CE82 的 9 字节通知：FC EF FE 86 00 03 01 [键位] [事件]
 *    快门=02 00（拍照模式拍一张/视频模式开始停止录像）
 *    切模式=01 00、息屏=00 00、关机=00 03
 *
 * 协议来源：社区逆向工程（pchwalek/insta360_ble_esp32，MIT；X3/ONE RS 实测，
 * 使用 GPS 遥控器的 X4 等新机型理论上兼容）。
 */
class Insta360Remote(private val context: Context) {

    data class Insta360Cam(val mac: String, val name: String, val subscribed: Boolean)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _cams = MutableStateFlow<List<Insta360Cam>>(emptyList())
    val cams: StateFlow<List<Insta360Cam>> = _cams

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private var gattServer: BluetoothGattServer? = null
    private var ce82: BluetoothGattCharacteristic? = null
    private var oldName: String? = null
    private var advertiseCallback: android.bluetooth.le.AdvertiseCallback? = null

    private val connected = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribed = ConcurrentHashMap<String, Boolean>()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ce80-0000-1000-8000-00805f9b34fb")
        val CE81_UUID: UUID = UUID.fromString("0000ce81-0000-1000-8000-00805f9b34fb")
        val CE82_UUID: UUID = UUID.fromString("0000ce82-0000-1000-8000-00805f9b34fb")
        val CE83_UUID: UUID = UUID.fromString("0000ce83-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val REMOTE_NAME = "Insta360 GPS Remote"

        // 相机用于识别遥控器的二级服务
        val SERVICE2_UUID: UUID = UUID.fromString("0000d0ff-3c17-d293-8e48-14fe2e4da212")
        val FFD1_UUID: UUID = UUID.fromString("0000ffd1-3c17-d293-8e48-14fe2e4da212")
        val FFD2_UUID: UUID = UUID.fromString("0000ffd2-3c17-d293-8e48-14fe2e4da212")
        val FFD3_UUID: UUID = UUID.fromString("0000ffd3-3c17-d293-8e48-14fe2e4da212")
        val FFD4_UUID: UUID = UUID.fromString("0000ffd4-3c17-d293-8e48-14fe2e4da212")
        val FFD5_UUID: UUID = UUID.fromString("0000ffd5-3c17-d293-8e48-14fe2e4da212")
        val FFD8_UUID: UUID = UUID.fromString("0000ffd8-3c17-d293-8e48-14fe2e4da212")
        val FFF1_UUID: UUID = UUID.fromString("0000fff1-3c17-d293-8e48-14fe2e4da212")
        val FFF2_UUID: UUID = UUID.fromString("0000fff2-3c17-d293-8e48-14fe2e4da212")
        val FFE0_UUID: UUID = UUID.fromString("0000ffe0-3c17-d293-8e48-14fe2e4da212")

        private val CMD_SCREEN = byteArrayOf(0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x00, 0x00)
        private val CMD_POWER_OFF = byteArrayOf(0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x00, 0x03)
        private val CMD_MODE = byteArrayOf(0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x01, 0x00)
        private val CMD_SHUTTER = byteArrayOf(0xFC.toByte(), 0xEF.toByte(), 0xFE.toByte(), 0x86.toByte(), 0x00, 0x03, 0x01, 0x02, 0x00)
    }

    private fun publish() {
        _cams.value = connected.keys.map { mac ->
            Insta360Cam(mac, connected[mac]?.name ?: "Insta360", subscribed[mac] == true)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            val mac = device.address
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected[mac] = device
                _status.value = "相机已连接：${device.name ?: mac}（在相机上完成配对确认后即可遥控）"
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connected.remove(mac)
                subscribed.remove(mac)
            }
            publish()
            // 继续广播，允许多台相机依次接入
            startAdvertising()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int,
            characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // 相机写 CE81（状态/回执），v1 忽略内容仅应答
            if (responseNeeded && device != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null) return
            val value = when (characteristic?.uuid) {
                CE83_UUID -> byteArrayOf(0x01, 0x02) // uint16 0x0201
                FFD3_UUID -> byteArrayOf(0x01, 0x90.toByte(), 0x1E, 0x30) // 0x301E9001
                FFD4_UUID -> byteArrayOf(0x01, 0x20, 0x00, 0x18) // 0x18002001
                else -> ByteArray(0)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor?.uuid == CCCD_UUID && device != null) {
                subscribed[device.address] = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                publish()
            }
            if (responseNeeded && device != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // ---------------- 启停 ----------------

    fun start() {
        if (_enabled.value) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: run {
            _status.value = "本机不支持蓝牙"
            return
        }
        val adapter = manager.adapter ?: run {
            _status.value = "本机不支持蓝牙"
            return
        }
        try {
            oldName = adapter.name
            if (oldName != REMOTE_NAME) adapter.name = REMOTE_NAME
        } catch (_: Exception) {}

        gattServer = manager.openGattServer(context, serverCallback)?.apply {
            val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(CE81_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                ce82 = BluetoothGattCharacteristic(CE82_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
                    addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ))
                }
                addCharacteristic(ce82!!)
                addCharacteristic(BluetoothGattCharacteristic(CE83_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ).apply { setValue(byteArrayOf(0x01, 0x02)) })
            }
            addService(svc)

            val svc2 = BluetoothGattService(SERVICE2_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(FFD1_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                addCharacteristic(BluetoothGattCharacteristic(FFD2_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(FFD3_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ).apply { setValue(byteArrayOf(0x01, 0x90.toByte(), 0x1E, 0x30)) })
                addCharacteristic(BluetoothGattCharacteristic(FFD4_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ).apply { setValue(byteArrayOf(0x01, 0x20, 0x00, 0x18)) })
                addCharacteristic(BluetoothGattCharacteristic(FFD5_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(FFD8_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                addCharacteristic(BluetoothGattCharacteristic(FFF1_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                addCharacteristic(BluetoothGattCharacteristic(FFF2_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                addCharacteristic(BluetoothGattCharacteristic(FFE0_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            }
            addService(svc2)
        }
        if (gattServer == null) {
            _status.value = "GATT 服务启动失败（重启蓝牙后重试）"
            return
        }
        _enabled.value = true
        startAdvertising()
        _status.value = "遥控器已就绪：请在相机的蓝牙/遥控器连接菜单中选择 \"${REMOTE_NAME}\""
    }

    fun stop() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        stopAdvertising()
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            oldName?.let { if (it != REMOTE_NAME) adapter?.name = it }
        } catch (_: Exception) {}
        oldName = null
        connected.clear()
        subscribed.clear()
        publish()
        _enabled.value = false
        _status.value = ""
    }

    private fun startAdvertising() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            _status.value = "本机不支持蓝牙广播"
            return
        }
        stopAdvertising()
        val settings = android.bluetooth.le.AdvertiseSettings.Builder()
            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = android.bluetooth.le.AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val cb = object : android.bluetooth.le.AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings?) {}
            override fun onStartFailure(errorCode: Int) {
                _status.value = "广播启动失败（错误码 $errorCode）"
            }
        }
        advertiseCallback = cb
        try {
            advertiser.startAdvertising(settings, data, cb)
        } catch (e: Exception) {
            _status.value = "广播启动失败: ${e.message}"
        }
    }

    private fun stopAdvertising() {
        advertiseCallback?.let { cb ->
            try {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            } catch (_: Exception) {}
        }
        advertiseCallback = null
    }

    // ---------------- 指令 ----------------

    private fun notifyAll(cmd: ByteArray) {
        val server = gattServer ?: return
        val ch = ce82 ?: return
        connected.values.forEach { device ->
            try {
                ch.value = cmd
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, ch, false)
            } catch (_: Exception) {}
        }
    }

    /** 快门：拍照模式拍一张 / 视频模式开始或停止录像 */
    fun shutter() = notifyAll(CMD_SHUTTER)

    /** 切换拍摄模式 */
    fun switchMode() = notifyAll(CMD_MODE)

    /** 相机屏幕亮/灭 */
    fun toggleScreen() = notifyAll(CMD_SCREEN)

    /** 相机关机 */
    fun powerOff() = notifyAll(CMD_POWER_OFF)
}
