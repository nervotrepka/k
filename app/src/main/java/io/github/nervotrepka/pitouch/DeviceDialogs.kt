package io.github.nervotrepka.pitouch

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.nervotrepka.pitouch.bt.Mode
import io.github.nervotrepka.pitouch.devices.Device
import io.github.nervotrepka.pitouch.devices.DeviceKind
import io.github.nervotrepka.pitouch.hid.LayoutToggle
import io.github.nervotrepka.pitouch.ui.Widgets
import kotlin.concurrent.thread

/** Device list and device editor dialogs. */
@SuppressLint("MissingPermission", "SetTextI18n")
class DeviceDialogs(private val activity: Activity) {
    private val w = Widgets(activity)

    fun showList() {
        val devices = Core.devices.devices
        val selected = Core.current?.id
        val items = devices.map { (if (it.id == selected) "✓  " else "     ") + it.name + "  ·  " + it.kind.title }
        AlertDialog.Builder(activity, THEME)
            .setTitle("Устройства")
            .setItems(items.toTypedArray()) { _, i -> Core.select(devices[i]) }
            .setPositiveButton("Добавить") { _, _ -> showEditor(null) }
            .apply { if (Core.current != null) setNeutralButton("Изменить текущее") { _, _ -> showEditor(Core.current) } }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    fun showEditor(existing: Device?) {
        val content = w.vertical().apply { setPadding(w.dp(20), w.dp(4), w.dp(20), w.dp(4)) }
        fun label(text: String) = TextView(activity).apply {
            this.text = text
            setTextColor(Widgets.MUTED_TEXT)
            setPadding(0, w.dp(12), 0, w.dp(2))
            content.addView(this)
        }

        label("Название")
        val name = EditText(activity).apply {
            setText(existing?.name ?: "")
            hint = "Например: Телевизор в зале"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            content.addView(this)
        }

        label("Тип")
        val kinds = DeviceKind.values()
        val kindSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.title })
            setSelection(kinds.indexOf(existing?.kind ?: DeviceKind.PI))
            content.addView(this)
        }

        label("Bluetooth (тачпад, клавиатура, кнопки пульта)")
        var btAddress = existing?.btAddress
        val btButton = Button(activity)
        fun updateBt() {
            val bonded = Core.connection.adapter?.takeIf { Core.hasBluetoothPermissions() }?.bondedDevices
            val device = bonded?.firstOrNull { it.address == btAddress }
            btButton.text = when {
                btAddress == null -> "Не используется — выбрать…"
                device != null -> "${device.name ?: "?"} (${device.address})"
                else -> btAddress!!
            }
        }
        btButton.setOnClickListener { pickBluetooth { btAddress = it; updateBt() } }
        updateBt()
        content.addView(btButton)

        val modes = RadioGroup(activity)
        val hidRadio = RadioButton(activity).apply { text = "Как Bluetooth-клавиатура (обычно)"; id = View.generateViewId() }
        val serverRadio = RadioButton(activity).apply { text = "Через сервер на Pi"; id = View.generateViewId() }
        modes.addView(hidRadio)
        modes.addView(serverRadio)
        modes.check(if (existing?.btMode == Mode.SERVER) serverRadio.id else hidRadio.id)
        content.addView(modes)

        val tvLabel = label("Wi-Fi пульт Samsung: IP-адрес телевизора")
        val ip = EditText(activity).apply {
            setText(existing?.ip ?: "")
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            content.addView(this)
        }
        val find = Button(activity).apply {
            text = "Найти телевизор в сети"
            setOnClickListener { findTv { ip.setText(it) } }
            content.addView(this)
        }
        val tvHint = TextView(activity).apply {
            text = "Телефон и ТВ должны быть в одной Wi-Fi сети. При первом подключении разрешите доступ на экране ТВ."
            textSize = 12f
            setTextColor(Widgets.MUTED_TEXT)
            content.addView(this)
        }

        label("Переключение раскладки EN/RU на устройстве")
        val toggles = LayoutToggle.values()
        val toggleSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, toggles.map { it.title })
            setSelection(toggles.indexOf(existing?.toggle ?: DeviceKind.PI.toggle))
            content.addView(this)
        }

        val delayLabel = label("")
        val delay = SeekBar(activity).apply {
            max = 60
            progress = existing?.keyDelayMs ?: 0
            content.addView(this)
        }
        fun updateDelay() {
            delayLabel.text = "Пауза между клавишами: ${delay.progress} мс (увеличьте, если ТВ теряет буквы)"
        }
        delay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateDelay()
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        updateDelay()

        var firstSelection = true
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val kind = kinds[position]
                val tvVisible = if (kind == DeviceKind.TV) View.VISIBLE else View.GONE
                listOf(tvLabel, ip, find, tvHint).forEach { it.visibility = tvVisible }
                serverRadio.visibility = if (kind == DeviceKind.PI) View.VISIBLE else View.GONE
                if (kind != DeviceKind.PI) modes.check(hidRadio.id)
                // Keep stored values when the dialog opens; apply type defaults on user changes.
                if (firstSelection && existing != null) {
                    firstSelection = false
                    return
                }
                firstSelection = false
                toggleSpinner.setSelection(toggles.indexOf(kind.toggle))
                delay.progress = kind.keyDelayMs
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(activity, THEME)
            .setTitle(if (existing == null) "Новое устройство" else "Устройство")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .apply {
                if (existing != null) setNeutralButton("Удалить") { _, _ ->
                    AlertDialog.Builder(activity, THEME)
                        .setMessage("Удалить «${existing.name}»?")
                        .setPositiveButton("Удалить") { _, _ -> Core.delete(existing) }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kind = kinds[kindSpinner.selectedItemPosition]
            val ipText = ip.text.toString().trim().takeIf { it.isNotEmpty() && kind == DeviceKind.TV }
            if (btAddress == null && ipText == null) {
                toast("Выберите Bluetooth-устройство или укажите IP телевизора")
                return@setOnClickListener
            }
            val device = Device(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name = name.text.toString().trim().ifEmpty { kind.title },
                kind = kind,
                btAddress = btAddress,
                btMode = if (modes.checkedRadioButtonId == serverRadio.id) Mode.SERVER else Mode.HID,
                ip = ipText,
                tvMac = existing?.tvMac.takeIf { existing?.ip == ipText },
                tvToken = existing?.tvToken.takeIf { existing?.ip == ipText },
                toggle = toggles[toggleSpinner.selectedItemPosition],
                keyDelayMs = delay.progress,
            )
            Core.save(device)
            if (existing == null) Core.select(device)
            dialog.dismiss()
        }
    }

    private fun pickBluetooth(onPicked: (String?) -> Unit) {
        val adapter = Core.connection.adapter
        if (adapter == null || !Core.bluetoothReady) {
            toast("Bluetooth выключен или нет разрешения")
            return
        }
        val bonded: List<BluetoothDevice> = adapter.bondedDevices.sortedBy { it.name ?: it.address }
        val items = listOf("Не использовать Bluetooth") + bonded.map { "${it.name ?: "?"}\n${it.address}" }
        AlertDialog.Builder(activity, THEME)
            .setTitle("Сопряжённые устройства")
            .setItems(items.toTypedArray()) { _, i -> onPicked(if (i == 0) null else bonded[i - 1].address) }
            .setNegativeButton("Отмена", null)
            .apply {
                if (bonded.isEmpty()) setMessage("Нет сопряжённых устройств. Сначала выполните сопряжение (см. Справку).")
            }
            .show()
    }

    private fun findTv(onFound: (String) -> Unit) {
        val progress = AlertDialog.Builder(activity, THEME)
            .setMessage("Ищу телевизоры Samsung в сети…")
            .setCancelable(false)
            .show()
        thread(isDaemon = true) {
            val found = Core.tv.discover()
            activity.runOnUiThread {
                progress.dismiss()
                if (activity.isFinishing) return@runOnUiThread
                if (found.isEmpty()) {
                    AlertDialog.Builder(activity, THEME)
                        .setMessage(
                            "Телевизор не найден. Проверьте, что он включён и в той же Wi-Fi сети, " +
                                "или введите IP вручную (ТВ: Настройки → Общие → Сеть → Состояние сети → Настройки IP)."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(activity, THEME)
                        .setTitle("Найдено")
                        .setItems(found.map { "${it.name}\n${it.ip}" }.toTypedArray()) { _, i -> onFound(found[i].ip) }
                        .show()
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }
}
