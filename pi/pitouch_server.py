#!/usr/bin/env python3
"""PiTouch server for Raspberry Pi.

Fallback for phones that cannot act as a Bluetooth HID device: the PiTouch app sends
HID reports over Bluetooth RFCOMM, and this server replays them as a virtual keyboard
and mouse through /dev/uinput.

Frame format: 1 byte report id, then the report (keyboard: 8 bytes, mouse: 7 bytes),
exactly as in the HID descriptor of the app.
"""

import logging
import os
import struct
import threading

SERVICE_UUID = "6c0f3f2e-2d9b-4f4e-9b7a-5049546f7563"  # must match RfcommTransport.SERVICE_UUID
RFCOMM_CHANNEL = 22
PROFILE_PATH = "/io/github/nervotrepka/pitouch"

REPORT_KEYBOARD = 1
REPORT_MOUSE = 2
REPORT_CONSUMER = 3
REPORT_SIZES = {REPORT_KEYBOARD: 8, REPORT_MOUSE: 7, REPORT_CONSUMER: 2}

# Linux input event codes (linux/input-event-codes.h).
EV_KEY = 0x01
EV_REL = 0x02
REL_X = 0x00
REL_Y = 0x01
REL_HWHEEL = 0x06
REL_WHEEL = 0x08
MOUSE_BUTTONS = [0x110, 0x111, 0x112, 0x113, 0x114]  # BTN_LEFT, RIGHT, MIDDLE, SIDE, EXTRA

# Modifier bits 0..7 -> LEFTCTRL, LEFTSHIFT, LEFTALT, LEFTMETA, RIGHTCTRL, RIGHTSHIFT, RIGHTALT, RIGHTMETA
MODIFIER_KEYS = [29, 42, 56, 125, 97, 54, 100, 126]

# HID keyboard usage -> Linux key code, from hid_keyboard[] in drivers/hid/hid-input.c.
_HID_TABLE = [
    0, 0, 0, 0, 30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38,
    50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44, 2, 3,
    4, 5, 6, 7, 8, 9, 10, 11, 28, 1, 14, 15, 57, 12, 13, 26,
    27, 43, 43, 39, 40, 41, 51, 52, 53, 58, 59, 60, 61, 62, 63, 64,
    65, 66, 67, 68, 87, 88, 99, 70, 119, 110, 102, 104, 111, 107, 109, 106,
    105, 108, 103, 69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77, 71,
    72, 73, 82, 83, 86, 127,
]
HID_TO_KEY = {usage: code for usage, code in enumerate(_HID_TABLE) if code}
# Consumer page usage -> Linux key code (media keys, Home, Back).
CONSUMER_TO_KEY = {
    0x9C: 402,  # KEY_CHANNELUP
    0x9D: 403,  # KEY_CHANNELDOWN
    0xB3: 208,  # KEY_FASTFORWARD
    0xB4: 168,  # KEY_REWIND
    0xB5: 163,  # KEY_NEXTSONG
    0xB6: 165,  # KEY_PREVIOUSSONG
    0xB7: 166,  # KEY_STOPCD
    0xCD: 164,  # KEY_PLAYPAUSE
    0xE2: 113,  # KEY_MUTE
    0xE9: 115,  # KEY_VOLUMEUP
    0xEA: 114,  # KEY_VOLUMEDOWN
    0x223: 172,  # KEY_HOMEPAGE
    0x224: 158,  # KEY_BACK
}
KEYBOARD_KEYS = sorted(set(HID_TO_KEY.values()) | set(MODIFIER_KEYS) | set(CONSUMER_TO_KEY.values()))

log = logging.getLogger("pitouch")


class FrameReader:
    """Splits the RFCOMM byte stream into (report_id, report) frames."""

    def __init__(self):
        self._buf = bytearray()

    def feed(self, data):
        self._buf += data
        frames = []
        while self._buf:
            size = REPORT_SIZES.get(self._buf[0])
            if size is None:
                log.warning("unknown report id %d, skipping byte", self._buf[0])
                del self._buf[0]
                continue
            if len(self._buf) < size + 1:
                break
            frames.append((self._buf[0], bytes(self._buf[1:size + 1])))
            del self._buf[:size + 1]
        return frames


class ReportTranslator:
    """Turns HID reports into input events (type, code, value). Keeps pressed state."""

    def __init__(self):
        self.keys = set()
        self.buttons = 0
        self.consumer_key = None

    def translate(self, report_id, report):
        if report_id == REPORT_KEYBOARD:
            return self.keyboard(report)
        if report_id == REPORT_MOUSE:
            return self.mouse(report)
        if report_id == REPORT_CONSUMER:
            return self.consumer(report)
        return []

    def consumer(self, report):
        (usage,) = struct.unpack("<H", report)
        code = CONSUMER_TO_KEY.get(usage)
        events = []
        if self.consumer_key is not None and self.consumer_key != code:
            events.append((EV_KEY, self.consumer_key, 0))
        if code is not None and code != self.consumer_key:
            events.append((EV_KEY, code, 1))
        self.consumer_key = code
        return events

    def keyboard(self, report):
        modifiers = report[0]
        pressed = {code for bit, code in enumerate(MODIFIER_KEYS) if modifiers & (1 << bit)}
        pressed |= {HID_TO_KEY[u] for u in report[2:8] if u in HID_TO_KEY}
        events = [(EV_KEY, code, 0) for code in sorted(self.keys - pressed)]
        events += [(EV_KEY, code, 1) for code in sorted(pressed - self.keys)]
        self.keys = pressed
        return events

    def mouse(self, report):
        buttons, dx, dy, wheel, pan = struct.unpack("<Bhhbb", report)
        events = []
        changed = buttons ^ self.buttons
        for bit, code in enumerate(MOUSE_BUTTONS):
            if changed & (1 << bit):
                events.append((EV_KEY, code, 1 if buttons & (1 << bit) else 0))
        self.buttons = buttons
        for code, value in ((REL_X, dx), (REL_Y, dy), (REL_WHEEL, wheel), (REL_HWHEEL, pan)):
            if value:
                events.append((EV_REL, code, value))
        return events

    def release_all(self):
        events = self.keyboard(bytes(8))
        events += self.mouse(bytes(7))
        events += self.consumer(bytes(2))
        return events


class VirtualDevices:
    """A uinput keyboard and a uinput mouse (separate, so desktops classify them correctly)."""

    def __init__(self):
        from evdev import UInput

        self._lock = threading.Lock()
        self._keyboard = UInput({EV_KEY: KEYBOARD_KEYS}, name="PiTouch Keyboard")
        self._mouse = UInput(
            {EV_KEY: MOUSE_BUTTONS, EV_REL: [REL_X, REL_Y, REL_HWHEEL, REL_WHEEL]},
            name="PiTouch Mouse",
        )

    def emit(self, events):
        if not events:
            return
        with self._lock:
            touched = set()
            for etype, code, value in events:
                dev = self._mouse if etype == EV_REL or code in MOUSE_BUTTONS else self._keyboard
                dev.write(etype, code, value)
                touched.add(dev)
            for dev in touched:
                dev.syn()


def serve_connection(fd, devices, peer):
    log.info("connected: %s", peer)
    reader = FrameReader()
    translator = ReportTranslator()
    try:
        while True:
            data = os.read(fd, 1024)
            if not data:
                break
            for report_id, report in reader.feed(data):
                devices.emit(translator.translate(report_id, report))
    except OSError as e:
        log.info("connection error: %s", e)
    finally:
        devices.emit(translator.release_all())
        os.close(fd)
        log.info("disconnected: %s", peer)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    import dbus
    import dbus.mainloop.glib
    import dbus.service
    from gi.repository import GLib

    devices = VirtualDevices()

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            log.info("profile released")

        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, properties):
            threading.Thread(
                target=serve_connection, args=(fd.take(), devices, str(device)), daemon=True
            ).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            log.info("disconnect requested: %s", device)

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    profile = Profile(bus, PROFILE_PATH)  # noqa: F841 (must stay referenced)
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
    manager.RegisterProfile(PROFILE_PATH, SERVICE_UUID, {
        "Name": "PiTouch",
        "Role": "server",
        "Channel": dbus.UInt16(RFCOMM_CHANNEL),
        "RequireAuthentication": True,
        "RequireAuthorization": False,
        "AutoConnect": False,
    })
    log.info("PiTouch server ready (RFCOMM channel %d)", RFCOMM_CHANNEL)
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
