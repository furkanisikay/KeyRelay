package dev.keyrelay.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * The shell-uid helper calls "attach" here through getContentProviderExternal (Shizuku pattern) and gets the app's
 * token back. The manifest permission keeps other apps out; the uid check keeps out everything but the shell.
 */
class HelperAttachProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ATTACH || Binder.getCallingUid() != SHELL_UID) return null
        val binder = extras?.getBinder(KEY_BINDER) ?: return null
        HelperConnection.attach(binder)
        return Bundle().apply { putBinder(KEY_BINDER, HelperConnection.appToken) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.keyrelay.helper"
        const val METHOD_ATTACH = "attach"
        const val KEY_BINDER = "binder"
        private const val SHELL_UID = 2000
    }
}
