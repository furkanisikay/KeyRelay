package dev.keyrelay.service

import dev.keyrelay.core.AdbConnectResult
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AppStartupPortsTest {
    @Test
    fun classifyConnect_true_connected() = assertEquals(AdbConnectResult.CONNECTED, classifyConnect { true })

    @Test
    fun classifyConnect_falseFromConnectPhaseTimeout_notStarted() = assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { false })

    @Test
    fun classifyConnect_mdnsTimeoutOrEmptyAddress_notStarted() {
        assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { throw InterruptedException("mdns") })
        assertEquals(AdbConnectResult.NOT_STARTED, classifyConnect { throw IOException("no address") })
    }

    @Test
    fun classifyConnect_pairingLost_pairingRequired() =
        assertEquals(AdbConnectResult.PAIRING_REQUIRED, classifyConnect { throw AdbPairingRequiredException("tls") })

    @Test
    fun classifyConnect_programmingError_propagates() {
        assertThrows(IllegalStateException::class.java) { classifyConnect { throw IllegalStateException("bug") } }
    }

    @Test
    fun isNewNetwork_sameHandleReplayedOnRegister_isNotNew() = assertFalse(isNewNetwork(42L, 42L))

    @Test
    fun isNewNetwork_reconnectOrFirstNetwork_isNew() {
        assertTrue(isNewNetwork(42L, 43L))
        assertTrue(isNewNetwork(null, 43L))
    }
}
