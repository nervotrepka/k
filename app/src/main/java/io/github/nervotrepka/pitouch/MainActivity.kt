package io.github.nervotrepka.pitouch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.nervotrepka.pitouch.bt.LinkState
import io.github.nervotrepka.pitouch.devices.DeviceKind
import io.github.nervotrepka.pitouch.hid.Layout
import io.github.nervotrepka.pitouch.hid.Mod
import io.github.nervotrepka.pitouch.hid.ModState
import io.github.nervotrepka.pitouch.hid.MouseButton
import io.github.nervotrepka.pitouch.hid.Usage
import io.github.nervotrepka.pitouch.tv.AndroidKey
import io.github.nervotrepka.pitouch.tv.RemoteKey
import io.github.nervotrepka.pitouch.tv.SamsungRemote
import io.github.nervotrepka.pitouch.tv.TvApps
import io.github.nervotrepka.pitouch.ui.GyroMouse
import io.github.nervotrepka.pitouch.ui.KeyCaptureView
import io.github.nervotrepka.pitouch.ui.TouchpadView
import io.github.nervotrepka.pitouch.ui.Widgets

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), Core.Listener, KeyCaptureView.Sink {

    private enum class Page(val title: String) { TOUCHPAD("Тачпад"), REMOTE("Пульт"), TEXT("Текст") }

    private val isAndroid get() = Core.current?.kind == DeviceKind.ANDROID
    private fun title(p: Page) = if (p == Page.REMOTE && isAndroid) "Навигация" else p.title

    private lateinit var w: Widgets
    private lateinit var dialogs: DeviceDialogs
    private lateinit var gyro: GyroMouse

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var touchpad: TouchpadView
    private lateinit var keyCapture: KeyCaptureView
    private lateinit var textInput: EditText
    private lateinit var echoBar: LinearLayout
    private lateinit var echoText: TextView
    private lateinit var shiftEnter: CheckBox
    private var sendProgress: TextView? = null
    private var stopButton: Button? = null
    private var layoutKind: DeviceKind? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val left = Core.connection.pending
            sendProgress?.text = if (left > 0) "Отправка… осталось ~${left / 2} нажатий" else ""
            stopButton?.visibility = if (left > 0) View.VISIBLE else View.GONE
            if (left > 0) handler.postDelayed(this, 200)
        }
    }

    // Rebuilt by relayout().
    private var deviceButton: Button? = null
    private var tvStatus: TextView? = null
    private var layoutButton: Button? = null
    private var gyroButton: Button? = null
    private val modifierButtons = mutableMapOf<Int, Button>()
    private val tabButtons = mutableMapOf<Page, Button>()

    private var page = Page.TOUCHPAD
    private var keysExpanded = false
    private var gyroEnabled = false
    private var imeShown = false

    private val keyboard get() = Core.keyboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w = Widgets(this)
        dialogs = DeviceDialogs(this)
        gyro = GyroMouse(this) { dx, dy -> Core.connection.mouseMove(dx, dy) }
        page = Page.values().firstOrNull { it.name == Core.prefs.page } ?: Page.TOUCHPAD

        touchpad = TouchpadView(this).apply { output = Core.connection }
        keyCapture = KeyCaptureView(this).apply { sink = this@MainActivity }
        textInput = EditText(this).apply {
            setText(Core.prefs.draft)
            hint = "Наберите текст здесь и нажмите «Отправить»"
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(Widgets.MUTED_TEXT)
            background = w.background(Widgets.KEY)
            setPadding(w.dp(12), w.dp(10), w.dp(12), w.dp(10))
        }
        echoText = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            ellipsize = TextUtils.TruncateAt.START
            setPadding(w.dp(10), w.dp(4), w.dp(6), w.dp(4))
        }
        echoBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = w.background(Widgets.KEY)
            addView(echoText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(w.button("✕", 14f) { Core.echoClear() }.apply { background = null },
                LinearLayout.LayoutParams(w.dp(40), w.dp(36)))
        }
        shiftEnter = CheckBox(this).apply {
            text = "Новая строка = Shift+Enter (для мессенджеров)"
            setTextColor(Widgets.MUTED_TEXT)
            textSize = 13f
        }
        content = FrameLayout(this)
        root = w.vertical().apply { setBackgroundColor(Widgets.BG) }
        setContentView(root)
        applyPrefs()
        relayout()
        startBluetooth()
    }

    override fun onStart() {
        super.onStart()
        Core.listeners += this
        refresh()
        // Back from background: retry a link that dropped meanwhile.
        val device = Core.current
        if (device != null && Core.bluetoothReady && device.btAddress != null &&
            Core.btState != LinkState.CONNECTED && Core.btState != LinkState.CONNECTING
        ) Core.connect(device)
    }

    override fun onStop() {
        Core.listeners -= this
        Core.prefs.draft = textInput.text.toString()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (gyroEnabled) gyro.start()
    }

    override fun onPause() {
        super.onPause()
        gyro.stop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
    }

    // region Bluetooth start

    private fun startBluetooth() {
        val adapter = Core.connection.adapter
        if (adapter == null) {
            toast("На телефоне нет Bluetooth")
            Core.start()
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), RC_PERMISSIONS)
            return
        }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), RC_ENABLE_BT)
            return
        }
        Core.start()
        if (Core.devices.devices.isEmpty()) showWelcome()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != RC_PERMISSIONS) return
        if (Core.hasBluetoothPermissions()) startBluetooth() else toast("Нужно разрешение на Bluetooth")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_ENABLE_BT && resultCode == RESULT_OK) startBluetooth()
    }

    private fun showWelcome() {
        AlertDialog.Builder(this, DeviceDialogs.THEME)
            .setTitle("Добро пожаловать")
            .setMessage(
                "Добавьте устройство, которым хотите управлять: Raspberry Pi, ПК, планшет или телевизор.\n\n" +
                    "Сначала выполните сопряжение по Bluetooth (см. ⚙ → Справка), затем добавьте устройство."
            )
            .setPositiveButton("Добавить") { _, _ -> dialogs.showEditor(null) }
            .setNegativeButton("Позже", null)
            .show()
    }

    // endregion

    // region Core.Listener

    override fun onCoreChanged() {
        if (Core.current?.kind != layoutKind) relayout() else refresh()
    }

    override fun onCoreMessage(message: String) = toast(message)

    override fun onExit() = finishAndRemoveTask()

    // endregion

    // region KeyCaptureView.Sink

    override fun typeText(text: String) {
        keyboard.typeText(text)
        Core.echoType(text)
    }

    override fun backspace(count: Int) {
        repeat(count) { keyboard.tap(Usage.BACKSPACE) }
        Core.echoType("\b".repeat(count))
    }

    override fun key(usage: Int) {
        keyboard.tap(usage)
        echoKey(usage)
    }

    private fun echoKey(usage: Int) {
        when (usage) {
            Usage.BACKSPACE -> Core.echoType("\b")
            Usage.ENTER -> Core.echoType("\n")
        }
    }

    // endregion

    // region layout

    private val widthDp get() = resources.configuration.screenWidthDp
    private val heightDp get() = resources.configuration.screenHeightDp
    /** Half-screen split, landscape phone: little vertical space. */
    private val compact get() = heightDp < 480
    /** Room for two columns side by side. */
    private val wide get() = widthDp >= 560 && widthDp > heightDp * 1.15

    private fun relayout() {
        root.removeAllViews()
        modifierButtons.clear()
        tabButtons.clear()
        val pad = if (compact) 4 else 8
        root.setPadding(w.dp(pad), w.dp(pad), w.dp(pad), w.dp(pad))

        deviceButton = w.button("", 13f) { dialogs.showList() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(w.dp(10), 0, w.dp(10), 0)
            isSingleLine = true
        }
        layoutKind = Core.current?.kind
        val tabs = Page.values().map { p -> p to w.button(title(p), 13f) { showPage(p) }.also { tabButtons[p] = it } }
        val settings = w.button("⚙", 16f) { showSettings() }
        val barHeight = if (compact) 38 else 42
        if (compact || wide) {
            root.addView(w.row(
                listOf(deviceButton!! to 2.2f) + tabs.map { it.second to 1f } + listOf(settings to 0.6f),
                barHeight, topMargin = 0,
            ))
        } else {
            root.addView(w.row(listOf(deviceButton!! to 1f, settings to 0.18f), barHeight, topMargin = 0))
            root.addView(w.row(tabs.map { it.second to 1f }, 38))
        }

        Widgets.detach(content)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = w.dp(4) })
        Widgets.detach(keyCapture)
        root.addView(keyCapture, LinearLayout.LayoutParams(1, 1))
        showPage(page)
    }

    private fun showPage(p: Page) {
        page = p
        Core.prefs.page = p.name
        if (p != Page.TOUCHPAD) hideIme()
        content.removeAllViews()
        val view = when (p) {
            Page.TOUCHPAD -> touchpadPage()
            Page.REMOTE -> if (isAndroid) androidPage() else remotePage()
            Page.TEXT -> textPage()
        }
        content.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        refresh()
    }

    private fun touchpadPage(): View {
        val keyHeight = if (compact) 38 else 44
        Widgets.detach(touchpad)
        val mouseRow = listOf(
            w.holdButton("ЛКМ", onDown = { Core.connection.mouseButton(MouseButton.LEFT, true) },
                onUp = { Core.connection.mouseButton(MouseButton.LEFT, false) }) to 3f,
            w.holdButton("СКМ", onDown = { Core.connection.mouseButton(MouseButton.MIDDLE, true) },
                onUp = { Core.connection.mouseButton(MouseButton.MIDDLE, false) }) to 1.3f,
            w.holdButton("ПКМ", onDown = { Core.connection.mouseButton(MouseButton.RIGHT, true) },
                onUp = { Core.connection.mouseButton(MouseButton.RIGHT, false) }) to 3f,
        )
        val tools = listOf(
            w.button("⌨", 18f) { toggleIme() } to 1f,
            w.button("◎", 18f) { toggleGyro() }.also { gyroButton = it } to 1f,
        )

        val pad = w.vertical()
        Widgets.detach(echoBar)
        pad.addView(echoBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = w.dp(4) })
        pad.addView(touchpad, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        if (wide) {
            pad.addView(w.row(mouseRow, if (compact) 44 else 54))
            val keys = w.vertical()
            keys.addView(w.row(tools, keyHeight, topMargin = 0))
            keyRows(keyHeight).forEach { keys.addView(it) }
            return LinearLayout(this).apply {
                addView(pad, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.25f))
                addView(ScrollView(this@MainActivity).apply { addView(keys) },
                    LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = w.dp(6) })
            }
        }
        if (compact) {
            pad.addView(w.row(mouseRow.map { it.first to 2f } + tools + listOf(keysToggleButton() to 1f), keyHeight))
            if (keysExpanded) keyRows(keyHeight).take(3).forEach { pad.addView(it) }
            return pad
        }
        pad.addView(w.row(mouseRow, 52))
        keyRows(keyHeight).forEach { pad.addView(it) }
        pad.addView(w.row(tools, keyHeight))
        return pad
    }

    private fun keysToggleButton(): Button =
        w.button(if (keysExpanded) "▾" else "⋯", 16f) {
            keysExpanded = !keysExpanded
            showPage(Page.TOUCHPAD)
        }

    private fun keyRows(height: Int): List<View> {
        layoutButton = w.button("EN") { keyboard.switchLayout() }.apply {
            setOnLongClickListener {
                keyboard.switchLayout(send = false)
                toast("Индикатор раскладки исправлен без отправки")
                true
            }
        }
        fun hold(label: String, usage: Int) = w.holdButton(label, onDown = {
            keyboard.press(usage)
            echoKey(usage)
        }, onUp = { keyboard.release() })
        fun mod(label: String, bit: Int) = w.button(label) { keyboard.cycleModifier(bit) }.also { modifierButtons[bit] = it }
        fun tap(label: String, usage: Int, mods: Int = 0) = w.button(label) { keyboard.tap(usage, mods) }

        val rowA = w.row(listOf(
            hold("Esc", Usage.ESC) to 1f, hold("Tab", Usage.TAB) to 1f, mod("Ctrl", Mod.CTRL) to 1f,
            mod("Alt", Mod.ALT) to 1f, mod("Shift", Mod.SHIFT) to 1.2f, mod("Win", Mod.SUPER) to 1f,
            layoutButton!! to 1.1f,
        ), height)
        val rowB = w.row(listOf(
            hold("←", Usage.LEFT) to 1f, hold("↓", Usage.DOWN) to 1f, hold("↑", Usage.UP) to 1f,
            hold("→", Usage.RIGHT) to 1f, hold("Home", Usage.HOME) to 1.2f, hold("End", Usage.END) to 1.1f,
            hold("Del", Usage.DELETE) to 1f, hold("⌫", Usage.BACKSPACE) to 1f, hold("⏎", Usage.ENTER) to 1f,
        ), height)
        val extra = (1..12).map { tap("F$it", Usage.f(it)) } + listOf(
            tap("PgUp", Usage.PAGE_UP), tap("PgDn", Usage.PAGE_DOWN), tap("Ins", Usage.INSERT),
            tap("PrtSc", Usage.PRINT_SCREEN),
        )
        val shortcuts = listOf(
            tap("Ctrl+C", Usage.letter('c'), Mod.CTRL), tap("Ctrl+V", Usage.letter('v'), Mod.CTRL),
            tap("Ctrl+X", Usage.letter('x'), Mod.CTRL), tap("Ctrl+Z", Usage.letter('z'), Mod.CTRL),
            tap("Ctrl+A", Usage.letter('a'), Mod.CTRL), tap("Ctrl+S", Usage.letter('s'), Mod.CTRL),
            tap("Alt+Tab", Usage.TAB, Mod.ALT), tap("Alt+F4", Usage.f(4), Mod.ALT),
            tap("Терминал", Usage.letter('t'), Mod.CTRL or Mod.ALT),
            tap("Win+D", Usage.letter('d'), Mod.SUPER), tap("Win+E", Usage.letter('e'), Mod.SUPER),
        )
        return if (compact) {
            listOf(rowA, rowB, w.scrollRow(shortcuts + extra, height))
        } else {
            listOf(rowA, rowB, w.scrollRow(extra, height), w.scrollRow(shortcuts, height))
        }
    }

    private fun remotePage(): View {
        fun key(k: RemoteKey, size: Float = 15f) = w.button(k.label, size) { Core.remote(k) }
        fun weighted(vararg views: Pair<View, Float>) =
            w.row(views.toList(), 0).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = w.dp(4) } }

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Widgets.MUTED_TEXT)
            setPadding(w.dp(4), 0, w.dp(4), 0)
        }
        val nav = w.vertical().apply {
            addView(weighted(key(RemoteKey.POWER, 18f) to 1f, key(RemoteKey.SOURCE, 13f) to 1f, key(RemoteKey.HOME, 13f) to 1f))
            addView(weighted(key(RemoteKey.MENU, 13f) to 1f, key(RemoteKey.UP, 18f) to 1f, key(RemoteKey.INFO, 13f) to 1f))
            addView(weighted(key(RemoteKey.LEFT, 18f) to 1f, w.button("OK", 18f, Widgets.ACCENT) { Core.remote(RemoteKey.OK) } to 1f,
                key(RemoteKey.RIGHT, 18f) to 1f))
            addView(weighted(key(RemoteKey.BACK, 13f) to 1f, key(RemoteKey.DOWN, 18f) to 1f, key(RemoteKey.EXIT, 13f) to 1f))
        }
        val media = w.vertical().apply {
            addView(weighted(key(RemoteKey.VOL_UP) to 1f, key(RemoteKey.MUTE, 17f) to 1f, key(RemoteKey.CH_UP) to 1f))
            addView(weighted(key(RemoteKey.VOL_DOWN) to 1f, w.button("123", 15f) { showDigits() } to 1f, key(RemoteKey.CH_DOWN) to 1f))
            addView(weighted(key(RemoteKey.REWIND, 17f) to 1f, key(RemoteKey.PLAY, 17f) to 1f, key(RemoteKey.PAUSE, 17f) to 1f,
                key(RemoteKey.FAST_FORWARD, 17f) to 1f))
            addView(weighted(
                w.button("YouTube", 13f, Widgets.DANGER) { Core.launchApp(TvApps.YOUTUBE) } to 1f,
                w.button("Тачпад", 13f) { showPage(Page.TOUCHPAD) } to 1f,
                w.button("Текст", 13f) { showPage(Page.TEXT) } to 1f,
            ))
        }
        val page = w.vertical()
        page.addView(tvStatus, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (wide || compact) {
            page.addView(LinearLayout(this).apply {
                addView(nav, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(media, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = w.dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            page.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            page.addView(media, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = w.dp(8) })
        }
        return page
    }

    private fun androidPage(): View {
        fun key(k: AndroidKey, size: Float = 14f, color: Int = Widgets.KEY) = w.button(k.label, size, color) { Core.androidKey(k) }
        fun weighted(vararg views: Pair<View, Float>) =
            w.row(views.toList(), 0).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = w.dp(4) } }

        val nav = w.vertical().apply {
            addView(weighted(key(AndroidKey.BACK, 15f) to 1f, key(AndroidKey.HOME, 15f) to 1f, key(AndroidKey.RECENTS, 15f) to 1f))
            addView(weighted(key(AndroidKey.SHIFT_TAB) to 1f, key(AndroidKey.UP, 18f) to 1f, key(AndroidKey.TAB) to 1f))
            addView(weighted(key(AndroidKey.LEFT, 18f) to 1f, key(AndroidKey.OK, 18f, Widgets.ACCENT) to 1f, key(AndroidKey.RIGHT, 18f) to 1f))
            addView(weighted(key(AndroidKey.NOTIFICATIONS, 13f) to 1f, key(AndroidKey.DOWN, 18f) to 1f, key(AndroidKey.APPS, 13f) to 1f))
        }
        val media = w.vertical().apply {
            addView(weighted(key(AndroidKey.VOL_DOWN) to 1f, key(AndroidKey.MUTE, 17f) to 1f, key(AndroidKey.VOL_UP) to 1f))
            addView(weighted(key(AndroidKey.PREVIOUS, 17f) to 1f, key(AndroidKey.PLAY_PAUSE, 17f) to 1f, key(AndroidKey.NEXT, 17f) to 1f))
            addView(weighted(key(AndroidKey.SEARCH, 13f) to 1f, key(AndroidKey.SWITCH_APP, 13f) to 1f, key(AndroidKey.SCREENSHOT, 13f) to 1f))
            addView(weighted(
                key(AndroidKey.POWER, 13f) to 1f,
                w.button("Тачпад", 13f) { showPage(Page.TOUCHPAD) } to 1f,
                w.button("Текст", 13f) { showPage(Page.TEXT) } to 1f,
            ))
        }
        val hint = TextView(this).apply {
            text = "Стрелки и OK двигают выделение по интерфейсу. На тачпаде: ПКМ = Назад, прокрутка двумя пальцами."
            textSize = 12f
            setTextColor(Widgets.MUTED_TEXT)
            setPadding(w.dp(4), 0, w.dp(4), 0)
        }
        val page = w.vertical()
        page.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (wide || compact) {
            page.addView(LinearLayout(this).apply {
                addView(nav, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(media, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = w.dp(8) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            page.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            page.addView(media, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = w.dp(8) })
        }
        return page
    }

    private fun showDigits() {
        val grid = w.vertical().apply { setPadding(w.dp(16), w.dp(8), w.dp(16), w.dp(8)) }
        RemoteKey.DIGITS.chunked(3).forEach { chunk ->
            val views = chunk.map { k -> w.button(k.label, 20f) { Core.remote(k) } to 1f }
            grid.addView(w.row(if (chunk.size == 1) listOf(w.spacer() to 1f) + views + listOf(w.spacer() to 1f) else views, 56))
        }
        AlertDialog.Builder(this, DeviceDialogs.THEME).setView(grid).setPositiveButton("Готово", null).show()
    }

    private fun textPage(): View {
        val page = w.vertical()
        Widgets.detach(textInput)
        page.addView(textInput, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        Widgets.detach(shiftEnter)
        sendProgress = TextView(this).apply {
            textSize = 13f
            setTextColor(Widgets.WARN)
        }
        stopButton = w.button("Стоп", 13f, Widgets.DANGER) {
            Core.connection.cancelPending()
            Core.keyboard.reset()
            toast("Отправка остановлена")
        }.apply { visibility = View.GONE }
        page.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(shiftEnter, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendProgress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(stopButton, LinearLayout.LayoutParams(WRAP_CONTENT, w.dp(36)).apply { leftMargin = w.dp(6) })
        })
        val h = if (compact) 40 else 48
        page.addView(w.row(listOf(
            w.button("Вставить", 13f) { paste() } to 1f,
            w.button("Очистить", 13f) { textInput.setText("") } to 1f,
            w.button("Отправить", 13f, Widgets.ACCENT) { sendText(enter = false) } to 1.3f,
            w.button("Отпр. + ⏎", 13f, Widgets.ACTIVE) { sendText(enter = true) } to 1.3f,
        ), h))
        return page
    }

    private fun sendText(enter: Boolean) {
        val text = textInput.text.toString()
        if (text.isEmpty() && !enter) {
            toast("Введите текст")
            return
        }
        Core.sendText(text, enter, shiftEnter.isChecked)
        if (Core.current?.ip != null && Core.tvState == SamsungRemote.State.CONNECTED) {
            toast("Отправлено. На ТВ должно быть открыто поле ввода")
        } else {
            handler.removeCallbacks(progressTick)
            handler.post(progressTick)
        }
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
        if (text.isNullOrEmpty()) toast("Буфер обмена пуст") else textInput.text.insert(textInput.selectionStart.coerceAtLeast(0), text)
    }

    // endregion

    private fun refresh() {
        val device = Core.current
        deviceButton?.let { b ->
            val (state, color) = when {
                device == null -> "добавьте устройство" to Widgets.BAD
                Core.btState == LinkState.CONNECTED || Core.tvState == SamsungRemote.State.CONNECTED ->
                    "подключено" to Widgets.GOOD
                Core.btState == LinkState.CONNECTING || Core.tvState == SamsungRemote.State.CONNECTING ->
                    "подключение…" to Widgets.WARN
                Core.btState == LinkState.WAITING && device.btAddress == null -> "ожидание" to Widgets.WARN
                else -> "нет связи" to Widgets.BAD
            }
            b.text = "● ${device?.name ?: "Устройство"} — $state ▾"
            b.setTextColor(color)
        }
        tvStatus?.text = when {
            device?.ip == null -> "Пульт по Bluetooth. Для всех кнопок укажите IP телевизора в настройках устройства."
            Core.tvState == SamsungRemote.State.CONNECTED -> "Wi-Fi: подключено к ${device.ip}"
            Core.tvState == SamsungRemote.State.CONNECTING -> "Wi-Fi: подключение к ${device.ip}…"
            else -> "Wi-Fi: нет связи с ${device.ip} (нажмите любую кнопку, чтобы переподключиться)"
        }
        val echo = Core.echo
        echoBar.visibility = if (echo.isEmpty() && !imeShown) View.GONE else View.VISIBLE
        echoText.maxLines = if (compact) 1 else 2
        echoText.text = echo.takeLast(300).ifEmpty { "Здесь появится набранный текст" }
        echoText.setTextColor(if (echo.isEmpty()) Widgets.MUTED_TEXT else android.graphics.Color.WHITE)
        for ((p, b) in tabButtons) b.background = w.background(if (p == page) Widgets.ACCENT else Widgets.KEY)
        for ((bit, b) in modifierButtons) b.background = w.background(
            when (keyboard.modifierState(bit)) {
                ModState.OFF -> Widgets.KEY
                ModState.ONCE -> Widgets.ONCE
                ModState.LOCKED -> Widgets.ACTIVE
            }
        )
        layoutButton?.let {
            it.text = if (keyboard.layout == Layout.RU) "RU" else "EN"
            it.background = w.background(if (keyboard.layout == Layout.RU) Widgets.ACCENT else Widgets.KEY)
        }
        gyroButton?.background = w.background(if (gyroEnabled) Widgets.ACTIVE else Widgets.KEY)
    }

    private fun toggleIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        val visible = if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            imeShown
        }
        if (visible) {
            hideIme()
        } else {
            keyCapture.requestFocus()
            imm.restartInput(keyCapture)
            imm.showSoftInput(keyCapture, 0)
            imeShown = true
            refresh()
        }
    }

    private fun hideIme() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        imeShown = false
    }

    private fun toggleGyro() {
        if (!gyro.available) {
            toast("На телефоне нет гироскопа")
            return
        }
        gyroEnabled = !gyroEnabled
        if (gyroEnabled) gyro.start() else gyro.stop()
        if (gyroEnabled) toast("Держите телефон горизонтально, верхом к экрану")
        refresh()
    }

    private fun applyPrefs() {
        val prefs = Core.prefs
        touchpad.sensitivity = prefs.sensitivity
        touchpad.acceleration = prefs.acceleration
        touchpad.scrollSpeed = prefs.scrollSpeed
        touchpad.naturalScroll = prefs.naturalScroll
        touchpad.tapToClick = prefs.tapToClick
        gyro.sensitivity = prefs.gyroSensitivity
        keyCapture.suggestions = prefs.suggestions
    }

    private fun showSettings() {
        val prefs = Core.prefs
        val content = w.vertical().apply { setPadding(w.dp(20), w.dp(8), w.dp(20), w.dp(8)) }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Widgets.MUTED_TEXT)
            setPadding(0, w.dp(12), 0, w.dp(2))
            content.addView(this)
        }
        fun action(text: String, onClick: () -> Unit) = content.addView(Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        })

        label("Устройства и подключение")
        action("Устройства…") { dialogs.showList() }
        action("Сделать телефон видимым (для сопряжения)") {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        }
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            action("Разрешить работу в фоне (не отключать связь)") {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }

        val sensitivity = slider(content, "Скорость курсора", prefs.sensitivity)
        val acceleration = slider(content, "Ускорение курсора", prefs.acceleration, min = 0f)
        val scroll = slider(content, "Скорость прокрутки", prefs.scrollSpeed)
        val gyroSpeed = slider(content, "Чувствительность гироскопа", prefs.gyroSensitivity)

        fun switch(text: String, checked: Boolean) = Switch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, w.dp(8), 0, w.dp(8))
            content.addView(this)
        }
        val natural = switch("Естественная прокрутка (как на телефоне)", prefs.naturalScroll)
        val tap = switch("Тап по тачпаду = клик", prefs.tapToClick)
        val suggest = switch("Подсказки и автозамена клавиатуры телефона", prefs.suggestions)

        label("Справка")
        content.addView(TextView(this).apply {
            text = HELP
            textSize = 13f
        })
        action("Выйти и отключиться") {
            startService(Intent(this, ConnectionService::class.java).setAction(ConnectionService.ACTION_EXIT))
        }

        AlertDialog.Builder(this, DeviceDialogs.THEME)
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
                applyPrefs()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Adds a 0.1–4.0 slider and returns its getter. */
    private fun slider(parent: LinearLayout, title: String, value: Float, min: Float = 0.1f): () -> Float {
        val text = TextView(this).apply { setPadding(0, w.dp(10), 0, 0) }
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

    private companion object {
        const val RC_PERMISSIONS = 1
        const val RC_ENABLE_BT = 2

        const val HELP = """Сопряжение (Pi, ПК, планшет, ТВ):
1. Если телефон уже был сопряжён с устройством — удалите сопряжение на обоих.
2. Откройте PiTouch и нажмите «Сделать телефон видимым».
3. На устройстве найдите телефон в списке Bluetooth и подключите:
   • Pi: значок Bluetooth → Add Device;
   • Windows: Параметры → Bluetooth → Добавить устройство;
   • Android: Настройки → Bluetooth → выбрать телефон;
   • Samsung TV: Настройки → Общие → Диспетчер внешних устройств → Bluetooth.
4. Устройство само появится в списке. Тип и раскладку можно поменять в «Устройства… → Изменить».

Wi-Fi пульт Samsung: в устройстве типа «Samsung TV» укажите IP или нажмите «Найти телевизор». При первом подключении нажмите «Разрешить» на ТВ.

Русский язык: на устройстве должна быть включена русская раскладка, а сочетание переключения совпадать с настройкой устройства. Долгое нажатие на EN/RU исправляет индикатор.

Ctrl/Alt/Shift/Win: одно нажатие — для следующей клавиши (жёлтая), второе — фиксация (зелёная), третье — выключить.

Связь не рвётся при сворачивании: в шторке висит уведомление PiTouch. Чтобы выйти — «Выход» в уведомлении."""
    }
}
