package com.osmosync.app.mesh

import org.json.JSONObject

/**
 * 多机协同（主从）协议：
 * - UDP 广播（端口 47016）：从机发现主机
 * - TCP（端口 47017）：换行分隔的 JSON 消息
 * - 时钟同步：从机发起 ping/pong（NTP 式四时刻法），取最小 RTT 的偏移估计
 */
object Mesh {
    const val UDP_PORT = 47016
    const val TCP_PORT = 47017

    fun msg(vararg pairs: Pair<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }

    fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (_: Exception) {
        null
    }

    // ---- 消息类型常量 ----
    const val T_DISC = "disc"
    const val T_DISC_OK = "disc_ok"
    const val T_HELLO = "hello"
    const val T_WELCOME = "welcome"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_SESSION = "session"
    const val T_SESSION_OK = "session_ok"
    const val T_SESSION_BUSY = "session_busy"
    const val T_SHOT = "shot"
    const val T_STOP = "stop"
    const val T_BYE = "bye"
}
