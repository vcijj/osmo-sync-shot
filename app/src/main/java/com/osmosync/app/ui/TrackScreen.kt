package com.osmosync.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.osmosync.app.App
import com.osmosync.app.gps.OrientationProvider
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackScreen(modifier: Modifier = Modifier) {
    val app = App.instance
    val context = LocalContext.current
    val points by app.trackStore.points.collectAsState()
    val fix by app.gps.fix.collectAsState()
    val azimuth by app.orientation.azimuth.collectAsState()
    val gpsPushing by app.cameraManager.gpsPushing.collectAsState()

    var useAmap by remember { mutableStateOf(true) } // 默认高德瓦片（国内可用），可切 OSM
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }

    val shotPoints = points.filter { it.isShot }

    Column(modifier = modifier.fillMaxSize()) {

        // ---- 摘要与控制 ----
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "拍摄点 ${shotPoints.size} · 轨迹点 ${points.size}",
                            fontWeight = FontWeight.Bold,
                        )
                        val az = azimuth
                        Text(
                            buildString {
                                append("当前朝向: ${OrientationProvider.cardinal(az)}")
                                if (fix != null) append(" · 卫星 ${fix!!.satellites}")
                                if (!gpsPushing) {
                                    append("（GPS 开关未开启，请在\"相机管理\"页打开）")
                                }
                            },
                            fontSize = 12.sp, color = Color.Gray,
                        )
                    }
                    OutlinedButton(onClick = {
                        mapRef?.let { map ->
                            if (useAmap) map.setTileSource(amapTileSource()) else map.setTileSource(TileSourceFactory.MAPNIK)
                        }
                        useAmap = !useAmap
                        Toast.makeText(context, if (useAmap) "高德瓦片（火星坐标已转换）" else "OpenStreetMap", Toast.LENGTH_SHORT).show()
                    }) { Text(if (useAmap) "高德" else "OSM") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val f = fix
                        if (f != null) mapRef?.controller?.animateTo(GeoPoint(f.latitude, f.longitude))
                    }) { Text("回到当前位置") }
                    OutlinedButton(onClick = { showClear = true }) { Text("清空轨迹") }
                }
            }
        }

        // ---- 地图（严格限制在剩余空间内，不越界） ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clipToBounds(),
        ) {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().apply {
                        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
                        userAgentValue = ctx.packageName
                    }
                    MapView(ctx).apply {
                        setTileSource(amapTileSource())
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(16.0)
                    }.also { mapRef = it }
                },
                update = { map -> rebuildOverlays(map, points, useAmap) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // 首次有定位点时居中
    LaunchedEffect(points.isNotEmpty()) {
        if (!centeredOnce && points.isNotEmpty()) {
            val last = points.last()
            mapRef?.controller?.setCenter(GeoPoint(last.lat, last.lon))
            centeredOnce = true
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空轨迹") },
            text = { Text("将删除全部 ${points.size} 个轨迹点（含 ${shotPoints.size} 个拍摄点），此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    app.trackStore.clear()
                    showClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}

private fun rebuildOverlays(map: MapView, points: List<com.osmosync.app.track.TrackPoint>, useAmap: Boolean) {
    map.overlays.clear()
    val toGeo: (com.osmosync.app.track.TrackPoint) -> GeoPoint = { p ->
        if (useAmap) {
            val (la, lo) = com.osmosync.app.track.GeoConv.wgs84ToGcj02(p.lat, p.lon)
            GeoPoint(la, lo)
        } else GeoPoint(p.lat, p.lon)
    }

    // 轨迹线
    if (points.size >= 2) {
        val line = Polyline()
        line.setPoints(points.map { toGeo(it) })
        line.outlinePaint.color = 0xFF2E6DE6.toInt()
        line.outlinePaint.strokeWidth = 9f
        map.overlays.add(line)
    }

    // 拍摄点：箭头指向拍摄时的朝向
    points.filter { it.isShot }.forEach { p ->
        val marker = Marker(map).apply {
            position = toGeo(p)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(map.context.resources, arrowBitmap(0xFF1B873B.toInt()))
            rotation = p.headingDeg ?: 0f
            title = (if (p.shotIndex != null && p.shotIndex > 0) "第 ${p.shotIndex} 张" else "手动拍摄") +
                    " · " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(p.timeMs))
            snippet = "朝向 ${OrientationProvider.cardinal(p.headingDeg)}\n" +
                    String.format(Locale.US, "%.6f, %.6f · 海拔 %.0fm", p.lat, p.lon, p.altitudeM)
        }
        map.overlays.add(marker)
    }
    map.invalidate()
}

private fun amapTileSource(): XYTileSource =
    XYTileSource(
        "高德",
        3, 19, 256, ".png",
        arrayOf(
            "https://wprd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x={x}&y={y}&z={z}",
            "https://wprd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x={x}&y={y}&z={z}",
        ),
    )

private fun arrowBitmap(color: Int, sizePx: Int = 44): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val w = sizePx.toFloat()
    val path = Path().apply {
        moveTo(w / 2f, w * 0.06f)
        lineTo(w * 0.84f, w * 0.88f)
        lineTo(w / 2f, w * 0.70f)
        lineTo(w * 0.16f, w * 0.88f)
        close()
    }
    canvas.drawPath(path, paint)
    return bmp
}
