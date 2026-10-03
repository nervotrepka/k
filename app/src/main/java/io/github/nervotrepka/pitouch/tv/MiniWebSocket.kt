package io.github.nervotrepka.pitouch.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Minimal WebSocket client (text frames only), enough for the Samsung TV remote API.
 * For wss the TV's self-signed certificate is accepted: the connection is to a TV in the local network.
 */
class MiniWebSocket(
    private val uri: URI,
    private val onMessage: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private lateinit var socket: Socket
    private lateinit var output: OutputStream
    private val random = SecureRandom()
    @Volatile private var open = false

    fun connect(timeoutMs: Int) {
        val secure = uri.scheme == "wss"
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress(uri.host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        socket = if (secure) {
            (trustAll().socketFactory.createSocket(raw, uri.host, port, true) as SSLSocket).apply { startHandshake() }
        } else {
            raw
        }
        output = socket.getOutputStream()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val request = "GET $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(request.toByteArray())
        output.flush()

        val status = readLine(input)
        if (!status.contains(" 101")) throw IOException("WebSocket handshake failed: $status")
        while (readLine(input).isNotEmpty()) Unit

        socket.soTimeout = 0
        open = true
        thread(name = "ws-reader", isDaemon = true) { readLoop(input) }
    }

    val isOpen: Boolean get() = open

    @Synchronized
    fun send(text: String) = sendFrame(0x1, text.toByteArray())

    fun close() {
        if (!open) return
        open = false
        try {
            synchronized(this) { sendFrame(0x8, ByteArray(0)) }
        } catch (_: IOException) {
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> frame.write(0x80 or n)
            n < 65536 -> {
                frame.write(0x80 or 126)
                frame.write(n shr 8); frame.write(n)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write((n.toLong() shr shift).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        frame.write(mask)
        for (i in payload.indices) frame.write(payload[i].toInt() xor mask[i % 4].toInt())
        output.write(frame.toByteArray())
        output.flush()
    }

    private fun readLoop(input: DataInputStream) {
        val message = ByteArrayOutputStream()
        try {
            while (open) {
                val b0 = input.readUnsignedByte()
                val b1 = input.readUnsignedByte()
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also(input::readFully) else null
                val payload = ByteArray(length.toInt()).also(input::readFully)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0F) {
                    0x0, 0x1 -> {
                        message.write(payload)
                        if (b0 and 0x80 != 0) {
                            onMessage(message.toString("UTF-8"))
                            message.reset()
                        }
                    }
                    0x8 -> break
                    0x9 -> synchronized(this) { sendFrame(0xA, payload) }
                }
            }
        } catch (_: IOException) {
        }
        val wasOpen = open
        open = false
        try {
            socket.close()
        } catch (_: IOException) {
        }
        if (wasOpen) onClosed()
    }

    private fun readLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("Connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun trustAll(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
    }
}
