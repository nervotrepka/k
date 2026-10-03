package io.github.nervotrepka.pitouch

import android.content.Context
import io.github.nervotrepka.pitouch.bt.Mode
import io.github.nervotrepka.pitouch.hid.LayoutToggle

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pitouch", Context.MODE_PRIVATE)

    var mode: Mode
        get() = enumOr(sp.getString("mode", null), Mode.HID)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var deviceAddress: String?
        get() = sp.getString("device", null)
        set(v) = sp.edit().putString("device", v).apply()

    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 1f)
        set(v) = sp.edit().putFloat("sensitivity", v).apply()

    var acceleration: Float
        get() = sp.getFloat("acceleration", 1f)
        set(v) = sp.edit().putFloat("acceleration", v).apply()

    var scrollSpeed: Float
        get() = sp.getFloat("scroll", 1f)
        set(v) = sp.edit().putFloat("scroll", v).apply()

    var gyroSensitivity: Float
        get() = sp.getFloat("gyro", 1f)
        set(v) = sp.edit().putFloat("gyro", v).apply()

    var naturalScroll: Boolean
        get() = sp.getBoolean("natural", true)
        set(v) = sp.edit().putBoolean("natural", v).apply()

    var tapToClick: Boolean
        get() = sp.getBoolean("tap", true)
        set(v) = sp.edit().putBoolean("tap", v).apply()

    var suggestions: Boolean
        get() = sp.getBoolean("suggestions", false)
        set(v) = sp.edit().putBoolean("suggestions", v).apply()

    var layoutToggle: LayoutToggle
        get() = enumOr(sp.getString("toggle", null), LayoutToggle.ALT_SHIFT)
        set(v) = sp.edit().putString("toggle", v.name).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
