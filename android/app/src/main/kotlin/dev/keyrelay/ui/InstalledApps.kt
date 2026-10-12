package dev.keyrelay.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

/** Launcher apps the user can pick as targets (visible through the manifest's launcher <queries>). */
object InstalledApps {
    private const val ICON_PX = 96

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info -> AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), runCatching { pm.getApplicationIcon(info).toImage() }.getOrNull()) }
    }

    /** One app's launcher icon, or null if the app is gone. */
    fun icon(context: Context, packageName: String): ImageBitmap? =
        runCatching { context.packageManager.getApplicationIcon(packageName).toImage() }.getOrNull()

    private fun Drawable.toImage(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, ICON_PX, ICON_PX)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}

object TargetApps {
    /** Chosen apps first, then by label. */
    fun order(apps: List<AppEntry>, selected: Set<String>): List<AppEntry> =
        apps.sortedWith(compareBy<AppEntry>({ it.packageName !in selected }, { it.label.lowercase() }))
}
