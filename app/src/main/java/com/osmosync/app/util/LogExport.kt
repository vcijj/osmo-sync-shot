package com.osmosync.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.osmosync.app.shooter.ShotLogEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍摄记录导出：JSON 清单，含每次触发的精确时间戳与各机成功情况。
 * 蓝牙协议不支持从相机回传照片，导出的时间戳清单用于多机位素材按时间对齐。
 */
object LogExport {

    fun export(context: Context, logs: List<ShotLogEntry>): String {
        val arr = JSONArray()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (log in logs) {
            val o = JSONObject()
            o.put("shot", log.index)
            o.put("timeMs", log.timeMs)
            o.put("time", sdf.format(Date(log.timeMs)))
            val cams = JSONArray()
            for (r in log.cameraResults) {
                val c = JSONObject()
                c.put("model", r.model)
                c.put("mac", r.mac)
                c.put("ok", r.ok)
                c.put("detail", r.detail)
                cams.put(c)
            }
            o.put("cameras", cams)
            o.put("phoneSaved", log.phoneSaved)
            o.put("phoneDetail", log.phoneDetail)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("app", "osmo-sync-shot")
        root.put("exportedAt", sdf.format(Date()))
        root.put("entries", arr)
        val name = "osmosync-log-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"

        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OsmoSync")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                "已保存: Download/OsmoSync/$name"
            } else {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val f = File(dir, name)
                f.writeText(root.toString(2))
                "已保存: ${f.absolutePath}"
            }
        } catch (e: Exception) {
            "导出失败: ${e.message}"
        }
    }
}
