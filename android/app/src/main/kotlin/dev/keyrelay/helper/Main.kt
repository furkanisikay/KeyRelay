package dev.keyrelay.helper

import kotlin.system.exitProcess

/** Entry point when started as `app_process ... dev.keyrelay.helper.Main --lib=... --app-uid=...` under the shell uid. */
object Main {
    private const val EXIT_CRASH = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        Evdev.load(opts["--lib"] ?: error("--lib=<path to libkeyrelay.so> is required"))
        val helper = Helper(opts["--app-uid"]?.toIntOrNull() ?: error("--app-uid=<uid> is required"))
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            helper.reportFatal(e)
            exitProcess(EXIT_CRASH)
        }
        helper.start()
        println("${System.currentTimeMillis()} helper ready")
        Thread.sleep(Long.MAX_VALUE)
    }
}
