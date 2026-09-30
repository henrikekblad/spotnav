package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures

/** Whether the app may write the car's own charge limit, and what it says about the answer. */
class ChargeLimitTest {
    private fun vehicle(limit: Double? = 90.0) = VehicleStatus(
        id = "device-1",
        name = "EV6",
        socPercent = 55.0,
        targetSocPercentMax = limit
    )

    private fun capabilities(setChargeLimit: Boolean) =
        ChargerCapabilities(setChargeLimit = setChargeLimit)

    @Test fun theWriteIsOfferedOnlyOnALiteralTrueAndALiveLimit() {
        assertTrue(ChargeLimit.offered(capabilities(true), vehicle()))

        // Either half missing is "no control".
        assertFalse(ChargeLimit.offered(capabilities(true), vehicle(limit = null)))
        assertFalse(ChargeLimit.offered(capabilities(false), vehicle()))
        // Nothing known at all: no status yet, or no vehicle to be about.
        assertFalse(ChargeLimit.offered(null, vehicle()))
        assertFalse(ChargeLimit.offered(capabilities(true), null))
    }

    @Test fun aCapabilityThatIsNotReallyBooleanIsNotSupport() {
        // The strict read is the one that keeps an odd value from offering a write to a car, and
        // `offered` can only act on what the read produced.
        val json = DashboardFixtures.json()
        json.getJSONObject("charger").getJSONObject("capabilities").put("set_charge_limit", "true")
        val capabilities = Dashboard.parse(json).capabilities

        assertFalse(capabilities.setChargeLimit)
        assertFalse(ChargeLimit.offered(capabilities, vehicle()))
    }

    @Test fun theControlHidesWhenItIsNotOfferedAndWaitsWhileAWriteIsRunning() {
        assertFalse(ChargeLimit.control(offered = false, inFlight = false).visible)
        assertFalse(ChargeLimit.control(offered = false, inFlight = true).visible)
        assertFalse(ChargeLimit.control(offered = false, inFlight = false).enabled)

        assertTrue(ChargeLimit.control(offered = true, inFlight = false).enabled)
        // A second press while one is on its way would be the same write again, and the integration
        // would answer 429 for it — a refusal the user caused by pressing twice rather than by
        // anything being wrong.
        assertFalse(ChargeLimit.control(offered = true, inFlight = true).enabled)
        assertTrue(ChargeLimit.control(offered = true, inFlight = true).visible)
    }

    @Test fun anAnswerIsSaidOnlyWhenThereIsSomethingToSay() {
        // Nothing asked, and nothing went wrong: nothing to say.
        assertNull(ChargeLimit.message(null))
        // The write happening is not news this card can report.
        assertNull(ChargeLimit.message(ChargeLimit.Answer.Set))
        assertEquals(
            ChargeLimit.Message(ChargeLimit.Kind.TOO_SOON, 43),
            ChargeLimit.message(ChargeLimit.Answer.TooSoon(43))
        )
        // A 429 whose body this app cannot read as seconds is still a refusal, and still says
        // "shortly" rather than inventing a number.
        assertEquals(
            ChargeLimit.Message(ChargeLimit.Kind.TOO_SOON, null),
            ChargeLimit.message(ChargeLimit.Answer.TooSoon(null))
        )
        assertEquals(
            ChargeLimit.Message(ChargeLimit.Kind.FAILED, null),
            ChargeLimit.message(ChargeLimit.Answer.Failed)
        )
    }
}
