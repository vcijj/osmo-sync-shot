package com.osmosync.app.protocol

/**
 * DJI R SDK 协议帧封装与解析（小端存储）。
 *
 * 帧结构:
 *  偏移 0    SOF       1B   固定 0xAA
 *  偏移 1    Ver/Len   2B   [15:10]=版本0  [9:0]=整帧长度 (LSB first)
 *  偏移 3    CmdType   1B   [4:0]应答类型  [5]帧类型(0命令/1应答) [7:6]保留
 *  偏移 4    ENC       1B   0=不加密
 *  偏移 5    RES       3B
 *  偏移 8    SEQ       2B   应答帧回带相同 SEQ
 *  偏移 10   CRC16     2B   覆盖 SOF..SEQ
 *  偏移 12   DATA      nB   CmdSet(1B)+CmdID(1B)+payload(n-2)
 *  末尾      CRC32     4B   覆盖 SOF..DATA
 */
object DjiFrame {

    const val SOF = 0xAA

    // CmdType 高低组合
    const val CMD_NO_RESPONSE = 0x00      // 命令帧，无需应答
    const val CMD_RESPONSE_OR_NOT = 0x01  // 命令帧，最好有应答但缺失不算失败
    const val CMD_WAIT_RESULT = 0x02      // 命令帧，必须有应答
    const val ACK_NO_RESPONSE = 0x20      // 应答帧（bit5=1 表示应答帧）

    fun build(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray = ByteArray(0), seq: Int): ByteArray {
        val dataLen = payload.size + 2
        val total = 12 + dataLen + 4
        val frame = ByteArray(total)

        frame[0] = SOF.toByte()
        val verLength = total and 0x3FF
        frame[1] = (verLength and 0xFF).toByte()
        frame[2] = ((verLength ushr 8) and 0x03).toByte()
        frame[3] = cmdType.toByte()
        frame[4] = 0 // ENC 不加密
        // frame[5..7] RES 保留 0
        frame[8] = (seq and 0xFF).toByte()
        frame[9] = ((seq ushr 8) and 0xFF).toByte()

        val crc16 = Crc.crc16(frame, 10)
        frame[10] = (crc16 and 0xFF).toByte()
        frame[11] = ((crc16 ushr 8) and 0xFF).toByte()

        frame[12] = cmdSet.toByte()
        frame[13] = cmdId.toByte()
        payload.copyInto(frame, 14)

        val crc32 = Crc.crc32(frame, total - 4)
        for (i in 0 until 4) {
            frame[total - 4 + i] = ((crc32 ushr (8 * i)) and 0xFFL).toInt().toByte()
        }
        return frame
    }

    /** 解析出的一帧 */
    class Parsed(
        val cmdType: Int,   // 原始字节，bit5=1 为应答帧
        val seq: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
    ) {
        val isAck: Boolean get() = (cmdType and 0x20) != 0
    }

    /**
     * 从字节流中解析一帧。
     * 返回 (解析结果, 消耗的字节数)；帧不完整或校验失败返回 null。
     * @param strict true 时校验失败立即报错（用于单帧数据），false 时跳到下一个 SOF 继续。
     */
    fun parse(buf: ByteArray, offset: Int = 0, strict: Boolean = false): Pair<Parsed, Int>? {
        var start = offset
        while (start < buf.size && (buf[start].toInt() and 0xFF) != SOF) start++
        if (buf.size - start < 16) return null // 最短帧 12头+2数据+4crc? 版本查询响应更长，16 为下限
        val len = (buf[start + 1].toInt() and 0xFF) or
                ((buf[start + 2].toInt() and 0x03) shl 8)
        if (len < 16 || start + len > buf.size) {
            if (strict) return null
            // 长度不合理，跳过当前 SOF 找下一个
            return parse(buf, start + 1, false)
        }
        val frame = buf.copyOfRange(start, start + len)

        val crc16 = (frame[10].toInt() and 0xFF) or ((frame[11].toInt() and 0xFF) shl 8)
        if (Crc.crc16(frame, 10) != crc16) {
            if (strict) return null
            return parse(buf, start + 1, false)
        }
        var crc32 = 0L
        for (i in 0 until 4) crc32 = crc32 or ((frame[len - 4 + i].toLong() and 0xFF) shl (8 * i))
        if (Crc.crc32(frame, len - 4) != crc32) {
            if (strict) return null
            return parse(buf, start + 1, false)
        }

        val parsed = Parsed(
            cmdType = frame[3].toInt() and 0xFF,
            seq = (frame[8].toInt() and 0xFF) or ((frame[9].toInt() and 0xFF) shl 8),
            cmdSet = frame[12].toInt() and 0xFF,
            cmdId = frame[13].toInt() and 0xFF,
            payload = frame.copyOfRange(14, len - 4),
        )
        return parsed to (start + len)
    }
}
