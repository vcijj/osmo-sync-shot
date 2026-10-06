package com.osmosync.app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 一次 GPS 定位结果（协议字段所需的全部信息） */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val speedMS: Float,
    val bearingDeg: Float,
    val accuracyM: Float,
    val satellites: Int,
    val timeMs: Long,
)

/**
 * 手机 GPS 定位源：官方 GPS 遥控器的角色——把手机定位按需推送给相机，
 * 同时供手机拍照写入 EXIF。
 */
@SuppressLint("MissingPermission")
class GpsProvider(private val context: Context) {

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    @Volatile
    var running: Boolean = false
        private set

    private var lm: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var satelliteCount = 0

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { update(location) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun update(location: Location) {
        val f = GpsFix(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else 0.0,
            speedMS = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 9999f,
            satellites = satelliteCount,
            timeMs = location.time,
        )
        _fix.value = f
        _status.value = "卫星 ${f.satellites} · 精度 ${"%.0f".format(f.accuracyM)}m · ${"%.6f, %.6f".format(f.latitude, f.longitude)}"
    }

    fun start() {
        if (running) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            _status.value = "设备无定位服务"
            return
        }
        lm = manager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _status.value = "请先打开系统定位服务（GPS）"
        } else {
            _status.value = "等待 GPS 定位..."
        }
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper(),
                )
            }
            // 网络定位做补充，室内也能有个粗略位置
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper(),
                )
            }
        } catch (e: Exception) {
            _status.value = "定位启动失败: ${e.message}"
            return
        }
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var n = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.getCn0DbHz(i) > 0) n++
                }
                satelliteCount = n
            }
        }.also {
            try {
                manager.registerGnssStatusCallback(it, android.os.Handler(Looper.getMainLooper()))
            } catch (_: Exception) {}
        }
        running = true
    }

    fun stop() {
        running = false
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        gnssCallback?.let { try { lm?.unregisterGnssStatusCallback(it) } catch (_: Exception) {} }
        gnssCallback = null
        lm = null
        satelliteCount = 0
        _fix.value = null
        _status.value = ""
    }

    /** 供 CameraX 写 EXIF 用的 android.location.Location（无定位时返回 null） */
    fun toLocation(): Location? {
        val f = _fix.value ?: return null
        return Location("osmosync").apply {
            latitude = f.latitude
            longitude = f.longitude
            altitude = f.altitudeM
            accuracy = f.accuracyM
            speed = f.speedMS
            bearing = f.bearingDeg
            time = f.timeMs
        }
    }
}
