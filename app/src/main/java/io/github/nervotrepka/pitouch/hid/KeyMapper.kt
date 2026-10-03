package io.github.nervotrepka.pitouch.hid

enum class Layout {
    EN, RU;

    fun other(): Layout = if (this == EN) RU else EN
}

data class KeyStroke(val usage: Int, val modifiers: Int = 0)

/**
 * Translates characters into key strokes for the standard XKB "us" and "ru" layouts.
 * HID sends key positions, not characters, so the Pi must have both layouts configured.
 */
object KeyMapper {
    /** Usage of every key in the order of the layout strings below. */
    private val KEYS = intArrayOf(
        0x35, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E,
        0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13, 0x2F, 0x30, 0x31,
        0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x33, 0x34,
        0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10, 0x36, 0x37, 0x38,
    )

    internal const val EN_PLAIN = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./"
    internal const val EN_SHIFT = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?"
    internal const val RU_PLAIN = "ё1234567890-=йцукенгшщзхъ\\фывапролджэячсмитьбю."
    internal const val RU_SHIFT = "Ё!\"№;%:?*()_+ЙЦУКЕНГШЩЗХЪ/ФЫВАПРОЛДЖЭЯЧСМИТЬБЮ,"

    private val COMMON = mapOf(
        ' ' to KeyStroke(Usage.SPACE),
        '\n' to KeyStroke(Usage.ENTER),
        '\r' to KeyStroke(Usage.ENTER),
        '\t' to KeyStroke(Usage.TAB),
    )

    private val tables = mapOf(
        Layout.EN to table(EN_PLAIN, EN_SHIFT),
        Layout.RU to table(RU_PLAIN, RU_SHIFT),
    )

    private fun table(plain: String, shifted: String): Map<Char, KeyStroke> {
        require(plain.length == KEYS.size && shifted.length == KEYS.size)
        val map = HashMap<Char, KeyStroke>()
        KEYS.forEachIndexed { i, usage ->
            map[plain[i]] = KeyStroke(usage)
            map[shifted[i]] = KeyStroke(usage, Mod.SHIFT)
        }
        return map
    }

    fun map(c: Char, layout: Layout): KeyStroke? = COMMON[c] ?: tables.getValue(layout)[c]

    /** Layout to type [c] in: [current] if possible, otherwise the other one, or null if neither. */
    fun layoutFor(c: Char, current: Layout): Layout? = when {
        map(c, current) != null -> current
        map(c, current.other()) != null -> current.other()
        else -> null
    }

    fun canType(c: Char): Boolean = layoutFor(c, Layout.EN) != null
}
