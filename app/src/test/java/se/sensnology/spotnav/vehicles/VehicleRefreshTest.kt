package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerActions
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The vehicle card's re-read control: when it is offered at all, when it can be pressed, and what
 * each of the three answers means.
 */
class VehicleRefreshTest {
    private fun control(
        offered: Boolean = true,
        hasSelectedVehicle: Boolean = true,
        inFlight: Boolean = false
    ) = VehicleRefresh.control(offered, hasSelectedVehicle, inFlight)

    // offering the control: a literal `true`, and nothing else

    @Test fun anAdvertisedCapabilityOffersTheControl() {
        assertTrue(VehicleRefresh.offered(ChargerCapabilities(refreshVehicle = true)))
    }

    @Test fun anExplicitFalseHidesIt() {
        assertFalse(VehicleRefresh.offered(ChargerCapabilities(refreshVehicle = false)))
    }

    // An integration that does not advertise it, and a dashboard that has not arrived yet: both
    // mean "cannot", not "unknown".
    @Test fun anIntegrationThatDoesNotAdvertiseItHidesIt() {
        assertFalse(VehicleRefresh.offered(ChargerCapabilities()))
        assertFalse(VehicleRefresh.offered(null))
    }

    // A value that is not really a boolean is not support: showing a control that could only answer
    // 400 is worse than showing none.
    @Test fun aCapabilityThatIsNotABooleanIsNotSupport() {
        val json = DashboardFixtures.json()
        json.getJSONObject("charger").getJSONObject("capabilities").put("refresh_vehicle", "true")
        val parsed = Dashboard.parse(json).capabilities

        assertFalse(parsed.refreshVehicle)
        assertFalse(VehicleRefresh.offered(parsed))
    }

    // enabled: something to be about, and nothing in flight

    @Test fun itIsPressedOnlyWhenThereIsAVehicleAndNothingIsInFlight() {
        assertTrue(control(hasSelectedVehicle = true, inFlight = false).enabled)
        assertFalse(control(hasSelectedVehicle = true, inFlight = true).enabled)
        assertFalse(control(hasSelectedVehicle = false, inFlight = false).enabled)
        assertFalse(control(offered = false, hasSelectedVehicle = true, inFlight = false).enabled)
    }

    @Test fun itIsNotDrawnUnlessTheCapabilityAllowsIt() {
        assertTrue(control(hasSelectedVehicle = false, inFlight = false).visible)
        assertFalse(control(offered = false, hasSelectedVehicle = true, inFlight = false).visible)
    }

    // the three answers, and what each has to say

    // Nothing: the values are the news, and the icon stops spinning.
    @Test fun aSuccessfulRefreshSaysNothing() {
        assertNull(VehicleRefresh.message(null))
        assertNull(VehicleRefresh.message(VehicleRefresh.Answer.Refreshed))
    }

    @Test fun aRefusalSaysToWaitAndForHowLong() {
        val message = VehicleRefresh.message(VehicleRefresh.Answer.TooSoon(43))

        assertEquals(VehicleRefresh.Kind.TOO_SOON, message?.kind)
        assertEquals(43, message?.retryAfterS)
    }

    // An unreadable wait is still a refusal: the card says "shortly" rather than inventing a
    // number.
    @Test fun aRefusalWithoutAReadableWaitStillSaysToWait() {
        val message = VehicleRefresh.message(VehicleRefresh.Answer.TooSoon(null))

        assertEquals(VehicleRefresh.Kind.TOO_SOON, message?.kind)
        assertNull(message?.retryAfterS)
    }

    // "Unknown vehicle", and everything else: one message, because the user can do nothing
    // different about any of them — and the integration deliberately does not say which cause it
    // was, so the card must not either.
    @Test fun aFailureSaysItDidNotHappen() {
        val message = VehicleRefresh.message(VehicleRefresh.Answer.Failed)

        assertEquals(VehicleRefresh.Kind.FAILED, message?.kind)
        assertNull(message?.retryAfterS)
    }

    // reading the exchange

    @Test fun aTwoHundredWithOkMeansTheReReadHappened() {
        assertEquals(
            VehicleRefresh.Answer.Refreshed,
            VehicleRefresh.answer(200, """{"ok": true, "action": "refresh_vehicle", "entity_count": 4}""")
        )
    }

    @Test fun aTwoHundredThatIsNotReallyOkIsAFailure() {
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(200, """{"ok": false}"""))
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(200, """{"ok": "true"}"""))
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(200, "not json at all"))
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(200, null))
    }

    @Test fun aTooManyRequestsCarriesHowLongToWait() {
        assertEquals(
            VehicleRefresh.Answer.TooSoon(43),
            VehicleRefresh.answer(429, """{"ok": false, "error": "Vehicle was refreshed too recently", "retry_after_s": 43}""")
        )
    }

    // A wait this app cannot read as a positive whole number of seconds is still a refusal: the
    // card says "shortly" rather than inventing a number.
    @Test fun aTooManyRequestsWithoutAReadableWaitIsStillARefusal() {
        assertEquals(VehicleRefresh.Answer.TooSoon(null), VehicleRefresh.answer(429, """{"ok": false}"""))
        assertEquals(VehicleRefresh.Answer.TooSoon(null), VehicleRefresh.answer(429, """{"retry_after_s": "43"}"""))
        assertEquals(VehicleRefresh.Answer.TooSoon(null), VehicleRefresh.answer(429, """{"retry_after_s": 0}"""))
        assertEquals(VehicleRefresh.Answer.TooSoon(null), VehicleRefresh.answer(429, """{"retry_after_s": -5}"""))
        assertEquals(VehicleRefresh.Answer.TooSoon(null), VehicleRefresh.answer(429, ""))
    }

    // "Unknown vehicle", and everything else: one meaning, because the user can do nothing
    // different about any of them — and the integration deliberately does not say which cause it
    // was, so the card must not either.
    @Test fun everyOtherAnswerIsTheSameFailure() {
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(400, """{"ok": false, "error": "Unknown vehicle"}"""))
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(502, """{"ok": false, "error": "Charger command failed"}"""))
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(404, ""))
    }

    // No HTTP answer at all: unreachable, refused, timed out.
    @Test fun noAnswerAtAllIsTheSameFailure() {
        assertEquals(VehicleRefresh.Answer.Failed, VehicleRefresh.answer(null, null))
    }

    // the request the app sends

    @Test fun thePayloadNamesTheActionAndTheVehicle() {
        val payload = JSONObject(
            HomeAssistantClient.payload(HomeAssistantCommand("refresh_vehicle", vehicleId = "device-1"))
        )

        assertEquals(1, payload.getInt("version"))
        assertEquals("refresh_vehicle", payload.getString("action"))
        assertEquals("device-1", payload.getString("vehicle_id"))
    }
}
