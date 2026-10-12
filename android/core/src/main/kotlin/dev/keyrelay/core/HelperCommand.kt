package dev.keyrelay.core

/** Builds the adb shell command that starts the helper detached from the adb session. */
object HelperCommand {
    // 15 chars: the kernel comm limit; a longer name is truncated and pidof stops matching.
    const val NICE_NAME = "keyrelay-helper"
    const val LOG_PATH = "/data/local/tmp/keyrelay-helper.log"

    private const val UNSAFE_CHARS = "'\"$`\\"

    // adbd kills the shell's process group on stream close, so setsid must finish before the shell exits.
    fun build(apkPath: String, nativeLibDir: String, args: List<String>): String {
        val all = listOf(apkPath, nativeLibDir) + args
        require(all.none { arg -> arg.any { it in UNSAFE_CHARS } }) { "shell argument contains one of: $UNSAFE_CHARS" }
        val quotedArgs = (listOf("--lib=$nativeLibDir/libkeyrelay.so") + args).joinToString(" ") { "'$it'" }
        return "setsid sh -c \"CLASSPATH='$apkPath' nohup app_process /system/bin --nice-name=$NICE_NAME " +
            "dev.keyrelay.helper.Main $quotedArgs > $LOG_PATH 2>&1 < /dev/null &\""
    }
}
