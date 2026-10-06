package com.osmosync.app.shooter

import com.osmosync.app.ble.CameraManager
import com.osmosync.app.ble.ShotResult
import com.osmosync.app.phone.PhoneCameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ShootConfig(
    val intervalSeconds: Double = 5.0,   // 拍摄间隔
    val totalShots: Int = 0,             // 总张数，0 = 不限
    val startDelaySeconds: Int = 0,      // 倒计时开始
    val startAtClock: String = "",       // 定时开始 HH:mm（当天，已过则明天）
    val phoneCapture: Boolean = true,    // 手机同步拍照
    val switchToPhotoFirst: Boolean = false, // 开始前把所有相机切到拍照模式
)

data class ShotLogEntry(
    val index: Int,
    val timeMs: Long,
    val cameraResults: List<ShotResult>,
    val phoneSaved: Boolean,
    val phoneDetail: String,
)

sealed class ShooterState {
    data object Idle : ShooterState()
    data class Running(
        val config: ShootConfig,
        val startedAtMs: Long,
        val actualStartAtMs: Long,
        val currentShot: Int,
        val totalShots: Int,
        val nextShotAtMs: Long,
    ) : ShooterState()
}

/**
 * 定时连拍调度器：按设定的间隔向所有已连接的大疆相机群发快门，
 * 同时（可选）触发手机相机同步拍一张。
 */
class IntervalShooter(
    private val cameraManager: CameraManager,
    private val phone: PhoneCameraController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<ShooterState>(ShooterState.Idle)
    val state: StateFlow<ShooterState> = _state

    private val _logs = MutableStateFlow<List<ShotLogEntry>>(emptyList())
    val logs: StateFlow<List<ShotLogEntry>> = _logs

    val isRunning: Boolean get() = job?.isActive == true

    /** 计算实际开始时间戳；返回 null 表示开始时刻格式错误 */
    fun computeStartAt(config: ShootConfig): Long? {
        var start = System.currentTimeMillis() + config.startDelaySeconds * 1000L
        val clock = config.startAtClock.trim()
        if (clock.isNotEmpty()) {
            val m = Regex("(\\d{1,2}):(\\d{2})").find(clock) ?: return null
            val hh = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            if (hh !in 0..23 || mm !in 0..59) return null
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
            start = cal.timeInMillis
        }
        return start
    }

    fun start(config: ShootConfig) {
        if (isRunning) return
        val startAt = computeStartAt(config) ?: return
        _logs.value = emptyList()
        job = scope.launch {
            // 可选：先统一切到拍照模式
            if (config.switchToPhotoFirst) {
                cameraManager.switchAllToPhoto()
                delay(800)
            }
            val running = ShooterState.Running(
                config = config,
                startedAtMs = System.currentTimeMillis(),
                actualStartAtMs = startAt,
                currentShot = 0,
                totalShots = config.totalShots,
                nextShotAtMs = startAt,
            )
            _state.value = running

            // 等待开始时刻
            while (isActive && System.currentTimeMillis() < startAt) {
                _state.value = running.copy(nextShotAtMs = startAt)
                delay(200)
            }

            var i = 0
            val intervalMs = (config.intervalSeconds * 1000).toLong().coerceAtLeast(200)
            while (isActive && (config.totalShots == 0 || i < config.totalShots)) {
                if (i > 0) {
                    val next = System.currentTimeMillis() + intervalMs
                    _state.value = _state.value.let { s ->
                        if (s is ShooterState.Running) s.copy(nextShotAtMs = next) else s
                    }
                    delay(intervalMs)
                }
                i++
                _state.value = _state.value.let { s ->
                    if (s is ShooterState.Running) s.copy(currentShot = i) else s
                }
                fireOnce(i, config)
            }
            stop()
        }
    }

    suspend fun fireOnce(index: Int, config: ShootConfig) {
        val t0 = System.currentTimeMillis()
        // 先群发相机快门（BLE 写入毫秒级），再触发手机拍照
        val camResults = withContext(Dispatchers.IO) { cameraManager.shutterAll() }
        var phoneSaved = false
        var phoneDetail = "未启用"
        if (config.phoneCapture) {
            try {
                phone.takePhoto()
                phoneSaved = true
                phoneDetail = "已保存到 DCIM/OsmoSync"
            } catch (e: Exception) {
                phoneDetail = "手机拍照失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val entry = ShotLogEntry(
            index = index,
            timeMs = t0,
            cameraResults = camResults,
            phoneSaved = phoneSaved,
            phoneDetail = phoneDetail,
        )
        _logs.value = (_logs.value + entry).takeLast(200)
    }

    fun stop() {
        val j = job
        job = null
        j?.cancel()
        _state.value = ShooterState.Idle
    }
}
