import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pitouch_server as s  # noqa: E402


class FrameReaderTest(unittest.TestCase):
    def test_splits_partial_and_joined_frames(self):
        r = s.FrameReader()
        kb = bytes([1, 0, 0, 4, 0, 0, 0, 0, 0])
        mouse = bytes([2, 1, 5, 0, 0xFB, 0xFF, 0, 0])
        self.assertEqual(r.feed(kb[:4]), [])
        frames = r.feed(kb[4:] + mouse + b"\x99")
        self.assertEqual(frames, [(1, kb[1:]), (2, mouse[1:])])


class TranslatorTest(unittest.TestCase):
    def test_keyboard_press_and_release(self):
        t = s.ReportTranslator()
        # Ctrl + 'c'
        self.assertEqual(
            t.keyboard(bytes([0x01, 0, 0x06, 0, 0, 0, 0, 0])),
            [(s.EV_KEY, 29, 1), (s.EV_KEY, 46, 1)],
        )
        self.assertEqual(t.keyboard(bytes(8)), [(s.EV_KEY, 29, 0), (s.EV_KEY, 46, 0)])

    def test_key_table(self):
        self.assertEqual(s.HID_TO_KEY[0x04], 30)   # A
        self.assertEqual(s.HID_TO_KEY[0x28], 28)   # Enter
        self.assertEqual(s.HID_TO_KEY[0x35], 41)   # Grave (Ё)
        self.assertEqual(s.HID_TO_KEY[0x45], 88)   # F12
        self.assertEqual(s.HID_TO_KEY[0x52], 103)  # Up

    def test_consumer(self):
        t = s.ReportTranslator()
        self.assertEqual(t.consumer(struct.pack("<H", 0xE9)), [(s.EV_KEY, 115, 1)])
        self.assertEqual(t.consumer(bytes(2)), [(s.EV_KEY, 115, 0)])
        self.assertEqual(s.FrameReader().feed(bytes([3, 0x23, 0x02])), [(3, bytes([0x23, 0x02]))])

    def test_mouse(self):
        t = s.ReportTranslator()
        report = struct.pack("<Bhhbb", 1, -3, 400, 1, 0)
        self.assertEqual(
            t.mouse(report),
            [(s.EV_KEY, 0x110, 1), (s.EV_REL, s.REL_X, -3), (s.EV_REL, s.REL_Y, 400), (s.EV_REL, s.REL_WHEEL, 1)],
        )
        self.assertEqual(t.release_all(), [(s.EV_KEY, 0x110, 0)])


if __name__ == "__main__":
    unittest.main()
