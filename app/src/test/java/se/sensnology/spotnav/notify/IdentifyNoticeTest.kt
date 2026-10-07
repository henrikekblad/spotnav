package se.sensnology.spotnav.notify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * "Which car is plugged in?" as this phone's own notification: when a check posts it, which buttons
 * it carries, when a later check takes it off, and what a tapped button does.
 */
class IdentifyNoticeTest {
    private val since = "2026-10-06T17:00:00+00:00"

    private fun block(state: String, cars: List<Pair<String, String>> = listOf("vehicle_niro" to "Niro", "vehicle_ev6" to "EV6"), at: String = since) =
        JSONObject().put("state", state).put("method", "assumed").put("vehicle_id", cars.first().first)
            .put("since", at)
            .put("candidates", JSONArray().apply {
                cars.forEach { (id, name) -> put(JSONObject().put("vehicle_id", id).put("name", name).put("likely", false)) }
            })
            .put("evidence", JSONArray())

    private fun dashboard(identification: JSONObject? = null, edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", identification ?: JSONObject.NULL)
            edit()
        }

    private fun older(identification: JSONObject): Dashboard = dashboard(identification) {
        getJSONObject("settings").remove("identify_mode")
        getJSONObject("settings").remove("vehicle_ids")
    }

    private fun step(dash: Dashboard, chosen: Boolean = true, posted: String? = null, inApp: String? = null) =
        IdentifyNotice.step(dash, chosen, posted, inApp)

    // When to post

    @Test fun anOpenQuestionIsPostedOnceWithTheCarsInTheGivenOrder() {
        val post = step(dashboard(block("asking"))) as IdentifyNotice.Step.Post
        assertEquals(since, post.question.key)
        assertEquals(listOf("vehicle_niro", "vehicle_ev6"), post.question.cars.map { it.vehicleId })
        assertEquals(listOf("Niro", "EV6"), post.question.cars.map { it.name })
        assertFalse(post.question.openButton)
        // The same question again (a later check, another wake-up) is not posted again, even when swiped.
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(block("asking")), posted = since))
    }

    @Test fun aNewPlugInsQuestionReplacesTheOldOne() {
        val later = "2026-10-06T19:00:00+00:00"
        val post = step(dashboard(block("asking", at = later)), posted = since) as IdentifyNotice.Step.Post
        assertEquals(later, post.question.key)
    }

    @Test fun nothingIsPostedWhileTheCarsAreStillLookedAt() {
        // Home Assistant asks the phones only once it asks; while it waits, a car's own report may still settle it.
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(block("waiting"))))
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(block("decided"))))
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(null)))
    }

    @Test fun nothingIsPostedWhenTheEventIsNotChosenOrHomeAssistantDoesNotIdentify() {
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(block("asking")), chosen = false))
        assertEquals(IdentifyNotice.Step.Keep, step(older(block("asking"))))
        // A question without cars has no buttons to offer.
        assertEquals(IdentifyNotice.Step.Keep, step(dashboard(block("asking").put("candidates", JSONArray()))))
    }

    @Test fun theQuestionTheBannerShowsInViewIsNotPostedAsWell() {
        assertEquals(IdentifyNotice.Step.Suppress, step(dashboard(block("asking")), inApp = since))
        // Another question than the one in view is posted.
        assertTrue(step(dashboard(block("asking")), inApp = "2026-10-06T15:00:00+00:00") is IdentifyNotice.Step.Post)
    }

    // Which buttons

    @Test fun upToThreeCarsEachGetAButton() {
        val three = listOf("a" to "A", "b" to "B", "c" to "C")
        val post = step(dashboard(block("asking", cars = three))) as IdentifyNotice.Step.Post
        assertEquals(listOf("a", "b", "c"), post.question.cars.map { it.vehicleId })
        assertFalse(post.question.openButton)
    }

    @Test fun withMoreThanThreeCarsTheFirstTwoGetAButtonAndTheThirdOpensTheApp() {
        val four = listOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val post = step(dashboard(block("asking", cars = four))) as IdentifyNotice.Step.Post
        assertEquals(listOf("a", "b"), post.question.cars.map { it.vehicleId })
        assertTrue(post.question.openButton)
    }

    // When to retire

    @Test fun aDecidedOrGoneQuestionIsTakenOff() {
        assertEquals(IdentifyNotice.Step.Retire, step(dashboard(block("decided")), posted = since))
        // Unplugged: no block at all.
        assertEquals(IdentifyNotice.Step.Retire, step(dashboard(null), posted = since))
        // Back to looking at the cars (a replug): the question asked before is gone.
        assertEquals(IdentifyNotice.Step.Retire, step(dashboard(block("waiting")), posted = since))
        // The event turned off.
        assertEquals(IdentifyNotice.Step.Retire, step(dashboard(block("asking")), chosen = false, posted = since))
    }

    // A tapped button

    @Test fun aButtonAnswersOnlyTheQuestionItWasPostedFor() {
        assertEquals(IdentifyNotice.Tap.ANSWER, IdentifyNotice.tap(dashboard(block("asking")), since))
        assertEquals(IdentifyNotice.Tap.GONE, IdentifyNotice.tap(dashboard(block("decided")), since))
        assertEquals(IdentifyNotice.Tap.GONE, IdentifyNotice.tap(dashboard(null), since))
        assertEquals(IdentifyNotice.Tap.GONE, IdentifyNotice.tap(dashboard(block("asking", at = "2026-10-06T19:00:00+00:00")), since))
        assertEquals(IdentifyNotice.Tap.FAILED, IdentifyNotice.tap(null, since))
    }

    // What a profile remembers

    @Test fun theStoreRemembersThePostedQuestionAndWhetherHomeAssistantIdentifiesPerProfile() {
        val store = LocalNotificationStore(se.sensnology.spotnav.testing.FakeKeyValueStore())
        assertEquals(null, store.identifyPosted("a"))
        assertFalse(store.identifies("a"))
        store.setIdentifyPosted("a", since)
        assertTrue(store.setIdentifies("a", true))
        // Unchanged: nothing to bring up to date.
        assertFalse(store.setIdentifies("a", true))
        assertEquals(since, store.identifyPosted("a"))
        assertEquals(null, store.identifyPosted("b"))
        assertEquals(setOf("a"), store.identifying(listOf("a", "b")))
        store.forget("a")
        assertEquals(null, store.identifyPosted("a"))
        assertFalse(store.identifies("a"))
    }
}
