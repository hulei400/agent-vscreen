package com.kilon.agentvscreen.ui

import android.content.res.Configuration
import android.os.PowerManager
import androidx.activity.ComponentActivity

/**
 * 1.3.0 (rainbow, beta19): one way back from our own full pages (the full screen page, an answer page, the lesson
 * review) to the floating window.
 *
 * Before, each page called `finish()` on its own paths and some paths did not call it at all: "Done" on the full
 * screen page opened the review in another task and left the full screen page stopped behind it. When the review
 * closed, the system brought that stale page back, on MagicOS shrunk into a small window, instead of the floating
 * window. Now:
 * - every way out goes through [back], which ends the page AND its task, so nothing is left for the system to bring
 *   back or to shrink;
 * - a page put into a small or split window by the system goes back to the floating window (these pages are
 *   only meant to fill the screen; the floating window is our small view);
 * - a page with [closeWhenHidden] ends as soon as another page or app covers it (the full screen page: what is in
 *   front of it decides where the user goes next, and coming back means the floating window).
 * While a page is in front the floating window steps aside ([Overlay.fullscreenShown]).
 */
abstract class PageActivity : ComponentActivity() {
    protected open val closeWhenHidden: Boolean = false

    override fun onStart() {
        super.onStart(); Overlay.fullscreenShown(true)
        if (isInMultiWindowMode) back()
    }

    override fun onStop() {
        Overlay.fullscreenShown(false); super.onStop()
        // Not when the screen just went off (the page is still where the user left it) or on a rotation.
        val screenOn = runCatching { getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        if (closeWhenHidden && !isChangingConfigurations && screenOn) back()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) back()
    }

    /** Leave this page for the floating window. */
    fun back() { if (!isFinishing && !isDestroyed) finishAndRemoveTask() }
}
