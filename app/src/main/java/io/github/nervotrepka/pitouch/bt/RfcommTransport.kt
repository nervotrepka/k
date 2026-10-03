package io.github.nervotrepka.pitouch.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Fallback mode: sends the same HID reports over RFCOMM to pitouch_server.py on the Pi,
 * which replays them through uinput. Frame = report id byte + report bytes.
 */
@SuppressLint("MissingPermission")
class RfcommTransport(
    private val adapter: BluetoothAdapter,
    private val callback: Transport.Callback,
) : Transport {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    override fun start() = callback.onState(LinkState.DISCONNECTED, null, null)

    override fun connect(device: BluetoothDevice) {
        disconnect()
        callback.onState(LinkState.CONNECTING, device, null)
        val s = try {
            device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (e: IOException) {
            callback.onState(LinkState.DISCONNECTED, device, "Ошибка Bluetooth: ${e.message}")
            return
        }
        socket = s
        thread(name = "rfcomm", isDaemon = true) { run(device, s) }
    }

    private fun run(device: BluetoothDevice, s: BluetoothSocket) {
        try {
            adapter.cancelDiscovery()
            s.connect()
        } catch (e: IOException) {
            closeQuietly(s)
            if (socket === s) {
                socket = null
                callback.onState(
                    LinkState.DISCONNECTED, device,
                    "Сервер на Pi не отвечает. Запущен ли pitouch (sudo systemctl status pitouch)?",
                )
            }
            return
        }
        if (socket !== s) {
            closeQuietly(s)
            return
        }
        output = s.outputStream
        callback.onState(LinkState.CONNECTED, device, null)
        try {
            val input = s.inputStream
            val buf = ByteArray(64)
            while (input.read(buf) >= 0) Unit
        } catch (_: IOException) {
        }
        closeQuietly(s)
        if (socket === s) {
            socket = null
            output = null
            callback.onState(LinkState.DISCONNECTED, device, "Соединение с Pi потеряно")
        }
    }

    override fun disconnect() {
        val s = socket ?: return
        socket = null
        output = null
        closeQuietly(s)
        callback.onState(LinkState.DISCONNECTED, null, null)
    }

    override fun send(reportId: Int, report: ByteArray) {
        val out = output ?: return
        try {
            out.write(byteArrayOf(reportId.toByte()) + report)
            out.flush()
        } catch (_: IOException) {
            socket?.let(::closeQuietly) // the reader thread reports the disconnect
        }
    }

    override fun close() = disconnect()

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Must match SERVICE_UUID in pi/pitouch_server.py. */
        val SERVICE_UUID: UUID = UUID.fromString("6c0f3f2e-2d9b-4f4e-9b7a-5049546f7563")
    }
}
