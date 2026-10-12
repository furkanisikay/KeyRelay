package dev.keyrelay.helper

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Hand-written stub for the hidden oneway AIDL interface android.app.IProcessObserver.
 * Transaction codes are resolved from the platform stub (Android 15 added onProcessStarted first);
 * android14-release values are the fallback.
 */
class ProcessObserverBinder(private val listener: Listener) : Binder() {
    interface Listener {
        fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean)
        fun onProcessDied(pid: Int, uid: Int)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            IBinder.INTERFACE_TRANSACTION -> reply?.writeString(DESCRIPTOR)
            CODE_FOREGROUND_CHANGED -> {
                data.enforceInterface(DESCRIPTOR)
                listener.onForegroundActivitiesChanged(data.readInt(), data.readInt(), data.readInt() != 0)
            }
            CODE_PROCESS_DIED -> {
                data.enforceInterface(DESCRIPTOR)
                listener.onProcessDied(data.readInt(), data.readInt())
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    companion object {
        const val DESCRIPTOR = "android.app.IProcessObserver"

        private val CODE_FOREGROUND_CHANGED: Int
        private val CODE_PROCESS_DIED: Int

        init {
            var foreground = IBinder.FIRST_CALL_TRANSACTION
            var died = IBinder.FIRST_CALL_TRANSACTION + 2
            try {
                val stub = Class.forName("$DESCRIPTOR\$Stub")
                fun code(name: String) = stub.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
                foreground = code("TRANSACTION_onForegroundActivitiesChanged")
                died = code("TRANSACTION_onProcessDied")
                println("IProcessObserver codes from platform stub: foreground=$foreground died=$died")
            } catch (e: Exception) {
                foreground = IBinder.FIRST_CALL_TRANSACTION
                died = IBinder.FIRST_CALL_TRANSACTION + 2
                println("IProcessObserver codes fallback to android14: foreground=$foreground died=$died ($e)")
            }
            CODE_FOREGROUND_CHANGED = foreground
            CODE_PROCESS_DIED = died
        }
    }
}
