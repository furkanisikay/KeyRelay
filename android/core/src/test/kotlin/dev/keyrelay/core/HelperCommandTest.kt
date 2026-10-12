package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HelperCommandTest {
    @Test
    fun build_typicalPaths_producesDetachedAppProcessCommand() {
        val cmd = HelperCommand.build("/data/app/~~a/dev.keyrelay-b/base.apk", "/data/app/~~a/dev.keyrelay-b/lib/arm64", listOf("--watch-foreground=com.x"))
        assertEquals(
            "setsid sh -c \"CLASSPATH='/data/app/~~a/dev.keyrelay-b/base.apk' nohup app_process /system/bin --nice-name=keyrelay-helper " +
                "dev.keyrelay.helper.Main '--lib=/data/app/~~a/dev.keyrelay-b/lib/arm64/libkeyrelay.so' '--watch-foreground=com.x' " +
                "> /data/local/tmp/keyrelay-helper.log 2>&1 < /dev/null &\"",
            cmd,
        )
    }

    @Test
    fun build_pathWithSingleQuote_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/data/app/x'y/base.apk", "/lib", emptyList()) }
    }

    @Test
    fun build_argumentWithSingleQuote_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--watch-foreground=a'b")) }
    }

    @Test
    fun build_argumentWithShellMetacharacter_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--watch-foreground=\$x")) }
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib\"", emptyList()) }
    }

    @Test
    fun build_argumentWithBacktickOrBackslash_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--x=`id`")) }
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.build("/a/base.apk", "/lib", listOf("--x=a\\b")) }
    }

    @Test
    fun build_argumentWithSemicolonAndSpace_isQuotedNotRejected() {
        val cmd = HelperCommand.build("/a/base.apk", "/lib", listOf("--x=a; rm -rf b"))
        assertTrue(cmd.contains("'--x=a; rm -rf b'"))
    }

    @Test
    fun build_appUidArgument_followsLib() {
        val cmd = HelperCommand.build("/a/base.apk", "/lib", listOf("--app-uid=10234"))
        assertTrue(cmd.contains("'--lib=/lib/libkeyrelay.so' '--app-uid=10234'"))
    }
}
