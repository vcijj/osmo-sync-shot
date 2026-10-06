package com.osmosync.app.mesh

import android.net.wifi.WifiManager
import android.os.Build
import com.osmosync.app.ble.CameraManager
import com.osmosync.app.shooter.IntervalShooter
import com.osmosync.app.shooter.ShootConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

enum class MeshRole { NONE, MASTER, SLAVE }

/** 主机视角下的一台从机 */
data class SlaveInfo(
    val id: String,
    val name: String,
    val cams: Int,
    val offsetMs: Long,
    val rttMs: Long,
)

/** 协同会话中的一条拍摄记录（主机聚合显示：本机 + 各从机） */
data class MeshProgress(
    val label: String,
    val shot: Int,
    val ok: Int,
    val fail: Int,
    val phone: Boolean,
    val atMs: Long,
)

/** 从机侧状态 */
data class SlaveState(
    val masterName: String = "",
    val offsetMs: Long = 0,
    val rttMs: Long = -1,
    val session: ShootConfig? = null,
    val sessionStartAt: Long = 0,
)

/**
 * 多手机协同：主机开热点（或同一局域网），从机连入后做时钟校准；
 * 主机下发"绝对开始时刻 + 参数"，各设备用本地时钟按校准后的时刻同步开拍。
 */
class MeshManager(
    private val cameraManager: CameraManager,
    private val shooter: IntervalShooter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceName: String = Build.MODEL ?: "Android"

    private val _role = MutableStateFlow(MeshRole.NONE)
    val role: StateFlow<MeshRole> = _role

    private val _netStatus = MutableStateFlow("")
    val netStatus: StateFlow<String> = _netStatus

    private val _slaves = MutableStateFlow<List<SlaveInfo>>(emptyList())
    val slaves: StateFlow<List<SlaveInfo>> = _slaves

    private val _progress = MutableStateFlow<List<MeshProgress>>(emptyList())
    val progress: StateFlow<List<MeshProgress>> = _progress

    private val _slaveState = MutableStateFlow<SlaveState?>(null)
    val slaveState: StateFlow<SlaveState?> = _slaveState

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val clients = ConcurrentHashMap<String, Client>()

    private var masterConn: SlaveConn? = null
    @Volatile private var slaveStopFlag = false

    private class Client(val id: String, val socket: Socket) {
        @Volatile var name: String = ""
        @Volatile var cams: Int = 0
        @Volatile var offsetMs: Long = 0
        @Volatile var rttMs: Long = -1
        @Volatile var busy: Boolean = false
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val sendLock = Any()
        fun send(line: String): Boolean = try {
            synchronized(sendLock) { writer.println(line) }
            true
        } catch (_: Exception) { false }
    }

    private inner class SlaveConn(val socket: Socket, val ip: String) {
        @Volatile var masterName: String = ""
        @Volatile var offsetMs: Long = 0
        @Volatile var rttMs: Long = -1
        @Volatile var alive: Boolean = true
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        fun send(line: String): Boolean = try {
            synchronized(writer) { writer.println(line) }
            true
        } catch (_: Exception) { false }
    }

    private fun publishSlaves() {
        _slaves.value = clients.values
            .sortedBy { it.id }
            .map { SlaveInfo(it.id, it.name.ifBlank { "未命名" }, it.cams, it.offsetMs, it.rttMs) }
    }

    private fun addProgress(p: MeshProgress) {
        _progress.value = (_progress.value + p).takeLast(300)
    }

    private fun subnetBroadcasts(): List<String> {
        val out = mutableSetOf("255.255.255.255")
        try {
            val wifi = appContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wifi?.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                val bytes = byteArrayOf(
                    (bc and 0xFF).toByte(), ((bc shr 8) and 0xFF).toByte(),
                    ((bc shr 16) and 0xFF).toByte(), ((bc shr 24) and 0xFF).toByte(),
                )
                val host = InetAddress.getByAddress(bytes).hostAddress
                if (host != null) out.add(host)
            }
        } catch (_: Exception) {}
        return out.toList()
    }

    // ================= 主机 =================

    fun startMaster() {
        if (_role.value == MeshRole.MASTER) return
        stopInternal()
        _role.value = MeshRole.MASTER
        _progress.value = emptyList()
        scope.launch {
            try {
                udpSocket = DatagramSocket(Mesh.UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                serverSocket = ServerSocket(Mesh.TCP_PORT, 8, InetAddress.getByName("0.0.0.0"))
                _netStatus.value = "主机已就绪，等待从机接入..."
                launch { udpDiscoverLoop() }
                acceptLoop()
            } catch (e: Exception) {
                _netStatus.value = "主机启动失败: ${e.message}（请确认热点已开启）"
                stopInternal()
            }
        }
    }

    private suspend fun udpDiscoverLoop() {
        val buf = ByteArray(512)
        while (_role.value == MeshRole.MASTER && udpSocket != null) {
            try {
                val p = DatagramPacket(buf, buf.size)
                udpSocket?.receive(p) ?: break
                val m = Mesh.parse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                if (m.optString("t") == Mesh.T_DISC) {
                    val reply = Mesh.msg("t" to Mesh.T_DISC_OK, "name" to deviceName, "port" to Mesh.TCP_PORT)
                    DatagramSocket().send(
                        DatagramPacket(reply.toByteArray(), reply.length, p.address, p.port),
                    )
                }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (_role.value != MeshRole.MASTER) break
                delay(300)
            }
        }
    }

    private suspend fun acceptLoop() {
        while (_role.value == MeshRole.MASTER) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                break
            }
            val id = "${socket.inetAddress.hostAddress}:${socket.port}"
            val client = Client(id, socket)
            clients[id] = client
            publishSlaves()
            scope.launch { clientReadLoop(client) }
        }
    }

    private suspend fun clientReadLoop(client: Client) {
        try {
            client.socket.soTimeout = 12000 // 读超时仅用于兜底感知掉线，ping 会持续刷新
            while (_role.value == MeshRole.MASTER && client.socket.isConnected) {
                val line = try {
                    client.reader.readLine()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    null
                } ?: break
                val m = Mesh.parse(line) ?: continue
                when (m.optString("t")) {
                    Mesh.T_HELLO -> {
                        client.name = m.optString("name")
                        client.cams = m.optInt("cams")
                        publishSlaves()
                        client.send(Mesh.msg("t" to Mesh.T_WELCOME, "name" to deviceName, "cams" to cameraManager.connectedCameras().size))
                    }
                    Mesh.T_PING -> {
                        client.offsetMs = m.optLong("offset")
                        client.send(Mesh.msg("t" to Mesh.T_PONG, "k" to m.optInt("k"), "t0" to m.optLong("t0"), "t1" to System.currentTimeMillis()))
                    }
                    Mesh.T_SHOT -> {
                        addProgress(
                            MeshProgress(
                                label = client.name.ifBlank { client.id },
                                shot = m.optInt("shot"),
                                ok = m.optInt("ok"),
                                fail = m.optInt("fail"),
                                phone = m.optBoolean("phone"),
                                atMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    Mesh.T_SESSION_OK -> client.busy = true
                    Mesh.T_SESSION_BUSY -> client.busy = false
                    Mesh.T_BYE -> break
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(client.id)
            publishSlaves()
            try { client.socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendAll(line: String) {
        clients.values.forEach { it.send(line) }
    }

    /** 主机发起协同会话：给所有从机下发绝对开始时刻，同时启动本机连拍 */
    fun startMasterSession(cfg: ShootConfig): Boolean {
        if (_role.value != MeshRole.MASTER) return false
        val startAt = (shooter.computeStartAt(cfg) ?: return false)
            .coerceAtLeast(System.currentTimeMillis() + 3000)
        val json = Mesh.msg(
            "t" to Mesh.T_SESSION,
            "startAt" to startAt,
            "intervalMs" to (cfg.intervalSeconds * 1000).toLong(),
            "shots" to cfg.totalShots,
            "phone" to cfg.phoneCapture,
            "photo" to cfg.switchToPhotoFirst,
        )
        sendAll(json)
        addProgress(MeshProgress("会话已下发（${clients.size} 台从机）", 0, 0, 0, false, System.currentTimeMillis()))
        shooter.start(
            cfg,
            startAtOverride = startAt,
            onShot = { entry ->
                addProgress(
                    MeshProgress(
                        "本机（$deviceName）",
                        entry.index,
                        entry.cameraResults.count { it.ok },
                        entry.cameraResults.count { !it.ok },
                        entry.phoneSaved,
                        entry.timeMs,
                    ),
                )
            },
        )
        return true
    }

    fun stopMasterSession() {
        shooter.stop()
        sendAll(Mesh.msg("t" to Mesh.T_STOP))
    }

    fun stopMaster() {
        stopInternal()
    }

    // ================= 从机 =================

    fun startSlave(manualIp: String?) {
        if (_role.value == MeshRole.SLAVE) return
        stopInternal()
        _role.value = MeshRole.SLAVE
        slaveStopFlag = false
        scope.launch {
            val target = discoverMaster(manualIp)
            if (target == null) {
                _netStatus.value = "未发现主机：请确认与主机在同一 WiFi/热点网络"
                return@launch
            }
            _netStatus.value = "发现主机 ${target.first}，连接中..."
            slaveRun(target.first, target.second)
        }
    }

    private fun discoverMaster(manualIp: String?): Pair<String, Int>? {
        if (!manualIp.isNullOrBlank()) return manualIp.trim() to Mesh.TCP_PORT
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply { broadcast = true; soTimeout = 800 }
            val payload = Mesh.msg("t" to Mesh.T_DISC).toByteArray()
            val targets = subnetBroadcasts().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                for (t in targets) {
                    try {
                        socket.send(DatagramPacket(payload, payload.size, t, Mesh.UDP_PORT))
                    } catch (_: Exception) {}
                }
                try {
                    val buf = ByteArray(512)
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val m = Mesh.parse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                    if (m.optString("t") == Mesh.T_DISC_OK) {
                        return (p.address.hostAddress ?: continue) to m.optInt("port", Mesh.TCP_PORT)
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private suspend fun slaveRun(ip: String, port: Int) {
        var backoff = 1000L
        while (_role.value == MeshRole.SLAVE && !slaveStopFlag) {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ip, port), 4000)
                socket.soTimeout = 0
                val conn = SlaveConn(socket, ip)
                masterConn = conn
                backoff = 1000
                conn.send(Mesh.msg("t" to Mesh.T_HELLO, "name" to deviceName, "cams" to cameraManager.connectedCameras().size))
                _netStatus.value = "已连接主机，正在校准时钟..."
                val syncJob = scope.launch { clockSyncLoop(conn) }
                slaveReadLoop(conn)
                syncJob.cancel()
            } catch (e: Exception) {
                _netStatus.value = "与主机断开：${e.message ?: "连接失败"}，${if (!slaveStopFlag) "重连中..." else ""}"
            }
            if (!slaveStopFlag && _role.value == MeshRole.SLAVE) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(8000)
            }
        }
    }

    private suspend fun clockSyncLoop(conn: SlaveConn) {
        var k = 0
        while (conn.alive && _role.value == MeshRole.SLAVE) {
            val t0 = System.currentTimeMillis()
            if (!conn.send(Mesh.msg("t" to Mesh.T_PING, "k" to k, "t0" to t0, "offset" to conn.offsetMs))) break
            delay(150)
            if (++k % 8 == 0) delay(6000) // 前 8 轮密集采样，之后放缓频率
        }
    }

    private suspend fun slaveReadLoop(conn: SlaveConn) {
        try {
            while (conn.alive && _role.value == MeshRole.SLAVE) {
                val line = conn.reader.readLine() ?: break
                val m = Mesh.parse(line) ?: continue
                when (m.optString("t")) {
                    Mesh.T_WELCOME -> {
                        conn.masterName = m.optString("name")
                        _slaveState.value = SlaveState(masterName = conn.masterName, offsetMs = conn.offsetMs, rttMs = conn.rttMs)
                    }
                    Mesh.T_PONG -> {
                        val t0 = m.optLong("t0")
                        val t1 = m.optLong("t1")
                        val t3 = System.currentTimeMillis()
                        val rtt = t3 - t0
                        val offset = t1 - t0 - rtt / 2 // 主机时钟 - 从机时钟
                        if (conn.rttMs < 0 || rtt < conn.rttMs) {
                            conn.offsetMs = offset
                            conn.rttMs = rtt
                        }
                        _slaveState.value = SlaveState(
                            masterName = conn.masterName,
                            offsetMs = conn.offsetMs,
                            rttMs = conn.rttMs,
                            session = _slaveState.value?.session,
                            sessionStartAt = _slaveState.value?.sessionStartAt ?: 0,
                        )
                    }
                    Mesh.T_SESSION -> {
                        if (shooter.isRunning) {
                            conn.send(Mesh.msg("t" to Mesh.T_SESSION_BUSY))
                        } else {
                            val cfg = ShootConfig(
                                intervalSeconds = m.optLong("intervalMs") / 1000.0,
                                totalShots = m.optInt("shots"),
                                startDelaySeconds = 0,
                                startAtClock = "",
                                phoneCapture = m.optBoolean("phone"),
                                switchToPhotoFirst = m.optBoolean("photo"),
                            )
                            val startAtMaster = m.optLong("startAt")
                            val startAtLocal = startAtMaster - conn.offsetMs // 从机时钟 = 主机时钟 - offset
                            _slaveState.value = SlaveState(
                                masterName = conn.masterName,
                                offsetMs = conn.offsetMs,
                                rttMs = conn.rttMs,
                                session = cfg,
                                sessionStartAt = startAtMaster,
                            )
                            conn.send(Mesh.msg("t" to Mesh.T_SESSION_OK))
                            shooter.start(
                                cfg,
                                startAtOverride = startAtLocal,
                                onShot = { entry ->
                                    conn.send(
                                        Mesh.msg(
                                            "t" to Mesh.T_SHOT,
                                            "shot" to entry.index,
                                            "ok" to entry.cameraResults.count { it.ok },
                                            "fail" to entry.cameraResults.count { !it.ok },
                                            "phone" to entry.phoneSaved,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Mesh.T_STOP -> shooter.stop()
                    Mesh.T_PING -> conn.send(Mesh.msg("t" to Mesh.T_PONG, "k" to m.optInt("k"), "t0" to m.optLong("t0"), "t1" to System.currentTimeMillis()))
                }
            }
        } catch (_: Exception) {
        } finally {
            conn.alive = false
            try { conn.socket.close() } catch (_: Exception) {}
            if (masterConn === conn) masterConn = null
        }
    }

    fun stopSlave() {
        shooter.stop()
        stopInternal()
    }

    // ================= 公共 =================

    private fun stopInternal() {
        slaveStopFlag = true
        try { masterConn?.send(Mesh.msg("t" to Mesh.T_BYE)) } catch (_: Exception) {}
        try { masterConn?.socket?.close() } catch (_: Exception) {}
        masterConn = null
        clients.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
        _slaves.value = emptyList()
        _slaveState.value = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        _role.value = MeshRole.NONE
        scope.coroutineContext.cancelChildren()
    }

    companion object {
        // 局域网广播需要上下文，由 App 在构造时注入
        lateinit var appContext: android.content.Context
            private set

        fun initContext(ctx: android.content.Context) { appContext = ctx.applicationContext }
    }
}
