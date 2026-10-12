package dev.keyrelay.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddressKindTest {
    @Test
    fun isSameNetwork_privateAndLocalAddresses_true() {
        for (h in listOf("192.168.1.10", "10.0.0.5", "172.16.0.1", "172.31.255.1", "169.254.3.4", "homepc.local", "fe80::1", "fd12::3", "[fd12::3]")) {
            assertTrue(isSameNetwork(h), h)
        }
    }

    @Test
    fun isSameNetwork_internetAndTailnetAddresses_false() {
        for (h in listOf("100.64.0.10", "homepc.tailnet-example.ts.net", "172.32.0.1", "8.8.8.8", "2001:db8::1", "fdomain.example", "1.2.3")) {
            assertFalse(isSameNetwork(h), h)
        }
    }
}
