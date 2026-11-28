package app.tether.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control panel's shared state (protocol §5.4): defaults, flipping, adopting and the wire shape. */
class FeaturesTest {

    @Test fun everythingIsOffByDefault() {
        val s = FeatureSettings.DEFAULT
        assertEquals(0L, s.rev)
        for (id in Feature.ALL) assertFalse("$id must start off", s.isOn(id))
        assertEquals(s, FeatureSettings.of(null))
        assertEquals(s, FeatureSettings.of(JSONObject()))
    }

    @Test fun flippingMovesTheRevisionAndKeepsTheRest() {
        val s = FeatureSettings.DEFAULT.with(Feature.INTERNET, true).with(Feature.FIND, true).with(Feature.INTERNET, false)
        assertFalse(s.isOn(Feature.INTERNET)); assertTrue(s.isOn(Feature.FIND)); assertFalse(s.isOn(Feature.USSD))
        assertEquals(3L, s.rev)
    }

    @Test fun aNewerRevisionWinsWhole() {
        val mine = FeatureSettings.DEFAULT.with(Feature.INTERNET, true)                    // rev 1
        val theirs = FeatureSettings.DEFAULT.with(Feature.USSD, true).with(Feature.FIND, true) // rev 2
        assertEquals(theirs, mine.adopt(theirs))
    }

    @Test fun anOlderRevisionLoses() {
        val mine = FeatureSettings.DEFAULT.with(Feature.USSD, true).with(Feature.FIND, true)   // rev 2
        val theirs = FeatureSettings.DEFAULT.with(Feature.INTERNET, true)                      // rev 1
        assertEquals(mine, mine.adopt(theirs))
        assertEquals(mine, mine.adopt(theirs, Feature.INTERNET))
    }

    @Test fun twoPhonesFlippingDifferentSwitchesAtOnceKeepBothFlips() {
        val base = FeatureSettings.DEFAULT.with(Feature.RINGER, true)                          // rev 1, shared
        val mine = base.with(Feature.INTERNET, true)                                           // rev 2
        val theirs = base.with(Feature.FIND, true)                                             // also rev 2
        val merged = mine.adopt(theirs, Feature.FIND)
        assertTrue(merged.isOn(Feature.INTERNET)); assertTrue(merged.isOn(Feature.FIND)); assertTrue(merged.isOn(Feature.RINGER))
        assertTrue("the merged state must out-revise both", merged.rev > mine.rev && merged.rev > theirs.rev)
        // And the other side converges on the same state when it hears the merged one.
        assertEquals(merged, theirs.adopt(merged))
    }

    @Test fun theSameSwitchFlippedBothWaysAtOnceTakesTheSendersWord() {
        val base = FeatureSettings.DEFAULT                                                     // internet off, rev 0
        val mine = base.with(Feature.INTERNET, true)                                           // rev 1: on
        val theirs = FeatureSettings(mapOf(Feature.INTERNET to false), rev = 1)                // rev 1: still off, re-sent
        // No changed id: nothing to take.
        assertEquals(mine, mine.adopt(theirs))
        // With the changed id named, the sender's value is taken (last word wins for that switch).
        assertFalse(mine.adopt(theirs, Feature.INTERNET).isOn(Feature.INTERNET))
    }

    @Test fun jsonRoundTripsIncludingUnknownIds() {
        val s = FeatureSettings(mapOf(Feature.INTERNET to true, "futureFeature" to true, Feature.DEMO to false), rev = 7)
        val back = FeatureSettings.of(s.json())
        assertEquals(s, back)
        assertTrue("an unknown id survives the round trip", back.isOn("futureFeature"))
        val withId = s.json(Feature.INTERNET)
        assertEquals(Feature.INTERNET, FeatureSettings.changedId(withId))
        assertEquals(null, FeatureSettings.changedId(s.json()))
    }

    @Test fun theCatalogueCoversTheSpec() {
        // The extras that exist in this build are switchable; Stage 3's are already named so they can join.
        assertTrue(Feature.AVAILABLE.containsAll(listOf(Feature.INTERNET, Feature.USSD, Feature.FIND, Feature.RINGER)))
        assertTrue(Feature.ALL.containsAll(Feature.AVAILABLE))
        assertTrue(Feature.ALL.containsAll(listOf(Feature.FILES, Feature.LIVE_ACTIVITY, Feature.DEMO)))
    }
}
