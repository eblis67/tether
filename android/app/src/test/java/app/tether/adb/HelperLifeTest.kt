package app.tether.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issues #26 / #29: call audio stopped when the phone left Wi-Fi, and nothing said why. */
class HelperLifeTest {
    @Test fun stoppedWithoutWifiSaysSo() {
        assertEquals("wifi", HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = false, legacy = false))
    }

    @Test fun noReasonWhileRunningOrWhenWifiOrTcpDebuggingCanStartIt() {
        assertNull(HelperLife.why(running = true, sdk = 33, wifi = false, adbWifi = false, legacy = false))
        assertNull(HelperLife.why(running = false, sdk = 33, wifi = true, adbWifi = true, legacy = false))
        // No Wi-Fi, even if the Wireless debugging setting still says on (Samsung keeps it on): it cannot be reached.
        assertEquals("Samsung keeps the setting on without Wi-Fi (A54)", "wifi", HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = true, legacy = false))
        // TCP debugging reaches adbd on 127.0.0.1 without Wi-Fi.
        assertNull(HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = false, legacy = true))
        // Android 8-10 have no Wireless debugging: Wi-Fi is not what is missing there.
        assertNull(HelperLife.why(running = false, sdk = 29, wifi = false, adbWifi = false, legacy = false))
    }

    @Test fun onlyWirelessDebuggingKeepsTheHelperUp() {
        assertTrue(HelperLife.needsWifi(running = true, sdk = 33, usbDebugging = false))
        assertFalse(HelperLife.needsWifi(running = true, sdk = 33, usbDebugging = true))
        assertFalse(HelperLife.needsWifi(running = false, sdk = 33, usbDebugging = false))
        assertFalse(HelperLife.needsWifi(running = true, sdk = 29, usbDebugging = false))
    }
}
