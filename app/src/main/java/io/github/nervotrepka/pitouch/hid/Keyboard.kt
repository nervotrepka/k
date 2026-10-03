package io.github.nervotrepka.pitouch.hid

/** Sink for HID input; implemented by the Bluetooth connection. */
interface HidOutput {
    /** Sends a keyboard report with one key ([usage] 0 = no key). */
    fun keyboard(modifiers: Int, usage: Int)
    fun mouseMove(dx: Int, dy: Int)
    fun mouseScroll(wheel: Int, pan: Int)
    fun mouseButton(button: Int, down: Boolean)
    /** Presses a consumer key ([usage] 0 = release). */
    fun consumer(usage: Int)
}

/** Hotkey that switches the keyboard layout on the Pi. */
enum class LayoutToggle(val title: String, val modifiers: Int, val usage: Int) {
    ALT_SHIFT("Alt+Shift", Mod.ALT or Mod.SHIFT, 0),
    CTRL_SHIFT("Ctrl+Shift", Mod.CTRL or Mod.SHIFT, 0),
    CTRL_SPACE("Ctrl+Space (Android)", Mod.CTRL, Usage.SPACE),
    SUPER_SPACE("Win+Space", Mod.SUPER, Usage.SPACE),
    CAPS_LOCK("Caps Lock", 0, Usage.CAPS_LOCK),
}

enum class ModState { OFF, ONCE, LOCKED }

/**
 * Typing logic: tracks the Pi's current layout (EN/RU), switches it when a character
 * needs the other one, and applies sticky modifiers from the on-screen panel.
 */
class Keyboard(private val out: HidOutput) {
    var layout = Layout.EN
        private set
    var toggle = LayoutToggle.ALT_SHIFT
    var onChange: (() -> Unit)? = null

    private var once = 0
    private var locked = 0

    fun modifierState(mod: Int): ModState = when {
        locked and mod != 0 -> ModState.LOCKED
        once and mod != 0 -> ModState.ONCE
        else -> ModState.OFF
    }

    /** OFF -> ONCE (next key only) -> LOCKED -> OFF. */
    fun cycleModifier(mod: Int) {
        when (modifierState(mod)) {
            ModState.OFF -> once = once or mod
            ModState.ONCE -> {
                once = once and mod.inv()
                locked = locked or mod
            }
            ModState.LOCKED -> locked = locked and mod.inv()
        }
        onChange?.invoke()
    }

    fun typeText(text: CharSequence) = text.forEach(::typeChar)

    fun typeChar(c: Char) {
        val target = KeyMapper.layoutFor(c, layout) ?: return
        if (target != layout) switchLayout()
        val stroke = KeyMapper.map(c, layout) ?: return
        tap(stroke.usage, stroke.modifiers)
    }

    fun tap(usage: Int, modifiers: Int = 0) {
        press(usage, modifiers)
        release()
    }

    fun press(usage: Int, modifiers: Int = 0) = out.keyboard(modifiers or once or locked, usage)

    fun release() {
        out.keyboard(0, 0)
        if (once != 0) {
            once = 0
            onChange?.invoke()
        }
    }

    /** Sends the layout hotkey to the Pi (if [send]) and flips the tracked layout. */
    fun switchLayout(send: Boolean = true) {
        if (send) {
            var held = 0
            for (bit in intArrayOf(Mod.CTRL, Mod.SHIFT, Mod.ALT, Mod.SUPER)) {
                if (toggle.modifiers and bit == 0) continue
                held = held or bit
                out.keyboard(held, 0)
            }
            if (toggle.usage != 0) out.keyboard(held, toggle.usage)
            out.keyboard(0, 0)
        }
        layout = layout.other()
        onChange?.invoke()
    }

    /** Called on (re)connect: a freshly booted Pi starts in the first (EN) layout. */
    fun reset() {
        layout = Layout.EN
        once = 0
        locked = 0
        onChange?.invoke()
    }
}
