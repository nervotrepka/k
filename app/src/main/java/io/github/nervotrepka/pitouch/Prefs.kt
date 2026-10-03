package io.github.nervotrepka.pitouch

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pitouch", Context.MODE_PRIVATE)

    /** Settings of version 1 (single device), migrated into the device list. */
    val legacyMode: String? get() = sp.getString("mode", null)
    val legacyDevice: String? get() = sp.getString("device", null)
    val legacyToggle: String? get() = sp.getString("toggle", null)

    fun clearLegacy() = sp.edit().remove("mode").remove("device").remove("toggle").apply()

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

    var page: String?
        get() = sp.getString("page", null)
        set(v) = sp.edit().putString("page", v).apply()

    var draft: String
        get() = sp.getString("draft", "") ?: ""
        set(v) = sp.edit().putString("draft", v).apply()
}
