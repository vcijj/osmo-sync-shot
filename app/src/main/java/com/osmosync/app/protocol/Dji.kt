package com.osmosync.app.protocol

/**
 * DJI R SDK 指令集（CmdSet, CmdID）的 payload 构造与解析。
 * 字段布局参考官方 Osmo-GPS-Controller-Demo 的 protocol_data_segment_CN.md。
 */
object Dji {

    const val CMD_SET_GENERAL = 0x00
    const val CMD_SET_CAMERA = 0x1D

    // 连接请求 0x0019
    const val CMD_CONNECT = 0x19
    // 按键上报 0x0011
    const val CMD_KEY_REPORT = 0x11
    // 版本查询 0x0000
    const val CMD_VERSION = 0x00
    // 拍录控制 0x1D03
    const val CMD_RECORD_CONTROL = 0x03
    // 统一模式切换 0x1D04
    const val CMD_MODE_SWITCH = 0x04
    // 相机状态订阅 0x1D05
    const val CMD_STATUS_SUBSCRIBE = 0x05
    // 相机状态推送 0x1D02
    const val CMD_STATUS_PUSH = 0x02

    // 相机模式
    const val MODE_SLOW_MOTION = 0x00
    const val MODE_VIDEO = 0x01
    const val MODE_TIMELAPSE = 0x02
    const val MODE_PHOTO = 0x05
    const val MODE_HYPERLAPSE = 0x0A

    // 相机状态 camera_status
    const val CAM_SCREEN_OFF = 0x00
    const val CAM_LIVE = 0x01
    const val CAM_PLAYBACK = 0x02
    const val CAM_SHOOTING = 0x03

    // 电源模式 power_mode
    const val POWER_NORMAL = 0
    const val POWER_SLEEP = 3

    // 按键 key_code
    const val KEY_SHUTTER = 0x01  // 拍录键：拍照模式下拍照 / 视频模式下开始或停止录像
    const val KEY_QS = 0x02       // QS 键：快速切换模式
    const val KEY_SNAPSHOT = 0x03 // 快照键：休眠状态下拍摄，需先广播唤醒

    // 校验模式 verify_mode
    const val VERIFY_RECONNECT = 0  // 已配对，相机按历史记录决定是否弹窗
    const val VERIFY_PAIR = 1       // 首次配对，相机弹窗确认

    // 设备型号（device_id 低 16 位）
    const val MODEL_ACTION_4 = 0xFF33
    const val MODEL_ACTION_5 = 0xFF44
    const val MODEL_ACTION_6 = 0xFF55
    const val MODEL_OSMO_360 = 0xFF66

    fun modelName(deviceId: Long): String = when (((deviceId ushr 16) and 0xFFFF).toInt()) {
        MODEL_ACTION_4 -> "Osmo Action 4"
        MODEL_ACTION_5 -> "Osmo Action 5 Pro"
        MODEL_ACTION_6 -> "Osmo Action 6"
        MODEL_OSMO_360 -> "Osmo 360"
        else -> {
            val low = (deviceId and 0xFFFF).toInt()
            when (low) {
                MODEL_ACTION_4 -> "Osmo Action 4"
                MODEL_ACTION_5 -> "Osmo Action 5 Pro"
                MODEL_ACTION_6 -> "Osmo Action 6"
                MODEL_OSMO_360 -> "Osmo 360"
                else -> "未知型号"
            }
        }
    }

    fun modeName(mode: Int): String = when (mode) {
        MODE_SLOW_MOTION -> "慢动作"
        MODE_VIDEO -> "视频"
        MODE_TIMELAPSE -> "静止延时"
        MODE_PHOTO -> "拍照"
        MODE_HYPERLAPSE -> "运动延时"
        0x1A -> "直播"
        0x23 -> "UVC直播"
        0x28 -> "超级夜景"
        0x34 -> "人物跟随"
        0x38 -> "全景视频"
        0x3F -> "全景拍照"
        else -> "模式0x%02X".format(mode)
    }

    fun statusName(status: Int): String = when (status) {
        CAM_SCREEN_OFF -> "息屏"
        CAM_LIVE -> "待机"
        CAM_PLAYBACK -> "回放"
        CAM_SHOOTING -> "拍摄中"
        0x05 -> "预录制中"
        else -> "状态0x%02X".format(status)
    }

    // ---------- 基础字节序 ----------

    fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun getU16(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)

    fun getU32(src: ByteArray, off: Int): Long =
        (src[off].toLong() and 0xFF) or ((src[off + 1].toLong() and 0xFF) shl 8) or
                ((src[off + 2].toLong() and 0xFF) shl 16) or ((src[off + 3].toLong() and 0xFF) shl 24)

    // ---------- 连接请求（0x0019） ----------

    /**
     * 遥控端 -> 相机 的连接请求（33 字节 payload）。
     * @param deviceId 本机（遥控端）持久随机设备 ID
     * @param mac      本机 6 字节协议 MAC（可随机生成持久保存）
     * @param verifyMode 0=已配对重连 1=首次配对（相机弹窗确认）
     * @param verifyData 首次配对时的校验码
     */
    fun connectionRequest(
        deviceId: Long,
        mac: ByteArray,
        fwVersion: Long,
        verifyMode: Int,
        verifyData: Int,
    ): ByteArray {
        val p = ByteArray(33)
        putU32(p, 0, deviceId)
        p[4] = mac.size.toByte()
        mac.copyInto(p, 5)
        putU32(p, 21, fwVersion)
        p[25] = 0 // conidx 预留
        p[26] = verifyMode.toByte()
        putU16(p, 27, verifyData)
        // 29..32 预留 0
        return p
    }

    /** 相机 -> 遥控端 的连接请求（verify_mode=2 表示校验结果） */
    class CameraConnectRequest(
        val deviceId: Long,   // 相机自己的设备 ID（低16位为型号码）
        val macLen: Int,
        val mac: ByteArray,
        val verifyMode: Int,
        val verifyData: Int,
    )

    fun parseCameraConnectRequest(p: ByteArray): CameraConnectRequest? {
        if (p.size < 29) return null
        val macLen = p[4].toInt() and 0xFF
        val mac = if (macLen in 1..6 && p.size >= 5 + macLen) p.copyOfRange(5, 5 + macLen) else ByteArray(0)
        return CameraConnectRequest(
            deviceId = getU32(p, 0),
            macLen = macLen,
            mac = mac,
            verifyMode = p[26].toInt() and 0xFF,
            verifyData = getU16(p, 27),
        )
    }

    /** 相机应答遥控端连接请求的响应帧（9 字节） */
    class ConnectResponse(
        val deviceId: Long,
        val retCode: Int,
        val reserved: Int, // 相机侧用此字段返回相机编号
    )

    fun parseConnectResponse(p: ByteArray): ConnectResponse? {
        if (p.size < 5) return null
        return ConnectResponse(
            deviceId = getU32(p, 0),
            retCode = p[4].toInt() and 0xFF,
            reserved = if (p.size >= 9) getU32(p, 5).toInt() else 0,
        )
    }

    /** 遥控端应答相机连接请求的响应帧（9 字节），reserved[0] 为相机编号 */
    fun connectResponse(deviceId: Long, retCode: Int, cameraIndex: Int): ByteArray {
        val p = ByteArray(9)
        putU32(p, 0, deviceId)
        p[4] = retCode.toByte()
        putU32(p, 5, cameraIndex.toLong())
        return p
    }

    // ---------- 按键上报（0x0011） ----------

    /** key_code=0x01 拍录键短按事件 -> 拍照模式下拍一张，视频模式下开始/停止录像 */
    fun keyReportShutter(): ByteArray = keyReport(KEY_SHUTTER, mode = 1, keyValue = 0)

    fun keyReport(keyCode: Int, mode: Int, keyValue: Int): ByteArray {
        val p = ByteArray(4)
        p[0] = keyCode.toByte()
        p[1] = mode.toByte()
        putU16(p, 2, keyValue)
        return p
    }

    // ---------- 统一模式切换（0x1D04） ----------

    fun modeSwitch(deviceId: Long, mode: Int): ByteArray {
        val p = ByteArray(9)
        putU32(p, 0, deviceId)
        p[4] = mode.toByte()
        // 5..8 预留
        return p
    }

    // ---------- 拍录控制（0x1D03） ----------

    fun recordControl(deviceId: Long, start: Boolean): ByteArray {
        val p = ByteArray(9)
        putU32(p, 0, deviceId)
        p[4] = (if (start) 0 else 1).toByte()
        return p
    }

    // ---------- 相机状态订阅（0x1D05） ----------

    /** push_mode=3 周期+状态变化推送；push_freq 固定 20（2Hz） */
    fun statusSubscription(pushMode: Int = 3, pushFreq: Int = 20): ByteArray {
        val p = ByteArray(6)
        p[0] = pushMode.toByte()
        p[1] = pushFreq.toByte()
        return p
    }

    // ---------- 相机状态推送（0x1D02） ----------

    class CameraStatus(
        val cameraMode: Int,
        val camStatus: Int,
        val recordTimeSec: Int,
        val remainCapacityMb: Long,
        val remainPhotoNum: Long,
        val remainVideoSec: Long,
        val powerMode: Int,
        val batteryPercent: Int,
    )

    fun parseStatusPush(p: ByteArray): CameraStatus? {
        if (p.size < 38) return null
        return CameraStatus(
            cameraMode = p[0].toInt() and 0xFF,
            camStatus = p[1].toInt() and 0xFF,
            recordTimeSec = getU16(p, 5),
            remainCapacityMb = getU32(p, 15),
            remainPhotoNum = getU32(p, 19),
            remainVideoSec = getU32(p, 23),
            powerMode = p[28].toInt() and 0xFF,
            batteryPercent = p[37].toInt() and 0xFF,
        )
    }
}
