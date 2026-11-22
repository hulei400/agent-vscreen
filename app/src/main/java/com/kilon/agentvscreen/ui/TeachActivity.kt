package com.kilon.agentvscreen.ui

import android.app.Activity
import android.os.Bundle

/**
 * 1.4.0-beta11: the save page is now the REVIEW pane of the screen page ([Surface.openReview]). This stays so old
 * notifications and links still open it.
 */
class TeachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Surface.openReview(this)
        finish()
    }
}
