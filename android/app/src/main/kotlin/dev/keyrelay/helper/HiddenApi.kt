package dev.keyrelay.helper

import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.keyrelay.service.HelperAttachProvider
import java.io.File

/** Reflection over hidden framework APIs. Only valid in a shell-uid app_process. */
object HiddenApi {
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val TOP_APP_PROCS = "/dev/cpuset/top-app/cgroup.procs"
    private val iActivityManager = Class.forName("android.app.IActivityManager")
    private val iContentProvider = Class.forName("android.content.IContentProvider")

    private fun activityManager(): Any = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!

    fun registerProcessObserver(binder: IBinder) {
        val iface = Class.forName("android.app.IProcessObserver")
        val proxy = Class.forName("android.app.IProcessObserver\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        iActivityManager.getMethod("registerProcessObserver", iface).invoke(activityManager(), proxy)
    }

    /**
     * Calls the app's provider "attach" with [binder] and returns the app's token binder.
     * IContentProvider.call is (callingPkg, attributionTag, authority, method, arg, extras) on API 30 and
     * (AttributionSource, authority, method, arg, extras) from API 31 (AOSP android11/12/14/16-release).
     */
    fun attach(authority: String, binder: IBinder, userId: Int): IBinder? {
        val am = activityManager()
        val holder = iActivityManager
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, authority, userId, null, authority) ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val extras = Bundle().apply { putBinder(HelperAttachProvider.KEY_BINDER, binder) }
            val reply = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
                iContentProvider
                    .getMethod("call", AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, source, authority, HelperAttachProvider.METHOD_ATTACH, null, extras)
            } else {
                iContentProvider
                    .getMethod("call", String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, SHELL_PACKAGE, null, authority, HelperAttachProvider.METHOD_ATTACH, null, extras)
            } as Bundle?
            return reply?.getBinder(HelperAttachProvider.KEY_BINDER)
        } finally {
            iActivityManager
                .getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                .invoke(am, authority, null, userId)
        }
    }

    /** pid -> uid of processes Android currently shows on screen; seeds state before the first observer event. */
    fun topAppPids(): Map<Int, Int> {
        val procs = File(TOP_APP_PROCS)
        if (!procs.canRead()) return emptyMap()
        return procs.readLines().mapNotNull { line ->
            val pid = line.trim().toIntOrNull() ?: return@mapNotNull null
            val uid = runCatching {
                File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
            }.getOrNull() ?: return@mapNotNull null
            pid to uid
        }.toMap()
    }
}
