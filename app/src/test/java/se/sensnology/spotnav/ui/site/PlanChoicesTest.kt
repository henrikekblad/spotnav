package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SiteCapabilities

class PlanChoicesTest {
    @Test fun whereALanesChoicesGo() {
        assertEquals(PlanChoiceScope.Target("entry_b"), PlanChoiceScope.target(PlanChoiceScope.Home, "entry_b"))
        assertEquals(PlanChoiceScope.Target("entry_b"), PlanChoiceScope.target(PlanChoiceScope.Charger("entry_a", behindHome = true), "entry_b"))
        assertEquals(PlanChoiceScope.Target(null), PlanChoiceScope.target(PlanChoiceScope.Charger("entry_a", behindHome = false), "entry_a"))
        assertNull(PlanChoiceScope.target(PlanChoiceScope.Charger("entry_a", behindHome = false), "entry_b"))
        assertNull(PlanChoiceScope.target(PlanChoiceScope.Charger(null, behindHome = false), "entry_b"))
    }

    @Test fun anOlderHomeAssistantIsAskedAgainOnViewAfterAQuarterOrOnNewCapabilities() {
        assertTrue(PlanProbe.ask(null, null, 0, false))
        assertTrue(PlanProbe.ask(true, null, 0, false))
        assertFalse("get_site says no", PlanProbe.ask(false, null, 0, true))
        assertFalse(PlanProbe.ask(null, 1_000, 1_000 + 60_000, false))
        assertTrue("in view again", PlanProbe.ask(null, 1_000, 1_000 + 60_000, true))
        assertTrue("a quarter later", PlanProbe.ask(null, 1_000, 1_000 + PlanProbe.RETRY_MS, false))
        val memory = SiteMemory()
        memory.capabilitiesSeen(SiteCapabilities(getHouse = true))
        memory.planRefusedAt = 5
        memory.capabilitiesSeen(SiteCapabilities(getHouse = true))
        assertEquals("the same capabilities keep the refusal", 5L, memory.planRefusedAt)
        memory.capabilitiesSeen(SiteCapabilities(getHouse = true, getPlan = true))
        assertNull("an update asks again at once", memory.planRefusedAt)
    }
}
