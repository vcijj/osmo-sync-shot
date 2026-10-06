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
     * 依次对每台相机广播唤醒包。
     * 安卓广播间隔较慢（LOW_LATENCY≈100ms，ESP 为 20-60ms），而休眠相机用
     * 低功耗占空比扫描，容易错过，因此每台相机重复 [rounds] 轮、每轮 [perCameraMs] 毫秒。
     *
     * 注意必须使用**可连接广播**（setConnectable(true)），与官方 Demo 的
     * ADV_TYPE_IND 一致；休眠相机会过滤不可连接广播。
     *
     * @return 成功广播的相机数量（失败原因见 lastErrorCode，0 表示无错误）
     */
    suspend fun wake(
        context: Context,
        macs: List<String>,
        perCameraMs: Long = 3000,
        rounds: Int = 3,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): WakeResult {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return WakeResult(0, macs.size, -100)
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return WakeResult(0, macs.size, -101)
        var okCount = 0
        var failCount = 0
        var lastErrorCode = 0
        macs.forEachIndexed { index, mac ->
            val payload = wakePayload(mac)
            if (payload == null) {
                failCount++
                return@forEachIndexed
            }
            var anyOk = false
            repeat(rounds) {
                val err = advertiseOnce(advertiser, payload, perCameraMs)
                if (err == 0) anyOk = true else lastErrorCode = err
            }
            if (anyOk) okCount++ else failCount++
            onProgress?.invoke(index + 1, macs.size)
        }
        return WakeResult(okCount, failCount, lastErrorCode)
    }

    data class WakeResult(val okCount: Int, val failCount: Int, val lastErrorCode: Int)

    /** @return 0=广播正常发送；否则为 AdvertiseCallback 错误码或 -1（异常） */
    private suspend fun advertiseOnce(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        payload: ByteArray,
        durationMs: Long,
    ): Int {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // 官方 Demo 为 ADV_TYPE_IND（可连接广播），不可连接广播会被休眠相机过滤
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(WAKE_COMPANY_ID, payload)
            .build()
        val failureCode = java.util.concurrent.atomic.AtomicInteger(0)
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { /* 持续广播到时长结束 */ }
            override fun onStartFailure(code: Int) {
                failureCode.compareAndSet(0, code)
            }
        }
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
            return -102
        } catch (_: Exception) {
            return -1
        }
        delay(durationMs)
        try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        // 广播间隔稍作停顿，避免连续开关广播被系统限流
        delay(300)
        return failureCode.get()
    }
}
