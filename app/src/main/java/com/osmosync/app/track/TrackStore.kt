package com.osmosync.app.track

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 轨迹点：普通轨迹点（isShot=false）或拍摄触发点（isShot=true，带朝向与照片） */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float?,
    val altitudeM: Double,
    val isShot: Boolean,
    val shotIndex: Int?,
    val photoUri: String?,
)

/**
 * 轨迹存储：内存 StateFlow + JSON 持久化（filesDir/track.json），
 * App 重启后轨迹仍在；可在轨迹页一键清空。
 */
class TrackStore(context: Context) {

    private val file = File(context.filesDir, "track.json")
    private val _points = MutableStateFlow<List<TrackPoint>>(load())
    val points: StateFlow<List<TrackPoint>> = _points

    val shotCount: Int get() = _points.value.count { it.isShot }

    @Volatile private var lastSaveMs = 0L

    fun add(p: TrackPoint) {
        val list = (_points.value + p).takeLast(MAX_POINTS)
        _points.value = list
        val now = System.currentTimeMillis()
        if (p.isShot || now - lastSaveMs > 3000) {
            lastSaveMs = now
            save(list)
        }
    }

    fun clear() {
        _points.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<TrackPoint> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TrackPoint(
                timeMs = o.optLong("t"),
                lat = o.optDouble("la", 0.0),
                lon = o.optDouble("lo", 0.0),
                headingDeg = if (o.has("h")) o.optDouble("h").toFloat() else null,
                altitudeM = o.optDouble("al", 0.0),
                isShot = o.optBoolean("s", false),
                shotIndex = if (o.has("i")) o.optInt("i") else null,
                photoUri = o.optString("p").ifBlank { null },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<TrackPoint>) = try {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("t", p.timeMs)
                    .put("la", p.lat)
                    .put("lo", p.lon)
                    .put("al", p.altitudeM)
                    .put("s", p.isShot)
                    .putOpt("h", p.headingDeg?.toDouble())
                    .putOpt("i", p.shotIndex)
                    .putOpt("p", p.photoUri),
            )
        }
        file.writeText(arr.toString())
    } catch (_: Exception) {
    }

    companion object {
        private const val MAX_POINTS = 8000
    }
}
