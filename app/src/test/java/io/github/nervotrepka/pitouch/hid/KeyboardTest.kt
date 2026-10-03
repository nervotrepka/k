package io.github.nervotrepka.pitouch.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardTest {
    private class Recorder : HidOutput {
        val reports = mutableListOf<Pair<Int, Int>>()
        override fun keyboard(modifiers: Int, usage: Int) {
            reports += modifiers to usage
        }
        override fun mouseMove(dx: Int, dy: Int) {}
        override fun mouseScroll(wheel: Int, pan: Int) {}
        override fun mouseButton(button: Int, down: Boolean) {}
    }

    @Test
    fun layoutStringsAreConsistent() {
        assertEquals(KeyMapper.EN_PLAIN.length, KeyMapper.EN_SHIFT.length)
        assertEquals(KeyMapper.RU_PLAIN.length, KeyMapper.RU_SHIFT.length)
        assertEquals(KeyMapper.EN_PLAIN.length, KeyMapper.RU_PLAIN.length)
    }

    @Test
    fun mapsLatinAndCyrillic() {
        assertEquals(KeyStroke(Usage.letter('a')), KeyMapper.map('a', Layout.EN))
        assertEquals(KeyStroke(Usage.letter('a'), Mod.SHIFT), KeyMapper.map('A', Layout.EN))
        assertEquals(KeyStroke(Usage.letter('f')), KeyMapper.map('а', Layout.RU))
        assertEquals(KeyStroke(Usage.letter('q'), Mod.SHIFT), KeyMapper.map('Й', Layout.RU))
        assertEquals(KeyStroke(0x38, Mod.SHIFT), KeyMapper.map(',', Layout.RU))
        assertEquals(KeyStroke(0x36), KeyMapper.map(',', Layout.EN))
        assertNull(KeyMapper.map('а', Layout.EN))
        assertNull(KeyMapper.layoutFor('\u2603', Layout.EN))
    }

    @Test
    fun switchesLayoutOnlyWhenNeeded() {
        val out = Recorder()
        val kb = Keyboard(out)
        kb.typeText("1я")
        assertEquals(Layout.RU, kb.layout)
        assertEquals(
            listOf(
                0 to 0x1E, 0 to 0,                                  // "1"
                Mod.SHIFT to 0, Mod.SHIFT or Mod.ALT to 0, 0 to 0,  // Alt+Shift
                0 to Usage.letter('z'), 0 to 0,                     // "я"
            ),
            out.reports,
        )
        out.reports.clear()
        kb.typeText(".")  // exists in RU, no switch back
        assertEquals(listOf(0 to 0x38, 0 to 0), out.reports)
    }

    @Test
    fun oneShotModifierAppliesToNextKeyOnly() {
        val out = Recorder()
        val kb = Keyboard(out)
        kb.cycleModifier(Mod.CTRL)
        kb.typeText("cc")
        assertEquals(
            listOf(Mod.CTRL to Usage.letter('c'), 0 to 0, 0 to Usage.letter('c'), 0 to 0),
            out.reports,
        )
        assertEquals(ModState.OFF, kb.modifierState(Mod.CTRL))
    }

    @Test
    fun mouseReportEncodesLittleEndian() {
        val r = HidReports.mouse(MouseButton.LEFT, -2, 300, 1, -1)
        assertEquals(listOf(1, 0xFE, 0xFF, 0x2C, 0x01, 1, 0xFF), r.map { it.toInt() and 0xFF })
    }
}
