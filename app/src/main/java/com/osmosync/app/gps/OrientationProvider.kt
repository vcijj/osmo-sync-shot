package com.osmosync.app.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 手机朝向（罗盘方位角）：优先用旋转向量传感器，退化为方向传感器。
 * azimuth 0°=北，顺时针增加；显示为中文八方位 + 度数。
 */
class OrientationProvider(context: Context) {

    private val _azimuth = MutableStateFlow<Float?>(null)
    val azimuth: StateFlow<Float?> = _azimuth

    @Volatile
    var running: Boolean = false
        private set

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientationVec = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationVec)
                    var deg = Math.toDegrees(orientationVec[0].toDouble())
                    deg = (deg + 360.0) % 360.0
                    _azimuth.value = deg.toFloat()
                }
                @Suppress("DEPRECATION")
                Sensor.TYPE_ORIENTATION -> {
                    _azimuth.value = ((event.values[0] % 360f) + 360f) % 360f
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (running || sm == null) return
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val sensor = rv ?: sm.getDefaultSensor(Sensor.TYPE_ORIENTATION) ?: run {
            _azimuth.value = null
            return
        }
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            running = true
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { sm?.unregisterListener(listener) } catch (_: Exception) {}
        _azimuth.value = null
    }

    companion object {
        private val NAMES = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

        /** 0..360 度 → "东北 45°" 风格的文本 */
        fun cardinal(deg: Float?): String {
            deg ?: return "无"
            val idx = ((deg + 22.5f) / 45f).toInt() % 8
            return "${NAMES[idx]} ${deg.toInt()}°"
        }
    }
}
