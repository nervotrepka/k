package io.github.nervotrepka.pitouch.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Samsung TV remote over Wi-Fi.
 * - 2016+ (Tizen): WebSocket API on port 8001, or 8002 (wss + token) on 2018+ models.
 * - 2010–2014: legacy binary protocol on port 55000.
 * 2015 "J" series uses an encrypted protocol and is not supported (use Bluetooth).
 */
class SamsungRemote(private val context: Context, private val listener: Listener) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        /** Main thread. */
        fun onTvState(state: State, error: String?)

        /** Main thread. New token or MAC learned for the TV at [ip]. */
        fun onTvInfo(ip: String, mac: String?, token: String?)
    }

    data class Found(val ip: String, val name: String)

    @Volatile var state = State.DISCONNECTED
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var ws: MiniWebSocket? = null
    private var legacy: Socket? = null
    private var ip: String? = null
    private var token: String? = null
    private var mac: String? = null

    /** Sets the target TV and connects in the background. */
    fun connect(ip: String, mac: String?, token: String?) {
        io.execute {
            if (this.ip == ip && state != State.DISCONNECTED) return@execute
            closeNow()
            this.ip = ip
            this.mac = mac
            this.token = token
            openNow()
        }
    }

    fun disconnect() = io.execute {
        closeNow()
        ip = null
        update(State.DISCONNECTED, null)
    }

    fun key(code: String) = io.execute {
        if (code == "KEY_POWER" && state != State.CONNECTED && mac != null) {
            wakeOnLan(mac!!) // a TV in standby is off the network; magic packet turns it on
            return@execute
        }
        if (!ensureOpen()) return@execute
        ws?.let { sendJson(it, keyJson(code)); return@execute }
        legacy?.let { sendLegacyKey(it, code) }
    }

    fun text(text: String, enter: Boolean) = io.execute {
        if (!ensureOpen()) return@execute
        val socket = ws
        if (socket == null) {
            fail("Старый ТВ не принимает текст по Wi-Fi — отправьте его по Bluetooth")
            return@execute
        }
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray())
        sendJson(socket, JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", encoded).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")))
        sendJson(socket, JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("TypeOfRemote", "SendInputEnd")))
        if (enter) sendJson(socket, keyJson("KEY_ENTER"))
    }

    fun launchApp(appId: String) = io.execute {
        if (!ensureOpen()) return@execute
        val socket = ws
        if (socket == null) {
            fail("Запуск приложений не поддерживается этим ТВ")
            return@execute
        }
        sendJson(socket, JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
            .put("event", "ed.apps.launch").put("to", "host")
            .put("data", JSONObject().put("appId", appId).put("action_type", "DEEP_LINK"))))
    }

    fun close() {
        io.execute { closeNow() }
        io.shutdown()
    }

    // region connection

    private fun ensureOpen(): Boolean {
        if (state == State.CONNECTED) return true
        if (ip == null) {
            fail("У устройства не указан IP-адрес ТВ")
            return false
        }
        openNow()
        return state == State.CONNECTED
    }

    private fun openNow() {
        val host = ip ?: return
        update(State.CONNECTING, null)
        val info = fetchInfo(host)
        if (info != null) {
            val device = info.optJSONObject("device")
            device?.optString("wifiMac")?.takeIf { it.isNotEmpty() }?.let { learned ->
                if (learned != mac) {
                    mac = learned
                    main.post { listener.onTvInfo(host, learned, null) }
                }
            }
            val secure = device?.optString("TokenAuthSupport") == "true"
            if (openWebSocket(host, secure)) return
        } else if (openLegacy(host)) {
            return
        }
        update(
            State.DISCONNECTED,
            "ТВ $host не отвечает. Он включён и в той же Wi-Fi сети? (ТВ 2015 года, серия J, по Wi-Fi не поддерживается)",
        )
    }

    private fun fetchInfo(host: String): JSONObject? = try {
        val conn = URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection
        conn.connectTimeout = 2500
        conn.readTimeout = 2500
        conn.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: Exception) {
        null
    }

    private fun openWebSocket(host: String, secure: Boolean): Boolean {
        val name = Base64.getEncoder().encodeToString(APP_NAME.toByteArray())
        val tokenPart = token?.let { "&token=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val url = if (secure) {
            "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
        } else {
            "ws://$host:8001/api/v2/channels/samsung.remote.control?name=$name"
        }
        val lock = Object()
        var result: String? = null // "ok" or error text
        lateinit var socket: MiniWebSocket
        socket = MiniWebSocket(URI(url), onMessage = { message ->
            val json = try {
                JSONObject(message)
            } catch (_: Exception) {
                return@MiniWebSocket
            }
            when (json.optString("event")) {
                "ms.channel.connect" -> {
                    json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { t ->
                        token = t
                        main.post { listener.onTvInfo(host, null, t) }
                    }
                    synchronized(lock) { result = "ok"; lock.notifyAll() }
                }
                "ms.channel.unauthorized", "ms.channel.timeOut" ->
                    synchronized(lock) { result = "Подключение не разрешено на ТВ"; lock.notifyAll() }
            }
        }, onClosed = {
            synchronized(lock) { if (result == null) result = "ТВ закрыл соединение"; lock.notifyAll() }
            if (!io.isShutdown) io.execute {
                if (ws === socket) {
                    ws = null
                    update(State.DISCONNECTED, "Соединение с ТВ закрыто")
                }
            }
        })
        try {
            socket.connect(4000)
        } catch (_: IOException) {
            return false
        }
        main.post { listener.onTvState(State.CONNECTING, "Если на ТВ появился запрос — нажмите «Разрешить»") }
        val deadline = System.currentTimeMillis() + 30_000
        synchronized(lock) {
            while (result == null && System.currentTimeMillis() < deadline) lock.wait(1000)
        }
        if (result != "ok") {
            socket.close()
            update(State.DISCONNECTED, result ?: "ТВ не ответил на запрос подключения")
            return true // reached the TV; don't fall back to the legacy protocol
        }
        ws = socket
        update(State.CONNECTED, null)
        return true
    }

    private fun openLegacy(host: String): Boolean {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, 55000), 2500)
            socket.soTimeout = 30_000
            val payload = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x64, 0x00))
                write(legacyString(localIp() ?: "0.0.0.0"))
                write(legacyString("pitouch-android"))
                write(legacyString(APP_NAME))
            }.toByteArray()
            socket.getOutputStream().write(legacyPacket(payload))
            main.post { listener.onTvState(State.CONNECTING, "Если на ТВ появился запрос — нажмите «Разрешить»") }
            // Reply: 0x00/0x02, app string, then the payload: 64 00 01 00 = allowed, 64 00 00 00 = denied,
            // 0a 00 ... = waiting for the user.
            while (true) {
                val reply = readLegacyReply(socket.getInputStream())
                if (reply.size >= 4 && reply[0] == 0x64.toByte() && reply[2] == 0x01.toByte()) break
                if (reply.size >= 4 && reply[0] == 0x64.toByte() && reply[2] == 0x00.toByte()) {
                    socket.close()
                    update(State.DISCONNECTED, "Подключение запрещено на ТВ")
                    return true
                }
                if (reply.size >= 2 && reply[0] == 0x65.toByte()) {
                    socket.close()
                    update(State.DISCONNECTED, "ТВ отменил подключение (время ожидания вышло)")
                    return true
                }
            }
            socket.soTimeout = 0
            legacy = socket
            update(State.CONNECTED, null)
            return true
        } catch (_: SocketTimeoutException) {
            socket.close()
            return false
        } catch (_: IOException) {
            socket.close()
            return false
        }
    }

    private fun sendLegacyKey(socket: Socket, code: String) {
        try {
            val payload = byteArrayOf(0x00, 0x00, 0x00) + legacyString(code)
            socket.getOutputStream().write(legacyPacket(payload))
        } catch (_: IOException) {
            closeNow()
            update(State.DISCONNECTED, "Соединение с ТВ потеряно")
        }
    }

    private fun readLegacyReply(input: InputStream): ByteArray {
        input.read() // header
        val appLength = input.read() or (input.read() shl 8)
        input.skip(appLength.toLong())
        val length = input.read() or (input.read() shl 8)
        if (length < 0) throw IOException("closed")
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) throw IOException("closed")
            read += n
        }
        return data
    }

    private fun legacyString(s: String): ByteArray {
        val b = Base64.getEncoder().encode(s.toByteArray())
        return byteArrayOf(b.size.toByte(), 0) + b
    }

    private fun legacyPacket(payload: ByteArray): ByteArray {
        val app = LEGACY_APP.toByteArray()
        return byteArrayOf(0x00, app.size.toByte(), 0) + app +
            byteArrayOf((payload.size and 0xFF).toByte(), (payload.size shr 8).toByte()) + payload
    }

    private fun sendJson(socket: MiniWebSocket, json: JSONObject) {
        try {
            socket.send(json.toString())
        } catch (_: IOException) {
            closeNow()
            update(State.DISCONNECTED, "Соединение с ТВ потеряно")
        }
    }

    private fun keyJson(code: String) = JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
        .put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))

    private fun closeNow() {
        ws?.let { ws = null; it.close() }
        legacy?.let {
            legacy = null
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        state = State.DISCONNECTED
    }

    private fun update(newState: State, error: String?) {
        state = newState
        main.post { listener.onTvState(newState, error) }
    }

    private fun fail(error: String) = main.post { listener.onTvState(state, error) }

    // endregion

    // region discovery and wake

    @Suppress("DEPRECATION")
    private fun localIp(): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    /** Finds Samsung TVs in the local network via SSDP. Blocking; call off the main thread. */
    fun discover(timeoutMs: Int = 3000): List<Found> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("pitouch")?.apply { setReferenceCounted(false); acquire() }
        val ips = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(request, request.size, group, 1900)) }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    packet.address?.hostAddress?.let { ips += it }
                }
            }
        } catch (_: IOException) {
        } finally {
            lock?.release()
        }
        return ips.map { ip ->
            val name = fetchInfo(ip)?.optJSONObject("device")?.let { d ->
                listOf(d.optString("name"), d.optString("modelName")).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            Found(ip, name?.ifEmpty { null } ?: "Samsung TV")
        }
    }

    private fun wakeOnLan(mac: String) {
        val bytes = mac.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size != 6) return
        val packet = ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten().toByteArray()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (port in intArrayOf(9, 7)) {
                    socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), port))
                }
            }
            fail("Отправлен сигнал включения. Если ТВ не включился — включите в его настройках «Включение с мобильного»")
        } catch (_: IOException) {
            fail("Не удалось отправить сигнал включения")
        }
    }

    // endregion

    private companion object {
        const val APP_NAME = "PiTouch"
        const val LEGACY_APP = "iphone..iapp.samsung"
    }
}
