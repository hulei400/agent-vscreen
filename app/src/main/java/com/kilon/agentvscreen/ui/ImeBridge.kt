package com.kilon.agentvscreen.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keyboard bridge: the system keyboard for the background screen's input boxes.
 *
 * The system keyboard never appears for the virtual display's fields on its own (the display IME policy is set and
 * the keyboard still never shows — the virtual-screen app hit the same wall), so typing goes through a hidden 1x1
 * sink EditText in this window: the sink summons the phone keyboard, and what is typed is committed to the
 * background screen's focused editable field. The idea is the virtual-screen app's FloatViewer.openImeBridge,
 * re-implemented for this app (no code copied): the sink, the pre-fill, the debounced replace-commit and the
 * open-after-gesture check are the same shape; the commit here goes through this app's accessibility service
 * ([VScreenAccessibilityService.setNodeText]) instead of its display engine.
 *
 * The bridge opens automatically: after a takeover touch ends, [check] looks for a focused editable field on the
 * background screen; one found pops the keyboard, none found closes it. There is no button for it anymore (the old
 * "键盘" pill and its typing row are gone: typing into this window's own row was never the system keyboard, and the
 * user asked for the real one).
 *
 * Limits, stated plainly: the sink is single-line, so Enter commits (DONE) instead of starting a new line — a
 * multi-line field takes one line per commit. If accessibility cannot see the background screen's tree (service
 * off, or a toolkit that hides its fields), the bridge never opens and typing stays on `vscreen act "type …"`.
 */
internal class ImeBridge(
    private val ctx: Context,
    private val parent: ViewGroup,
    /** Lets the host window take input focus so the sink can hold it. The floating window flips its own
     * flag; a normal activity window (the full-screen page) is focusable already, so it passes a no-op. */
    private val setFocusable: (Boolean) -> Unit = { Overlay.setFocusable(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var sink: EditText? = null
    private var bridgeOn = false
    private var suppressCommit = false
    private var commitJob: Job? = null
    private var checkJob: Job? = null
    private var showJob: Job? = null
    /** Serialises commits: two overlapping replace-writes could otherwise land out of order. */
    private val commitMutex = Mutex()

    init {
        val s = EditText(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            setBackgroundColor(0x00000000)
            setTextColor(0x00000000)
            setCursorVisible(false)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressCommit) return
                    val text = s?.toString() ?: return
                    commitJob?.cancel()
                    commitJob = scope.launch {
                        delay(200)
                        if (!bridgeOn) return@launch
                        if (!commit(text)) withContext(Dispatchers.Main) { close() }
                    }
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitNow(); true } else false
            }
        }
        parent.addView(s)
        sink = s
    }

    /** Gone with the window: close the bridge, drop the sink, stop the jobs. */
    fun detach() {
        close()
        checkJob?.cancel(); checkJob = null
        showJob?.cancel(); showJob = null
        val s = sink
        sink = null
        if (s != null) runCatching { parent.removeView(s) }
        scope.cancel()
    }

    /** After a takeover touch: look for a focused editable field; pop the keyboard if there is one, close if not. */
    fun check() {
        val st = BackgroundOperation.state.value
        if (st.takeoverSessionId == null || st.displayId == null) { close(); return }
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(300)
            val cur = BackgroundOperation.state.value
            if (cur.takeoverSessionId == null || cur.displayId == null) { close(); return@launch }
            val target = focusedEditable(cur.displayId) ?: run { close(); return@launch }
            open(target.second)
        }
    }

    fun isOpen(): Boolean = bridgeOn

    fun close() {
        if (!bridgeOn) return
        bridgeOn = false
        showJob?.cancel(); showJob = null
        commitJob?.cancel(); commitJob = null
        val s = sink
        if (s != null) runCatching { imm.hideSoftInputFromWindow(s.windowToken, 0) }
        s?.clearFocus()
        // Programmatic clear: suppress the commit like the pre-fill, so no empty write is scheduled as it closes.
        suppressCommit = true
        s?.setText("")
        suppressCommit = false
        setFocusable(false)
    }

    /** The background screen's focused editable field and its current text ("" when the text is only a hint). */
    private fun focusedEditable(displayId: Int): Pair<AccessibilityNodeInfo, String>? {
        if (VScreenAccessibilityService.getInstance() == null) return null
        val roots = BackgroundOperation.nodeRoots(displayId)
        val focused = roots.firstNotNullOfOrNull {
            runCatching { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        }?.takeIf { it.isEditable } ?: return null
        val raw = focused.text?.toString().orEmpty()
        val hint = focused.hintText?.toString().orEmpty()
        // A hot word shown as grey placeholder text (same word as the hint) is not real input: pre-fill with ""
        // so the first keystroke replaces it instead of appending after it.
        return focused to if (raw.isNotEmpty() && raw == hint) "" else raw
    }

    private fun open(initialText: String) {
        val s = sink ?: return
        // A pending commit for the previous field's text is stale once the sink is re-filled: drop it.
        commitJob?.cancel(); commitJob = null
        // Pre-fill with the field's current text so typing appends instead of wiping it; only move the cursor when
        // the text actually changed, so a mid-typing refresh does not yank it.
        if (s.text.toString() != initialText) {
            suppressCommit = true
            s.setText(initialText)
            suppressCommit = false
            s.setSelection(initialText.length)
        }
        if (bridgeOn) return
        bridgeOn = true
        // Let the window take input focus so the sink can hold it.
        setFocusable(true)
        s.requestFocus()
        showJob?.cancel()
        showJob = scope.launch {
            for (delayMs in listOf(120L, 480L, 900L, 1400L)) {
                delay(delayMs)
                if (!bridgeOn) return@launch
                runCatching { imm.showSoftInput(s, 0) }
            }
        }
    }

    /** Commit the sink text now (keyboard Done key) and close the bridge. */
    private fun commitNow() {
        val text = sink?.text?.toString() ?: ""
        commitJob?.cancel(); commitJob = null
        if (text.isEmpty()) { close(); return }
        scope.launch(Dispatchers.IO) {
            commit(text)
            withContext(Dispatchers.Main) { close() }
        }
    }

    /** Write [text] into the focused field (replace). False = the field is gone; the caller closes the bridge. */
    private suspend fun commit(text: String): Boolean = commitMutex.withLock {
        val svc = VScreenAccessibilityService.getInstance() ?: return false
        val displayId = BackgroundOperation.state.value.displayId ?: return false
        val roots = BackgroundOperation.nodeRoots(displayId)
        val focused = roots.firstNotNullOfOrNull {
            runCatching { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        }?.takeIf { it.isEditable } ?: return false
        val ok = runCatching { svc.setNodeText(focused, text) }.getOrDefault(false)
        if (!ok) AppLogger.warning("ImeBridge", "setNodeText failed")
        ok
    }
}
