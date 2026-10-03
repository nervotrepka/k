package io.github.nervotrepka.pitouch.tv

import io.github.nervotrepka.pitouch.hid.Consumer
import io.github.nervotrepka.pitouch.hid.Usage

/**
 * A remote control button: Samsung Wi-Fi key code, plus a Bluetooth fallback
 * (consumer usage or keyboard usage; 0 = not available over Bluetooth).
 */
enum class RemoteKey(val label: String, val samsung: String, val consumer: Int = 0, val keyboard: Int = 0) {
    POWER("⏻", "KEY_POWER"),
    SOURCE("Источник", "KEY_SOURCE"),
    HOME("Домой", "KEY_HOME", consumer = Consumer.HOME),
    MENU("Меню", "KEY_MENU", keyboard = 0x65),
    UP("▲", "KEY_UP", keyboard = Usage.UP),
    DOWN("▼", "KEY_DOWN", keyboard = Usage.DOWN),
    LEFT("◀", "KEY_LEFT", keyboard = Usage.LEFT),
    RIGHT("▶", "KEY_RIGHT", keyboard = Usage.RIGHT),
    OK("OK", "KEY_ENTER", keyboard = Usage.ENTER),
    BACK("↩ Назад", "KEY_RETURN", consumer = Consumer.BACK),
    EXIT("Выход", "KEY_EXIT", keyboard = Usage.ESC),
    INFO("Инфо", "KEY_INFO"),
    VOL_UP("Гром +", "KEY_VOLUP", consumer = Consumer.VOLUME_UP),
    VOL_DOWN("Гром −", "KEY_VOLDOWN", consumer = Consumer.VOLUME_DOWN),
    MUTE("🔇", "KEY_MUTE", consumer = Consumer.MUTE),
    CH_UP("Кан +", "KEY_CHUP", consumer = Consumer.CHANNEL_UP),
    CH_DOWN("Кан −", "KEY_CHDOWN", consumer = Consumer.CHANNEL_DOWN),
    REWIND("⏪", "KEY_REWIND", consumer = Consumer.REWIND),
    PLAY("▶︎", "KEY_PLAY", consumer = Consumer.PLAY_PAUSE),
    PAUSE("⏸", "KEY_PAUSE", consumer = Consumer.PLAY_PAUSE),
    FAST_FORWARD("⏩", "KEY_FF", consumer = Consumer.FAST_FORWARD),
    STOP("⏹", "KEY_STOP", consumer = Consumer.STOP),
    D0("0", "KEY_0", keyboard = 0x27),
    D1("1", "KEY_1", keyboard = 0x1E),
    D2("2", "KEY_2", keyboard = 0x1F),
    D3("3", "KEY_3", keyboard = 0x20),
    D4("4", "KEY_4", keyboard = 0x21),
    D5("5", "KEY_5", keyboard = 0x22),
    D6("6", "KEY_6", keyboard = 0x23),
    D7("7", "KEY_7", keyboard = 0x24),
    D8("8", "KEY_8", keyboard = 0x25),
    D9("9", "KEY_9", keyboard = 0x26),
    ;

    val bluetoothSupported: Boolean get() = consumer != 0 || keyboard != 0

    companion object {
        val DIGITS = listOf(D1, D2, D3, D4, D5, D6, D7, D8, D9, D0)
    }
}

/** Samsung Smart Hub app ids. */
object TvApps {
    const val YOUTUBE = "111299001912"
}
