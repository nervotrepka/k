package io.github.nervotrepka.pitouch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.nervotrepka.pitouch.bt.Connection
import io.github.nervotrepka.pitouch.bt.LinkState
import io.github.nervotrepka.pitouch.bt.Mode
import io.github.nervotrepka.pitouch.hid.Keyboard
import io.github.nervotrepka.pitouch.hid.Layout
import io.github.nervotrepka.pitouch.hid.LayoutToggle
import io.github.nervotrepka.pitouch.hid.Mod
import io.github.nervotrepka.pitouch.hid.ModState
import io.github.nervotrepka.pitouch.hid.MouseButton
import io.github.nervotrepka.pitouch.hid.Usage
import io.github.nervotrepka.pitouch.ui.GyroMouse
import io.github.nervotrepka.pitouch.ui.KeyCaptureView
import io.github.nervotrepka.pitouch.ui.TouchpadView

@SuppressLint("MissingPermission", "SetTextI18n", "ClickableViewAccessibility")
class MainActivity : Activity(), Connection.Listener, KeyCaptureView.Sink {

    private lateinit var prefs: Prefs
    private lateinit var connection: Connection
    private lateinit var keyboard: Keyboard
    private lateinit var gyro: GyroMouse

    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var touchpad: TouchpadView
    private lateinit var keyCapture: KeyCaptureView
    private lateinit var layoutButton: Button
    private lateinit var gyroButton: Button
    private val modifierButtons = mutableMapOf<Int, Button>()

    private var gyroEnabled = false
    private var imeShown = false

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        connection = Connection(this, this)
        keyboard = Keyboard(connection).apply { onChange = ::refreshKeys }
        gyro = GyroMouse(this) { dx, dy -> connection.mouseMove(dx, dy) }
        setContentView(buildUi())
        applyPrefs()
        refreshKeys()
        onConnectionState(LinkState.DISCONNECTED, null, null)
        startBluetooth()
    }

    override fun onResume() {
        super.onResume()
        if (gyroEnabled) gyro.start()
    }

    override fun onPause() {
        super.onPause()
        gyro.stop()
    }

    override fun onDestroy() {
        connection.close()
        super.onDestroy()
    }

    // region Bluetooth

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    private fun startBluetooth() {
        val adapter = connection.adapter
        if (adapter == null) {
            status.text = "На телефоне нет Bluetooth"
            return
        }
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), RC_PERMISSIONS)
            return
        }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), RC_ENABLE_BT)
            return
        }
        connection.start(prefs.mode)
        connectToSaved()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != RC_PERMISSIONS) return
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBluetooth()
        } else {
            status.text = "Нужно разрешение на Bluetooth"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_ENABLE_BT && resultCode == RESULT_OK) startBluetooth()
    }

    private fun savedDevice(): BluetoothDevice? {
        val address = prefs.deviceAddress ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        return connection.adapter?.getRemoteDevice(address)
    }

    private fun connectToSaved() {
        val device = savedDevice() ?: return
        keyboard.reset()
        connection.connect(device)
    }

    private fun onConnectClicked() {
        when {
            connection.mode == null -> startBluetooth()
            connection.state == LinkState.CONNECTED || connection.state == LinkState.CONNECTING ->
                connection.disconnect()
            savedDevice() != null -> connectToSaved()
            else -> pickDevice()
        }
    }

    private fun pickDevice() {
        val adapter = connection.adapter ?: return
        if (!adapter.isEnabled) {
            startBluetooth()
            return
        }
        val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            toast("Нет сопряжённых устройств. Сначала выполните сопряжение с Pi — см. «Справка» в настройках.")
            return
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Выберите Raspberry Pi")
            .setItems(devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()) { _, i ->
                prefs.deviceAddress = devices[i].address
                connectToSaved()
            }
            .show()
    }

    override fun onConnectionState(state: LinkState, device: BluetoothDevice?, error: String?) {
        if (error != null) toast(error)
        val name = device?.name ?: device?.address ?: ""
        val (text, color) = when (state) {
            LinkState.CONNECTED -> "● Подключено: $name" to Color.rgb(0x5C, 0xD6, 0x8A)
            LinkState.CONNECTING -> "● Подключение… $name" to Color.rgb(0xF2, 0xB8, 0x4B)
            LinkState.WAITING -> "● Ожидание Pi (режим клавиатуры)" to Color.rgb(0xF2, 0xB8, 0x4B)
            LinkState.DISCONNECTED -> "● Не подключено" to Color.rgb(0xE5, 0x6B, 0x6B)
        }
        status.text = text
        status.setTextColor(color)
        connectButton.text =
            if (state == LinkState.CONNECTED || state == LinkState.CONNECTING) "Отключить" else "Подключить"
        if (state == LinkState.CONNECTED && device != null) {
            if (prefs.deviceAddress != device.address) prefs.deviceAddress = device.address
            keyboard.reset()
        }
    }

    // endregion

    // region KeyCaptureView.Sink

    override fun typeText(text: String) = keyboard.typeText(text)

    override fun backspace(count: Int) = repeat(count) { keyboard.tap(Usage.BACKSPACE) }

    override fun key(usage: Int) = keyboard.tap(usage)

    // endregion

    // region UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            textSize = 14f
            setOnClickListener { pickDevice() }
        }
        connectButton = keyButton("Подключить").apply { setOnClickListener { onConnectClicked() } }
        val settings = keyButton("⚙").apply { setOnClickListener { showSettings() } }
        top.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(connectButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)))
        top.addView(settings, LinearLayout.LayoutParams(dp(48), dp(42)).apply { leftMargin = dp(6) })
        root.addView(top)

        touchpad = TouchpadView(this).apply { output = connection }
        root.addView(touchpad, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        root.addView(row(
            mouseButton("ЛКМ", MouseButton.LEFT) to 3f,
            mouseButton("СКМ", MouseButton.MIDDLE) to 1.4f,
            mouseButton("ПКМ", MouseButton.RIGHT) to 3f,
            height = 54,
        ))

        layoutButton = keyButton("EN").apply {
            setOnClickListener { keyboard.switchLayout() }
            setOnLongClickListener {
                keyboard.switchLayout(send = false)
                toast("Индикатор раскладки исправлен без отправки на Pi")
                true
            }
        }
        root.addView(row(
            holdKey("Esc", Usage.ESC) to 1f,
            holdKey("Tab", Usage.TAB) to 1f,
            modifierKey("Ctrl", Mod.CTRL) to 1f,
            modifierKey("Alt", Mod.ALT) to 1f,
            modifierKey("Shift", Mod.SHIFT) to 1.2f,
            modifierKey("Win", Mod.SUPER) to 1f,
            layoutButton to 1.1f,
        ))
        root.addView(row(
            holdKey("←", Usage.LEFT) to 1f,
            holdKey("↓", Usage.DOWN) to 1f,
            holdKey("↑", Usage.UP) to 1f,
            holdKey("→", Usage.RIGHT) to 1f,
            holdKey("Home", Usage.HOME) to 1.2f,
            holdKey("End", Usage.END) to 1.1f,
            holdKey("Del", Usage.DELETE) to 1f,
            holdKey("⌫", Usage.BACKSPACE) to 1f,
            holdKey("⏎", Usage.ENTER) to 1f,
        ))

        val fKeys = (1..12).map { tapKey("F$it", Usage.f(it)) } + listOf(
            tapKey("PgUp", Usage.PAGE_UP), tapKey("PgDn", Usage.PAGE_DOWN),
            tapKey("Ins", Usage.INSERT), tapKey("PrtSc", Usage.PRINT_SCREEN),
        )
        root.addView(scrollRow(fKeys))
        root.addView(scrollRow(listOf(
            tapKey("Ctrl+C", Usage.letter('c'), Mod.CTRL),
            tapKey("Ctrl+V", Usage.letter('v'), Mod.CTRL),
            tapKey("Ctrl+X", Usage.letter('x'), Mod.CTRL),
            tapKey("Ctrl+Z", Usage.letter('z'), Mod.CTRL),
            tapKey("Ctrl+A", Usage.letter('a'), Mod.CTRL),
            tapKey("Ctrl+S", Usage.letter('s'), Mod.CTRL),
            tapKey("Alt+Tab", Usage.TAB, Mod.ALT),
            tapKey("Alt+F4", Usage.f(4), Mod.ALT),
            tapKey("Терминал", Usage.letter('t'), Mod.CTRL or Mod.ALT),
            tapKey("Ctrl+Shift+C", Usage.letter('c'), Mod.CTRL or Mod.SHIFT),
            tapKey("Ctrl+Shift+V", Usage.letter('v'), Mod.CTRL or Mod.SHIFT),
        )))

        val keyboardButton = keyButton("⌨  Клавиатура").apply { setOnClickListener { toggleIme() } }
        gyroButton = keyButton("◎  Гироскоп").apply { setOnClickListener { toggleGyro() } }
        root.addView(row(keyboardButton to 1f, gyroButton to 1f, height = 50))

        keyCapture = KeyCaptureView(this).apply { sink = this@MainActivity }
        root.addView(keyCapture, LinearLayout.LayoutParams(1, 1))
        return root
    }

    private fun row(vararg views: Pair<View, Float>, height: Int = 46): LinearLayout =
        LinearLayout(this).apply {
            for ((v, weight) in views) {
                addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                })
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(height)).apply { topMargin = dp(5) }
        }

    private fun scrollRow(views: List<View>): HorizontalScrollView {
        val inner = LinearLayout(this)
        for (v in views) {
            inner.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(5) }
        }
    }

    private fun keyBackground(color: Int): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(c)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(KEY_PRESSED))
            addState(intArrayOf(), shape(color))
        }
    }

    private fun keyButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minWidth = dp(48)
        minimumWidth = dp(48)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        stateListAnimator = null
        background = keyBackground(KEY)
    }

    /** Key held down while touched, so the Pi's auto-repeat works (arrows, Backspace). */
    private fun holdKey(label: String, usage: Int, modifiers: Int = 0) = keyButton(label).apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    keyboard.press(usage, modifiers)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    keyboard.release()
                }
            }
            true
        }
    }

    /** Key sent on click; used in scrollable rows so scrolling doesn't press keys. */
    private fun tapKey(label: String, usage: Int, modifiers: Int = 0) = keyButton(label).apply {
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            keyboard.tap(usage, modifiers)
        }
    }

    private fun modifierKey(label: String, mod: Int) = keyButton(label).apply {
        modifierButtons[mod] = this
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            keyboard.cycleModifier(mod)
        }
    }

    private fun mouseButton(label: String, button: Int) = keyButton(label).apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    connection.mouseButton(button, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    connection.mouseButton(button, false)
                }
            }
            true
        }
    }

    private fun refreshKeys() {
        if (!::layoutButton.isInitialized) return
        for ((mod, button) in modifierButtons) {
            button.background = keyBackground(
                when (keyboard.modifierState(mod)) {
                    ModState.OFF -> KEY
                    ModState.ONCE -> ONCE
                    ModState.LOCKED -> LOCKED
                }
            )
        }
        layoutButton.text = if (keyboard.layout == Layout.RU) "RU" else "EN"
        layoutButton.background = keyBackground(if (keyboard.layout == Layout.RU) RU_COLOR else KEY)
    }

    private fun toggleIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        val visible = if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            imeShown
        }
        if (visible) {
            imm.hideSoftInputFromWindow(keyCapture.windowToken, 0)
            imeShown = false
        } else {
            keyCapture.requestFocus()
            imm.restartInput(keyCapture)
            imm.showSoftInput(keyCapture, 0)
            imeShown = true
        }
    }

    private fun toggleGyro() {
        if (!gyro.available) {
            toast("На телефоне нет гироскопа")
            return
        }
        gyroEnabled = !gyroEnabled
        if (gyroEnabled) gyro.start() else gyro.stop()
        gyroButton.background = keyBackground(if (gyroEnabled) LOCKED else KEY)
        if (gyroEnabled) toast("Держите телефон горизонтально, верхом к монитору")
    }

    private fun applyPrefs() {
        touchpad.sensitivity = prefs.sensitivity
        touchpad.acceleration = prefs.acceleration
        touchpad.scrollSpeed = prefs.scrollSpeed
        touchpad.naturalScroll = prefs.naturalScroll
        touchpad.tapToClick = prefs.tapToClick
        gyro.sensitivity = prefs.gyroSensitivity
        keyCapture.suggestions = prefs.suggestions
        keyboard.toggle = prefs.layoutToggle
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(0xB0, 0xB4, 0xBC))
            setPadding(0, dp(12), 0, dp(2))
        }

        content.addView(label("Режим подключения"))
        val modes = RadioGroup(this)
        val hidRadio = RadioButton(this).apply { text = "Bluetooth-клавиатура (на Pi ничего не нужно)"; id = View.generateViewId() }
        val serverRadio = RadioButton(this).apply { text = "Сервер на Pi (если первый режим не работает)"; id = View.generateViewId() }
        modes.addView(hidRadio)
        modes.addView(serverRadio)
        modes.check(if (prefs.mode == Mode.HID) hidRadio.id else serverRadio.id)
        content.addView(modes)

        content.addView(Button(this).apply {
            text = "Выбрать Raspberry Pi"
            setOnClickListener { pickDevice() }
        })
        content.addView(Button(this).apply {
            text = "Сделать телефон видимым (для сопряжения)"
            setOnClickListener {
                startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                )
            }
        })

        val sensitivity = slider(content, "Скорость курсора", prefs.sensitivity)
        val acceleration = slider(content, "Ускорение курсора", prefs.acceleration, min = 0f)
        val scroll = slider(content, "Скорость прокрутки", prefs.scrollSpeed)
        val gyroSpeed = slider(content, "Чувствительность гироскопа", prefs.gyroSensitivity)

        fun switch(text: String, checked: Boolean) = Switch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            content.addView(this)
        }
        val natural = switch("Естественная прокрутка (как на телефоне)", prefs.naturalScroll)
        val tap = switch("Тап по тачпаду = клик", prefs.tapToClick)
        val suggest = switch("Подсказки и автозамена клавиатуры телефона", prefs.suggestions)

        content.addView(label("Переключение раскладки на Pi"))
        val toggles = LayoutToggle.values()
        val toggleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, toggles.map { it.title }
            )
            setSelection(toggles.indexOf(prefs.layoutToggle))
        }
        content.addView(toggleSpinner)

        content.addView(label("Справка"))
        content.addView(TextView(this).apply {
            text = HELP
            textSize = 13f
        })

        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.sensitivity = sensitivity()
                prefs.acceleration = acceleration()
                prefs.scrollSpeed = scroll()
                prefs.gyroSensitivity = gyroSpeed()
                prefs.naturalScroll = natural.isChecked
                prefs.tapToClick = tap.isChecked
                if (prefs.suggestions != suggest.isChecked) {
                    prefs.suggestions = suggest.isChecked
                    imeShown = false
                }
                prefs.layoutToggle = toggles[toggleSpinner.selectedItemPosition]
                applyPrefs()
                val mode = if (modes.checkedRadioButtonId == hidRadio.id) Mode.HID else Mode.SERVER
                if (mode != prefs.mode) {
                    prefs.mode = mode
                    startBluetooth()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Adds a 0.1–4.0 slider and returns its getter. */
    private fun slider(parent: LinearLayout, title: String, value: Float, min: Float = 0.1f): () -> Float {
        val text = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        val bar = SeekBar(this).apply {
            max = 40
            progress = (value * 10).toInt().coerceIn(0, 40)
        }
        fun current() = maxOf(bar.progress / 10f, min)
        fun update() {
            text.text = "$title: ${"%.1f".format(current())}"
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        update()
        parent.addView(text)
        parent.addView(bar)
        return ::current
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // endregion

    private companion object {
        const val RC_PERMISSIONS = 1
        const val RC_ENABLE_BT = 2
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

        val BG = Color.rgb(0x15, 0x17, 0x1C)
        val KEY = Color.rgb(0x2C, 0x30, 0x38)
        val KEY_PRESSED = Color.rgb(0x4A, 0x50, 0x5C)
        val ONCE = Color.rgb(0xB5, 0x84, 0x1F)
        val LOCKED = Color.rgb(0x2E, 0x8B, 0x57)
        val RU_COLOR = Color.rgb(0x2F, 0x5D, 0xA8)

        const val HELP = """Режим «Bluetooth-клавиатура»:
1. Если телефон уже был сопряжён с Pi — удалите сопряжение на обоих устройствах.
2. Откройте приложение в этом режиме и нажмите «Сделать телефон видимым».
3. На Pi: значок Bluetooth → Add Device → выберите телефон (или bluetoothctl: pair, trust, connect).
4. После этого Pi будет видеть телефон как клавиатуру и мышь.

Режим «Сервер на Pi»:
1. На Pi выполните install.sh из папки pi (см. README).
2. Выполните обычное сопряжение телефона с Pi.
3. Нажмите «Выбрать Raspberry Pi» и выберите его.

Русский язык: на Pi должны быть раскладки us,ru с тем же сочетанием переключения
(скрипт pi/setup-keyboard-layout.sh). Кнопка EN/RU переключает раскладку,
долгое нажатие — только исправляет индикатор, если он сбился.

Ctrl/Alt/Shift/Win: одно нажатие — для следующей клавиши (жёлтая),
второе — зафиксировать (зелёная), третье — выключить."""
    }
}
