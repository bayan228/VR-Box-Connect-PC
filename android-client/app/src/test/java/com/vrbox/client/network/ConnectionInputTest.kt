package com.vrbox.client.network

import org.junit.Assert.*
import org.junit.Test

class ConnectionInputTest {
    @Test fun allowsOnlyNumericPrivateLinkLocalAndLoopbackIpv4() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.1.2", "127.0.0.1")) {
            assertNotNull(address, ConnectionInput.localIpv4(address))
        }
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 10), ConnectionInput.localIpv4("192.168.1.10"))
    }

    @Test fun rejectsHostnamesPublicMulticastIpv6AndAmbiguousNumbersWithoutDns() {
        for (address in listOf("localhost", "pc.local", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1",
            "169.253.1.1", "224.0.0.1", "255.255.255.255", "0.0.0.0", "::1", "::ffff:127.0.0.1",
            "127.1", "2130706433", "192.168.01.1", "192.168.1.256", "192.168.1.-1", "192.168.1.+1",
            "192.168.1.1:8765", "192.168.1.1.", " 192.168.1.1", "192.168.1.\u0661", "")) {
            assertNull(address, ConnectionInput.localIpv4(address))
        }
    }

    @Test fun pinIsExactlySixAsciiDigits() {
        assertTrue(ConnectionInput.validPin("000000"))
        assertTrue(ConnectionInput.validPin("123456"))
        for (pin in listOf("", "12345", "1234567", "12345a", " 123456", "123456 ", "12345\u0661")) {
            assertFalse(pin, ConnectionInput.validPin(pin))
        }
    }
}
