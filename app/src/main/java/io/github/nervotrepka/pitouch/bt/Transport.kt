package io.github.nervotrepka.pitouch.bt

import android.bluetooth.BluetoothDevice

enum class LinkState { DISCONNECTED, WAITING, CONNECTING, CONNECTED }

/** A way of delivering HID reports to the Pi. */
interface Transport {
    fun interface Callback {
        /** May be called from any thread. */
        fun onState(state: LinkState, device: BluetoothDevice?, error: String?)
    }

    fun start()
    fun connect(device: BluetoothDevice)
    fun disconnect()

    /** Called on the connection's I/O thread. */
    fun send(reportId: Int, report: ByteArray)
    fun close()
}
