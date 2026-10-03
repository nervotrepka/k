package io.github.nervotrepka.pitouch.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.nervotrepka.pitouch.hid.HidOutput
import io.github.nervotrepka.pitouch.hid.HidReports
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class Mode { HID, SERVER }

/** Owns the active transport and turns input calls into reports sent on a background thread. */
class Connection(private val context: Context, private val listener: Listener) : HidOutput {

    fun interface Listener {
        /** Always called on the main thread. */
        fun onConnectionState(state: LinkState, device: BluetoothDevice?, error: String?)
    }

    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    var state = LinkState.DISCONNECTED
        private set
    var mode: Mode? = null
        private set

    /** Pause after each key report; slow receivers (TVs) drop keys typed too fast. */
    @Volatile var keyDelayMs = 0

    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private var transport: Transport? = null
    private var buttons = 0

    fun start(newMode: Mode) {
        if (newMode == mode && transport != null) return
        val bt = adapter ?: return
        transport?.close()
        mode = newMode
        lateinit var created: Transport
        val callback = Transport.Callback { state, device, error ->
            main.post { if (transport === created) update(state, device, error) }
        }
        created = when (newMode) {
            Mode.HID -> HidDeviceTransport(context, bt, callback)
            Mode.SERVER -> RfcommTransport(bt, callback)
        }
        transport = created
        state = LinkState.DISCONNECTED
        created.start()
    }

    private fun update(newState: LinkState, device: BluetoothDevice?, error: String?) {
        state = newState
        if (newState != LinkState.CONNECTED) buttons = 0
        listener.onConnectionState(newState, device, error)
    }

    fun connect(device: BluetoothDevice) = transport?.connect(device)

    fun disconnect() = transport?.disconnect()

    fun close() {
        transport?.close()
        transport = null
        io.shutdown()
    }

    override fun keyboard(modifiers: Int, usage: Int) =
        send(HidReports.ID_KEYBOARD, HidReports.keyboard(modifiers, usage), paced = true)

    override fun pause(ms: Int) {
        if (state == LinkState.CONNECTED && !io.isShutdown) io.execute { Thread.sleep(ms.toLong()) }
    }

    /** Reports waiting to be sent (long text being typed). */
    val pending: Int get() = io.queue.size

    /** Drops queued reports (stops typing a long text) and releases all keys. */
    fun cancelPending() {
        io.queue.clear()
        keyboard(0, 0)
    }

    override fun consumer(usage: Int) =
        send(HidReports.ID_CONSUMER, HidReports.consumer(usage), paced = true)

    override fun mouseMove(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) send(HidReports.ID_MOUSE, HidReports.mouse(buttons, dx, dy, 0, 0))
    }

    override fun mouseScroll(wheel: Int, pan: Int) {
        if (wheel != 0 || pan != 0) send(HidReports.ID_MOUSE, HidReports.mouse(buttons, 0, 0, wheel, pan))
    }

    override fun mouseButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        send(HidReports.ID_MOUSE, HidReports.mouse(buttons, 0, 0, 0, 0))
    }

    private fun send(id: Int, report: ByteArray, paced: Boolean = false) {
        if (state != LinkState.CONNECTED || io.isShutdown) return
        val t = transport ?: return
        io.execute {
            t.send(id, report)
            val delay = keyDelayMs
            if (paced && delay > 0) Thread.sleep(delay.toLong())
        }
    }
}
