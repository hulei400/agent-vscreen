package com.kilon.agentvscreen.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.kilon.agentvscreen.core.RootMain

/**
 * 1.4.2-beta3 [root way in]: where the root-started screen service ([RootMain]) hands its binder to the app.
 *
 * Exported because the shell / root reach it through the system's external provider access, which only sees exported
 * providers. Only uid 0 (root) and 2000 (shell) are accepted: an app cannot be either, so no app can slip the app a
 * service of its own. It has no data: query/insert/update/delete do nothing.
 */
class RootLink : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = Binder.getCallingUid() % 100_000
        if (caller != 0 && caller != 2000) throw SecurityException("uid $caller may not call this")
        if (method != RootMain.METHOD || extras == null) return null
        val b = extras.getBinder(RootMain.KEY_BINDER) ?: return null
        RootBackend.onBinder(b, extras.getInt(RootMain.KEY_UID, caller), extras.getInt(RootMain.KEY_PROTOCOL, 0))
        return Bundle()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
