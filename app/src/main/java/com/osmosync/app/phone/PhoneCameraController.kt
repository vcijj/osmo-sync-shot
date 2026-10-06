package com.osmosync.app.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 手机自身相机（CameraX）：定时连拍时与 BLE 快门同步拍照，
 * 照片保存到 DCIM/OsmoSync。
 */
class PhoneCameraController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    @Volatile private var attachedView: androidx.camera.view.PreviewView? = null
    private val captureMutex = Mutex()

    /** UI 注册预览控件；若相机已绑定则立即接上画面 */
    fun attachPreviewView(view: androidx.camera.view.PreviewView) {
        attachedView = view
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    fun detachPreviewView() {
        attachedView = null
    }

    val backCameraId: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    @OptIn(ExperimentalGetImage::class)
    fun bind(lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(), onReady: ((Boolean) -> Unit)? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val pv = Preview.Builder().build()
                preview = pv
                imageCapture = ic
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                attachedView?.let { pv.setSurfaceProvider(it.surfaceProvider) }
                onReady?.invoke(true)
            } catch (e: Exception) {
                imageCapture = null
                onReady?.invoke(false)
            }
        }, mainExecutor())
    }

    fun unbind() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        imageCapture = null
        preview = null
    }

    fun mainExecutor(): Executor = androidx.core.content.ContextCompat.getMainExecutor(context)

    /** 拍一张并保存到 MediaStore，返回保存的 Uri；失败抛异常。 */
    suspend fun takePhoto(): Uri = captureMutex.withLock {
        val ic = imageCapture ?: error("手机相机未就绪")
        val name = "PHONE_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/OsmoSync")
            }
        }
        var builder = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cv,
        )
        // 有定位时写入 GPS EXIF
        gps?.toLocation()?.let { loc ->
            val metadata = ImageCapture.Metadata().apply { location = loc }
            builder = builder.setMetadata(metadata)
        }
        val options = builder.build()
        suspendCancellableCoroutine { cont ->
            ic.takePicture(
                options,
                mainExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cont.resume(outputFileResults.savedUri ?: Uri.EMPTY)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    /** 注入 GPS 提供者（可选；拍照时若有定位则写入 EXIF） */
    @Volatile
    var gps: com.osmosync.app.gps.GpsProvider? = null

    fun attachGps(provider: com.osmosync.app.gps.GpsProvider) {
        gps = provider
    }
}
