package app.tether.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the mobile data switch (issue #14: data turned on from the iPhone, the switch kept showing off). */
class DataStateTest {
    @Test fun dualSimPhoneKeepsTheSwitchPerSubscription() {
        // A Poco X3 Pro on a custom ROM: `svc data enable` sets mobile_data<sub>, the global value stays "0".
        assertTrue(DataState.decide(telephony = null, perSub = "1", global = "0"))
        assertFalse(DataState.decide(telephony = null, perSub = "0", global = "1"))
    }

    @Test fun thePhoneServiceWins() {
        assertTrue(DataState.decide(telephony = true, perSub = "0", global = "0"))
        assertFalse(DataState.decide(telephony = false, perSub = "1", global = "1"))
    }

    @Test fun singleSimAndSamsungStillReadTheGlobalSetting() {
        // `settings get` prints "null" for a setting the phone does not have.
        assertTrue(DataState.decide(telephony = null, perSub = "null", global = "1"))
        assertTrue(DataState.decide(telephony = null, perSub = null, global = "1\n"))
        assertFalse(DataState.decide(telephony = null, perSub = "null", global = "0"))
        assertFalse(DataState.decide(telephony = null, perSub = "", global = "null"))
    }

    @Test fun perSubscriptionKey() {
        assertEquals("mobile_data2", DataState.subKey(2))
        assertEquals("mobile_data0", DataState.subKey(0))
        assertNull(DataState.subKey(-1))
    }
}
