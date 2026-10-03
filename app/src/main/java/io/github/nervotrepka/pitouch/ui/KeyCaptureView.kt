package io.github.nervotrepka.pitouch.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.nervotrepka.pitouch.hid.KeyMapper
import io.github.nervotrepka.pitouch.hid.Usage

/**
 * Invisible text target for the phone's on-screen keyboard. It mirrors what was typed in a
 * local buffer and turns every change (including autocorrect and voice input) into
 * backspaces + new characters sent to the Pi.
 */
class KeyCaptureView(context: Context) : View(context) {

    interface Sink {
        fun typeText(text: String)
        fun backspace(count: Int)
        fun key(usage: Int)
    }

    var sink: Sink? = null
    var suggestions = false

    private val buffer = SpannableStringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            if (suggestions) InputType.TYPE_TEXT_FLAG_AUTO_CORRECT else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        resetBuffer()
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return Connection()
    }

    fun resetBuffer() {
        buffer.clear()
        buffer.clearSpans()
        Selection.setSelection(buffer, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val out = sink ?: return false
        val usage = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (buffer.isNotEmpty()) {
                    buffer.delete(buffer.length - 1, buffer.length)
                    Selection.setSelection(buffer, buffer.length)
                }
                out.backspace(1)
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> Usage.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Usage.ENTER
            KeyEvent.KEYCODE_TAB -> Usage.TAB
            KeyEvent.KEYCODE_ESCAPE -> Usage.ESC
            KeyEvent.KEYCODE_DPAD_LEFT -> Usage.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Usage.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Usage.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Usage.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Usage.HOME
            KeyEvent.KEYCODE_MOVE_END -> Usage.END
            KeyEvent.KEYCODE_PAGE_UP -> Usage.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Usage.PAGE_DOWN
            else -> {
                val ch = event.unicodeChar
                if (ch == 0) return false
                val text = String(Character.toChars(ch))
                buffer.append(text)
                Selection.setSelection(buffer, buffer.length)
                out.typeText(text)
                return true
            }
        }
        // The Pi's cursor may have moved away from the end: forget what the IME could edit.
        resetBuffer()
        out.key(usage)
        return true
    }

    private fun emitDiff(before: String, after: String) {
        val out = sink ?: return
        var p = 0
        val n = minOf(before.length, after.length)
        while (p < n && before[p] == after[p]) p++
        val removed = before.substring(p).count(KeyMapper::canType)
        if (removed > 0) out.backspace(removed)
        if (p < after.length) out.typeText(after.substring(p))
    }

    private fun trim() {
        if (buffer.length > MAX_BUFFER && BaseInputConnection.getComposingSpanStart(buffer) < 0) {
            buffer.delete(0, buffer.length - KEEP_BUFFER)
            Selection.setSelection(buffer, buffer.length)
        }
    }

    private inner class Connection : BaseInputConnection(this@KeyCaptureView, true) {
        override fun getEditable(): Editable = buffer

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val before = buffer.toString()
            val result = super.commitText(text, newCursorPosition)
            afterEdit(before)
            return result
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val before = buffer.toString()
            val result = super.setComposingText(text, newCursorPosition)
            afterEdit(before)
            return result
        }

        override fun finishComposingText(): Boolean {
            val before = buffer.toString()
            val result = super.finishComposingText()
            afterEdit(before)
            return result
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val cursor = Selection.getSelectionStart(buffer).coerceAtLeast(0)
            val local = minOf(beforeLength, cursor)
            if (local > 0) {
                val before = buffer.toString()
                buffer.delete(cursor - local, cursor)
                afterEdit(before)
            }
            // Text typed before the buffer was reset still exists on the Pi.
            if (beforeLength > local) sink?.backspace(beforeLength - local)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) handleKey(event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            resetBuffer()
            sink?.key(Usage.ENTER)
            return true
        }

        private fun afterEdit(before: String) {
            val after = buffer.toString()
            if (before != after) emitDiff(before, after)
            trim()
        }
    }

    private companion object {
        const val MAX_BUFFER = 300
        const val KEEP_BUFFER = 100
    }
}
