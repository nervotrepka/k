package io.github.nervotrepka.pitouch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.nervotrepka.pitouch.bt.Connection
import io.github.nervotrepka.pitouch.bt.LinkState
import io.github.nervotrepka.pitouch.bt.Mode
import io.github.nervotrepka.pitouch.devices.Device
import io.github.nervotrepka.pitouch.devices.DeviceKind
import io.github.nervotrepka.pitouch.devices.DeviceStore
import io.github.nervotrepka.pitouch.devices.enumOr
import io.github.nervotrepka.pitouch.hid.KeyMapper
import io.github.nervotrepka.pitouch.hid.Keyboard
import io.github.nervotrepka.pitouch.hid.LayoutToggle
import io.github.nervotrepka.pitouch.hid.Usage
import io.github.nervotrepka.pitouch.tv.AndroidKey
import io.github.nervotrepka.pitouch.tv.RemoteKey
import io.github.nervotrepka.pitouch.tv.SamsungRemote

/**
 * Process-wide state shared by the activity and the foreground service, so the
 * connection survives the activity being closed or minimized.
 */
@SuppressLint("StaticFieldLeak", "MissingPermission")
object Core : Connection.Listener, SamsungRemote.Listener {

    interface Listener {
        fun onCoreChanged()
        fun onCoreMessage(message: String) {}
        fun onExit() {}
    }

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var devices: DeviceStore
        private set
    lateinit var connection: Connection
        private set
    lateinit var keyboard: Keyboard
        private set
    lateinit var tv: SamsungRemote
        private set

    var btState = LinkState.DISCONNECTED
        private set
    var btDeviceName: String? = null
        private set
    var tvState = SamsungRemote.State.DISCONNECTED
        private set

    val listeners = linkedSetOf<Listener>()
    private var initialized = false

    val current: Device? get() = devices.selected

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        devices = DeviceStore(app)
        connection = Connection(app, this)
        keyboard = Keyboard(connection).apply { onChange = ::notifyChanged }
        tv = SamsungRemote(app, this)
        migrate()
        current?.let { applySettings(it) }
    }

    /** Version 1 kept a single Bluetooth device in Prefs. */
    private fun migrate() {
        val address = prefs.legacyDevice ?: return
        if (devices.devices.isEmpty()) {
            val device = Device(
                name = "Raspberry Pi",
                kind = DeviceKind.PI,
                btAddress = address,
                btMode = enumOr(prefs.legacyMode, Mode.HID),
                toggle = enumOr(prefs.legacyToggle, LayoutToggle.ALT_SHIFT),
            )
            devices.save(device)
            devices.selectedId = device.id
        }
        prefs.clearLegacy()
    }

    fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < 31 || listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    val bluetoothReady: Boolean get() = connection.adapter?.isEnabled == true && hasBluetoothPermissions()

    /** Starts the foreground service and connects to the selected device. */
    fun start() {
        val intent = Intent(app, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        val device = current
        if (device != null) connect(device)
        else if (bluetoothReady) connection.start(Mode.HID) // let new hosts pair and connect to the phone
    }

    fun select(device: Device) {
        val previous = current
        devices.selectedId = device.id
        if (previous != null && previous.ip != null && previous.ip != device.ip) tv.disconnect()
        connect(device)
        notifyChanged()
    }

    /** (Re)connects Bluetooth and the TV link for [device]. */
    fun connect(device: Device) {
        applySettings(device)
        val address = device.btAddress
        if (address != null && bluetoothReady && BluetoothAdapter.checkBluetoothAddress(address)) {
            if (connection.mode != device.btMode) keyboard.reset()
            connection.start(device.btMode)
            connection.connect(connection.adapter!!.getRemoteDevice(address))
        } else if (bluetoothReady) {
            if (connection.mode == null) connection.start(Mode.HID) // so other hosts can still connect to the phone
            else connection.disconnect() // the previous device must not keep receiving input
        }
        device.ip?.let { tv.connect(it, device.tvMac, device.tvToken) }
    }

    fun disconnect() {
        connection.disconnect()
        tv.disconnect()
    }

    private fun applySettings(device: Device) {
        keyboard.toggle = device.toggle
        connection.keyDelayMs = device.keyDelayMs
    }

    fun save(device: Device) {
        devices.save(device)
        if (device.id == current?.id) connect(device)
        notifyChanged()
    }

    fun delete(device: Device) {
        devices.delete(device.id)
        if (devices.selectedId == device.id) devices.selectedId = devices.devices.firstOrNull()?.id
        notifyChanged()
    }

    // region actions

    /** Remote button: Wi-Fi for TVs with an IP, otherwise Bluetooth keys. */
    fun remote(key: RemoteKey) {
        val device = current
        if (device?.ip != null && (tvState == SamsungRemote.State.CONNECTED || !key.bluetoothSupported ||
                btState != LinkState.CONNECTED)
        ) {
            tv.key(key.samsung)
            return
        }
        when {
            btState != LinkState.CONNECTED -> message("Нет подключения")
            key.consumer != 0 -> {
                connection.consumer(key.consumer)
                connection.consumer(0)
            }
            key.keyboard != 0 -> keyboard.tap(key.keyboard)
            else -> message("Кнопка «${key.label}» работает только по Wi-Fi (укажите IP телевизора)")
        }
    }

    fun launchApp(appId: String) {
        if (current?.ip == null) message("Запуск приложений работает только по Wi-Fi (укажите IP телевизора)")
        else tv.launchApp(appId)
    }

    // region echo of typed text

    private val echoBuffer = StringBuilder()

    /** What was recently typed on the device, shown on the phone. */
    val echo: String get() = echoBuffer.toString()

    fun echoType(text: CharSequence) {
        for (c in text) {
            if (c == '\b') {
                if (echoBuffer.isNotEmpty()) echoBuffer.setLength(echoBuffer.length - 1)
            } else {
                echoBuffer.append(KeyMapper.substitute(c) ?: c.toString())
            }
        }
        if (echoBuffer.length > ECHO_MAX) echoBuffer.delete(0, echoBuffer.length - ECHO_MAX)
        notifyChanged()
    }

    fun echoClear() {
        echoBuffer.setLength(0)
        notifyChanged()
    }

    // endregion

    /** Android tablet navigation key (over Bluetooth). */
    fun androidKey(key: AndroidKey) {
        if (btState != LinkState.CONNECTED) {
            message("Нет подключения по Bluetooth")
            return
        }
        if (key.consumer != 0) {
            connection.consumer(key.consumer)
            connection.consumer(0)
        } else {
            keyboard.tap(key.usage, key.modifiers)
        }
    }

    /** Sends a whole text: via the TV's Wi-Fi input when possible, otherwise typed over Bluetooth. */
    fun sendText(text: String, enter: Boolean, shiftEnter: Boolean = false) {
        val device = current
        if (device?.ip != null && (tvState == SamsungRemote.State.CONNECTED || btState != LinkState.CONNECTED)) {
            tv.text(text, enter)
            return
        }
        if (btState != LinkState.CONNECTED) {
            message("Нет подключения")
            return
        }
        keyboard.shiftEnter = shiftEnter
        keyboard.typeText(text)
        keyboard.shiftEnter = false
        if (enter) keyboard.tap(Usage.ENTER)
        echoType(text + if (enter) "\n" else "")
    }

    private const val ECHO_MAX = 2000

    fun exit() {
        listeners.toList().forEach { it.onExit() }
        app.stopService(Intent(app, ConnectionService::class.java))
        tv.close()
        connection.close()
        initialized = false
    }

    // endregion

    // region callbacks

    override fun onConnectionState(state: LinkState, device: BluetoothDevice?, error: String?) {
        btState = state
        btDeviceName = device?.name ?: device?.address
        if (state == LinkState.CONNECTED && device != null) {
            keyboard.reset()
            val known = devices.byBluetooth(device.address)
            when {
                known == null -> {
                    // A host that paired and connected by itself: remember it.
                    val added = Device(
                        name = device.name ?: device.address,
                        kind = DeviceKind.OTHER,
                        btAddress = device.address,
                        btMode = connection.mode ?: Mode.HID,
                    )
                    devices.save(added)
                    devices.selectedId = added.id
                    applySettings(added)
                }
                known.id != current?.id -> {
                    devices.selectedId = known.id
                    applySettings(known)
                }
            }
        }
        error?.let(::message)
        notifyChanged()
    }

    override fun onTvState(state: SamsungRemote.State, error: String?) {
        tvState = state
        error?.let(::message)
        notifyChanged()
    }

    override fun onTvInfo(ip: String, mac: String?, token: String?) {
        val device = devices.devices.firstOrNull { it.ip == ip } ?: return
        devices.save(device.copy(tvMac = mac ?: device.tvMac, tvToken = token ?: device.tvToken))
    }

    private fun message(text: String) = listeners.toList().forEach { it.onCoreMessage(text) }

    private fun notifyChanged() = listeners.toList().forEach { it.onCoreChanged() }

    // endregion
}
