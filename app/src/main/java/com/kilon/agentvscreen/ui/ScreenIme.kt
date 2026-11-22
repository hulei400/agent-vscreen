package com.kilon.agentvscreen.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * 1.4.0-beta13 (rainbow: the keyboard button should just bring up my own keyboard, no box of ours).
 *
 * An editor with no picture: the system keyboard (any IME, pinyin candidates included) connects to this view as it
 * would to a text box, and what it writes goes to the screen's focused field. The view keeps its own copy of the text
 * (the IME needs one to read around the cursor); only what the IME has COMMITTED is sent, so pinyin letters still being
 * composed never land in the app's box or start its search-as-you-type. Delete on an empty copy and Enter / Go become
 * key presses on the screen, which is what they mean there.
 */
internal class ScreenImeView(
    context: Context,
    /** The committed text changed: the field should now read this. */
    private val onText: (String) -> Unit,
    /** A key that has no text: "del" (delete with nothing left here) or "enter". */
    private val onKey: (String) -> Unit,
) : View(context) {
    private val buf: Editable = Editable.Factory.getInstance().newEditable("")
    var secret = false
        set(v) { if (field != v) { field = v; imm().restartInput(this) } }

    init { isFocusable = true; isFocusableInTouchMode = true }

    private fun imm() = context.getSystemService(InputMethodManager::class.java)

    /** The screen's field changed by itself (another box, the app filled it): start the IME over from [text]. */
    fun reset(text: String) {
        buf.replace(0, buf.length, text); Selection.setSelection(buf, buf.length)
        BaseInputConnection.removeComposingSpans(buf)
        imm().restartInput(this)
    }

    fun open() { requestFocus(); post { imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) } }
    fun close() { imm().hideSoftInputFromWindow(windowToken, 0); clearFocus() }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
        out.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        out.initialSelStart = Selection.getSelectionStart(buf).coerceAtLeast(0)
        out.initialSelEnd = Selection.getSelectionEnd(buf).coerceAtLeast(0)
        out.initialCapsMode = 0
        return Conn()
    }

    /** What the field should read: the copy without the part the IME is still composing. */
    private fun committed(): String {
        val a = BaseInputConnection.getComposingSpanStart(buf); val b = BaseInputConnection.getComposingSpanEnd(buf)
        return if (a in 0..b && b <= buf.length) buf.substring(0, a) + buf.substring(b) else buf.toString()
    }

    private var last = ""
    private fun changed() { val t = committed(); if (t != last) { last = t; onText(t) } }

    private inner class Conn : BaseInputConnection(this@ScreenImeView, true) {
        override fun getEditable(): Editable = buf
        override fun commitText(text: CharSequence?, newCursorPosition: Int) = super.commitText(text, newCursorPosition).also { changed() }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = super.setComposingText(text, newCursorPosition).also { changed() }
        override fun finishComposingText() = super.finishComposingText().also { changed() }
        override fun setComposingRegion(start: Int, end: Int) = super.setComposingRegion(start, end).also { changed() }
        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            if (buf.isEmpty() && before > 0) { onKey("del"); return true }
            return super.deleteSurroundingText(before, after).also { changed() }
        }
        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean {
            if (buf.isEmpty() && before > 0) { onKey("del"); return true }
            return super.deleteSurroundingTextInCodePoints(before, after).also { changed() }
        }
        override fun performEditorAction(action: Int): Boolean { finishComposingText(); onKey("enter"); return true }
        override fun sendKeyEvent(e: KeyEvent): Boolean {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            when (e.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    val s = Selection.getSelectionStart(buf); val en = Selection.getSelectionEnd(buf)
                    when {
                        buf.isEmpty() -> onKey("del")
                        s != en && s >= 0 -> { buf.delete(minOf(s, en), maxOf(s, en)); changed() }
                        s > 0 -> { buf.delete(s - Character.charCount(Character.codePointBefore(buf, s)), s); changed() }
                    }
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { finishComposingText(); onKey("enter") }
                else -> e.unicodeChar.takeIf { it != 0 }?.let { commitText(String(Character.toChars(it)), 1) }
            }
            return true
        }
    }
}
