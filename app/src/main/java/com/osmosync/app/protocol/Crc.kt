package com.osmosync.app.protocol

/**
 * DJI R SDK 协议使用的 CRC 算法。
 * 与官方 Osmo-GPS-Controller-Demo 的 custom_crc16.c / custom_crc32.c 一致：
 * 两个算法均为反射表格法，初始值 0x3AA3，无输出翻转。
 */
object Crc {

    private val crc16Table = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
            table[i] = c and 0xFFFF
        }
    }

    private val crc32Table = LongArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i.toLong()
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
            table[i] = c and 0xFFFFFFFFL
        }
    }

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0x3AA3
        for (i in 0 until length) {
            crc = (crc16Table[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)) and 0xFFFF
        }
        return crc and 0xFFFF
    }

    fun crc32(data: ByteArray, length: Int = data.size): Long {
        var crc = 0x3AA3L
        for (i in 0 until length) {
            crc = (crc32Table[((crc xor data[i].toLong()) and 0xFF).toInt()] xor (crc ushr 8)) and 0xFFFFFFFFL
        }
        return crc and 0xFFFFFFFFL
    }
}
