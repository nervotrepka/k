package io.github.nervotrepka.pitouch.devices

import android.content.Context
import io.github.nervotrepka.pitouch.bt.Mode
import io.github.nervotrepka.pitouch.hid.LayoutToggle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class DeviceKind(val title: String, val toggle: LayoutToggle, val keyDelayMs: Int) {
    PI("Raspberry Pi / Linux", LayoutToggle.ALT_SHIFT, 0),
    WINDOWS("ПК с Windows", LayoutToggle.ALT_SHIFT, 0),
    ANDROID("Android-планшет", LayoutToggle.CTRL_SPACE, 0),
    TV("Samsung TV", LayoutToggle.ALT_SHIFT, 25),
    OTHER("Другое", LayoutToggle.ALT_SHIFT, 0),
}

/** A target the phone controls: over Bluetooth ([btAddress]) and/or a Samsung TV over Wi-Fi ([ip]). */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: DeviceKind,
    val btAddress: String? = null,
    val btMode: Mode = Mode.HID,
    val ip: String? = null,
    val tvMac: String? = null,
    val tvToken: String? = null,
    val toggle: LayoutToggle = kind.toggle,
    val keyDelayMs: Int = kind.keyDelayMs,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("kind", kind.name)
        .put("bt", btAddress ?: "")
        .put("btMode", btMode.name)
        .put("ip", ip ?: "")
        .put("tvMac", tvMac ?: "")
        .put("tvToken", tvToken ?: "")
        .put("toggle", toggle.name)
        .put("keyDelay", keyDelayMs)

    companion object {
        fun fromJson(o: JSONObject): Device {
            val kind = enumOr(o.optString("kind"), DeviceKind.OTHER)
            return Device(
                id = o.getString("id"),
                name = o.optString("name", "?"),
                kind = kind,
                btAddress = o.optString("bt").ifEmpty { null },
                btMode = enumOr(o.optString("btMode"), Mode.HID),
                ip = o.optString("ip").ifEmpty { null },
                tvMac = o.optString("tvMac").ifEmpty { null },
                tvToken = o.optString("tvToken").ifEmpty { null },
                toggle = enumOr(o.optString("toggle"), kind.toggle),
                keyDelayMs = o.optInt("keyDelay", kind.keyDelayMs),
            )
        }
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

/** Saved devices and the selected one. */
class DeviceStore(context: Context) {
    private val sp = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    var devices: List<Device> = load()
        private set

    var selectedId: String?
        get() = sp.getString("selected", null)
        set(v) = sp.edit().putString("selected", v).apply()

    val selected: Device? get() = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()

    fun byBluetooth(address: String): Device? = devices.firstOrNull { it.btAddress == address }

    fun save(device: Device) {
        val i = devices.indexOfFirst { it.id == device.id }
        devices = if (i >= 0) devices.toMutableList().also { it[i] = device } else devices + device
        persist()
    }

    fun delete(id: String) {
        devices = devices.filter { it.id != id }
        persist()
    }

    private fun load(): List<Device> {
        val raw = sp.getString("list", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { Device.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        sp.edit().putString("list", array.toString()).apply()
    }
}
