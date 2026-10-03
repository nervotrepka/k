package io.github.nervotrepka.pitouch.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.nervotrepka.pitouch.hid.HidReports

/**
 * The phone registers itself as a Bluetooth keyboard + mouse (Android 9+).
 * The Pi needs no extra software: it sees a regular Bluetooth HID device.
 */
@SuppressLint("MissingPermission")
class HidDeviceTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val callback: Transport.Callback,
) : Transport {

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var registered = false
    private var pending: BluetoothDevice? = null
    private var closed = false

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "PiTouch", "Phone touchpad and keyboard", "PiTouch",
        BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR,
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidDeviceTransport.registered = registered
            if (!registered) {
                host = null
                callback.onState(LinkState.DISCONNECTED, null, if (closed) null else "Система отключила HID-профиль")
                return
            }
            val target = pending ?: pluggedDevice
            if (target != null) connectNow(target) else callback.onState(LinkState.WAITING, null, null)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    pending = null
                    callback.onState(LinkState.CONNECTED, device, null)
                }
                BluetoothProfile.STATE_CONNECTING -> callback.onState(LinkState.CONNECTING, device, null)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host != null && host != device) return
                    val wasHost = host == device
                    host = null
                    val next = pending
                    if (wasHost && next != null && next != device && registered) {
                        connectNow(next) // switching to another host
                        return
                    }
                    val failed = !wasHost && next == device
                    pending = null
                    callback.onState(
                        if (registered) LinkState.WAITING else LinkState.DISCONNECTED, device,
                        if (failed) "Не удалось подключиться. Устройство сопряжено с телефоном в этом режиме?" else null,
                    )
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = HidReports.size(id.toInt())
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && size > 0) {
                hid?.replyReport(device, type, id, ByteArray(size))
            } else {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    override fun start() {
        callback.onState(LinkState.CONNECTING, null, null)
        val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (closed) {
                    adapter.closeProfileProxy(profile, proxy)
                    return
                }
                val device = proxy as BluetoothHidDevice
                hid = device
                if (!device.registerApp(sdp, null, null, context.mainExecutor, hidCallback)) {
                    callback.onState(LinkState.DISCONNECTED, null, UNSUPPORTED)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                registered = false
                if (!closed) callback.onState(LinkState.DISCONNECTED, null, "HID-сервис Bluetooth остановлен")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) callback.onState(LinkState.DISCONNECTED, null, UNSUPPORTED)
    }

    override fun connect(device: BluetoothDevice) {
        val current = host
        if (current == device) return
        pending = device
        if (current != null) {
            callback.onState(LinkState.CONNECTING, device, null)
            hid?.disconnect(current) // connects to [pending] once disconnected
        } else if (registered) {
            connectNow(device)
        }
    }

    private fun connectNow(device: BluetoothDevice) {
        pending = device
        callback.onState(LinkState.CONNECTING, device, null)
        if (hid?.connect(device) != true) {
            pending = null
            callback.onState(LinkState.WAITING, device, "Не удалось начать подключение")
        }
    }

    override fun disconnect() {
        pending = null
        host?.let { hid?.disconnect(it) }
    }

    override fun send(reportId: Int, report: ByteArray) {
        val device = host ?: return
        // sendReport() returns false when the Bluetooth queue is full: wait and retry instead of losing keys.
        repeat(SEND_ATTEMPTS) {
            val proxy = hid ?: return
            if (proxy.sendReport(device, reportId, report)) return
            Thread.sleep(RETRY_DELAY_MS)
            if (host != device) return
        }
    }

    override fun close() {
        closed = true
        val proxy = hid ?: return
        host?.let { proxy.disconnect(it) }
        proxy.unregisterApp()
        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        hid = null
        host = null
    }

    companion object {
        private const val SEND_ATTEMPTS = 40
        private const val RETRY_DELAY_MS = 5L
        const val UNSUPPORTED =
            "Телефон не поддерживает режим Bluetooth-клавиатуры. Включите режим «Сервер на Pi» в настройках."
    }
}
