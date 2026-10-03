package io.github.nervotrepka.pitouch.hid

/** HID usage IDs (keyboard page 0x07) used by the app. */
object Usage {
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPS_LOCK = 0x39
    const val PRINT_SCREEN = 0x46
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    fun letter(c: Char): Int = 0x04 + (c.lowercaseChar() - 'a')

    /** F1..F12. */
    fun f(n: Int): Int = 0x39 + n
}

/** Modifier bits of the keyboard report (left-hand keys). */
object Mod {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val SUPER = 0x08
}

/** Consumer page (0x0C) usages: media and TV keys. */
object Consumer {
    const val CHANNEL_UP = 0x9C
    const val CHANNEL_DOWN = 0x9D
    const val FAST_FORWARD = 0xB3
    const val REWIND = 0xB4
    const val NEXT = 0xB5
    const val PREVIOUS = 0xB6
    const val STOP = 0xB7
    const val PLAY_PAUSE = 0xCD
    const val MUTE = 0xE2
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val HOME = 0x223
    const val BACK = 0x224
}

object MouseButton {
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
}

/**
 * Report descriptor and report builders. The same reports are used by both transports:
 * sent directly as a Bluetooth HID device, or framed over RFCOMM to the Pi server.
 */
object HidReports {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3
    const val KEYBOARD_SIZE = 8
    const val MOUSE_SIZE = 7
    const val CONSUMER_SIZE = 2

    fun size(id: Int): Int = when (id) {
        ID_KEYBOARD -> KEYBOARD_SIZE
        ID_MOUSE -> MOUSE_SIZE
        ID_CONSUMER -> CONSUMER_SIZE
        else -> 0
    }

    val DESCRIPTOR: ByteArray = bytes(
        // Keyboard: modifiers, reserved byte, LED output, 6 key slots.
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x95, 0x05, 0x75, 0x01, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x95, 0x06, 0x75, 0x08, 0x81, 0x00,
        0xC0,
        // Mouse: 5 buttons, 16-bit X/Y, wheel, horizontal pan.
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // Consumer control: one 16-bit usage (volume, media, Home, Back).
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    fun keyboard(modifiers: Int, usage: Int): ByteArray {
        val r = ByteArray(KEYBOARD_SIZE)
        r[0] = modifiers.toByte()
        r[2] = usage.toByte()
        return r
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray {
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        return byteArrayOf(
            buttons.toByte(),
            x.toByte(), (x shr 8).toByte(),
            y.toByte(), (y shr 8).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
            pan.coerceIn(-127, 127).toByte(),
        )
    }

    fun consumer(usage: Int): ByteArray = byteArrayOf(usage.toByte(), (usage shr 8).toByte())

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
