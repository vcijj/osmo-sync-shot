package com.osmosync.app.importer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相机照片导入：通过 SAF 读取 USB OTG 连接的相机存储（或 SD 读卡器），
 * 扫描 DJI_ 前缀的 JPG 照片并复制到手机相册 DCIM/OsmoSync/Camera。
 * 支持按连拍会话时间范围筛选、按文件名去重。
 */
object CameraImporter {

    data class ImportState(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val message: String = "",
    )

    private val _state = MutableStateFlow<ImportState?>(null)
    val state: StateFlow<ImportState?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val RELATIVE_DIR = "DCIM/OsmoSync/Camera"
    private const val MAX_FILES = 2000
    private const val MAX_DEPTH = 4

    private class Doc(val uri: Uri, val name: String, val lastModified: Long)

    /** 上次选择的存储树，便于一键重新导入 */
    fun lastTreeUri(context: Context): Uri? =
        context.getSharedPreferences("osmosync", Context.MODE_PRIVATE)
            .getString("import_tree_uri", null)?.let { Uri.parse(it) }

    fun start(
        context: Context,
        treeUri: Uri,
        onlySession: Boolean,
        sessionRange: Pair<Long, Long>?,
    ) {
        if (_state.value?.running == true) return
        _state.value = ImportState(running = true, message = "扫描相机存储...")
        scope.launch {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                context.getSharedPreferences("osmosync", Context.MODE_PRIVATE)
                    .edit().putString("import_tree_uri", treeUri.toString()).apply()

                _state.value = ImportState(running = true, message = "扫描 DJI 照片...")
                val all = mutableListOf<Doc>()
                scan(context, treeUri, all, depth = 0)
                var list: List<Doc> = all
                if (onlySession && sessionRange != null) {
                    // 相机时钟可能略有偏差，放宽前后 5 分钟
                    list = all.filter {
                        it.lastModified >= sessionRange.first - 300_000L &&
                                it.lastModified <= sessionRange.second + 300_000L
                    }
                }
                if (list.isEmpty()) {
                    _state.value = ImportState(false, 0, 0, "未找到 DJI 照片（JPG），可尝试关闭时间筛选")
                    return@launch
                }
                var ok = 0
                var skipped = 0
                list.forEachIndexed { i, doc ->
                    when {
                        exists(context, doc.name) -> skipped++
                        importOne(context, doc) -> ok++
                        else -> skipped++
                    }
                    _state.value = ImportState(true, list.size, i + 1, "导入中 ${i + 1}/${list.size}")
                }
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                _state.value = ImportState(
                    false, list.size, ok,
                    "[$time] 导入完成：成功 $ok · 跳过 $skipped（已存在/失败），保存到 $RELATIVE_DIR",
                )
            } catch (e: Exception) {
                _state.value = ImportState(false, 0, 0, "导入失败: ${e.message}")
            }
        }
    }

    private fun isDjiPhoto(name: String): Boolean {
        val n = name.uppercase(Locale.US)
        return n.startsWith("DJI_") && (n.endsWith(".JPG") || n.endsWith(".JPEG"))
    }

    private fun scan(context: Context, treeUri: Uri, out: MutableList<Doc>, depth: Int) {
        if (out.size >= MAX_FILES || depth > MAX_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(treeUri),
        )
        val dirs = mutableListOf<Uri>()
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs.add(docUri)
                    } else if (isDjiPhoto(name)) {
                        out.add(Doc(docUri, name, c.getLong(3)))
                    }
                }
            }
        } catch (_: Exception) {
            return
        }
        dirs.forEach { scan(context, it, out, depth + 1) }
    }

    private fun exists(context: Context, name: String): Boolean {
        return try {
            val sel = StringBuilder("${MediaStore.Images.Media.DISPLAY_NAME} = ?")
            val args = mutableListOf(name)
            if (Build.VERSION.SDK_INT >= 29) {
                sel.append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?")
                args.add("$RELATIVE_DIR/")
            }
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                sel.toString(), args.toTypedArray(), null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun importOne(context: Context, doc: Doc): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, doc.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
                if (doc.lastModified > 0) {
                    put(MediaStore.Images.Media.DATE_TAKEN, doc.lastModified)
                    put(MediaStore.Images.Media.DATE_MODIFIED, doc.lastModified / 1000)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            context.contentResolver.openInputStream(doc.uri)!!.use { input ->
                context.contentResolver.openOutputStream(uri)!!.use { output ->
                    input.copyTo(output)
                }
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
        } else {
            val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "CameraImport")
            dir.mkdirs()
            val out = java.io.File(dir, doc.name)
            context.contentResolver.openInputStream(doc.uri)!!.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
