package com.osmosync.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import kotlinx.coroutines.delay

/**
 * 休眠相机广播唤醒。
 *
 * 原理（官方 Demo ble.c）：相机休眠后仍扫描周围广播，收到厂商字段形如
 * "WKP" + 自己反序 MAC 的广播包即被唤醒。原始 AD 结构为
 * `[10, 0xFF, 'W','K','P', mac[5..0]]`，其中 'W''K' 两个字节按 BLE 规范充当
 * Company ID（0x4B57，小端 57 4B），Android 的 AdvertiseData 会自动补上，
 * 因此 addManufacturerData 的数据为 ['P', mac[5], mac[4], ..., mac[0]]。
 */
object WakeAdvertiser {

    const val WAKE_COMPANY_ID = 0x4B57

    private fun parseMac(mac: String): ByteArray? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        return try {
            parts.map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun wakePayload(mac: String): ByteArray? {
        val b = parseMac(mac) ?: return null
        val data = ByteArray(7)
        data[0] = 'P'.code.toByte()
        for (i in 0 until 6) data[1 + i] = b[5 - i]
        return data
    }

    /**
     * 依次对每台相机广播唤醒包（广播一次约 [perCameraMs] 毫秒）。
     * @return 实际尝试唤醒的相机数量；不支持广播时返回 -1
     */
    suspend fun wake(context: Context, macs: List<String>, perCameraMs: Long = 2500): Int {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return -1
        val advertiser = adapter.bluetoothLeAdvertiser ?: return -1
        var count = 0
        for (mac in macs) {
            val payload = wakePayload(mac) ?: continue
            advertiseOnce(advertiser, payload, perCameraMs)
            count++
        }
        return count
    }

    private suspend fun advertiseOnce(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        payload: ByteArray,
        durationMs: Long,
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(WAKE_COMPANY_ID, payload)
            .build()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { /* 保持广播 */ }
            override fun onStartFailure(errorCode: Int) { done.complete(Unit) }
        }
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (_: Exception) {
            return
        }
        delay(durationMs)
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        done.complete(Unit)
        // 广播间隔稍作停顿，避免连续开关广播被系统限流
        delay(300)
    }
}
