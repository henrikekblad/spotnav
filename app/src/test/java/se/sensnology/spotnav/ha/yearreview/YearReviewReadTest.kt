package se.sensnology.spotnav.ha.yearreview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate

/** `get_year_review` against the integration's own answers (the vendored fixtures), read strictly as the card's tests read them. */
class YearReviewReadTest {
    private fun fixture(name: String): JSONObject = HaFixtures.json("year_review/v1/$name")

    /** The webhook's envelope around the command's answer. */
    private fun overWebhook(json: JSONObject): String = JSONObject(json.toString()).put("ok", true).put("action", "get_year_review").toString()

    @Test fun everyFixtureIsReadStrictlyAndKnowsEveryKeyAndPage() {
        val files = HaFixtures.files("year_review/v1")
        assertTrue("the year review's fixtures are vendored", files.size >= 7)
        for (file in files) {
            val json = JSONObject(file.readText())
            val answer = YearReviewRead.decode(json, strict = true)
            assertNotNull("${file.name} reads strictly", answer)
            val review = answer!!.review
            if (answer.state == YearReviewAnswer.State.READY) {
                val ids = json.getJSONObject("review").getJSONArray("pages").let { pages -> (0 until pages.length()).map { pages.getJSONObject(it).getString("id") } }
                assertEquals("${file.name}: every page read", ids, review!!.pages.map { it.id })
            } else {
                assertNull(review)
            }
        }
    }

    @Test fun theFullYearsFigures() {
        val answer = YearReviewRead.decode(fixture("full_year.json"), strict = true)!!
        assertEquals("site_villa", answer.siteId)
        assertEquals(2026, answer.year)
        assertTrue(answer.banner)
        assertTrue(answer.row)
        val review = answer.review!!
        assertEquals(LocalDate.of(2026, 12, 14), review.through)
        assertEquals("kr", review.money?.majorUnit)
        val saved = review.pages.filterIsInstance<YearReviewPage.Saved>().single()
        assertEquals(148340.0, saved.total, 0.0)
        assertEquals(LocalDate.of(2026, 10, 15), saved.startsBattery)
        assertNull(saved.startsCar)
        val car = review.pages.filterIsInstance<YearReviewPage.Car>().single()
        assertEquals(86.0, car.cheapSharePct!!, 0.0)
        assertEquals(LocalDate.of(2026, 1, 1), car.cheapestDate)
        val sun = review.pages.filterIsInstance<YearReviewPage.Sun>().single()
        assertEquals(true, sun.exportPaid)
        val expensive = review.pages.filterIsInstance<YearReviewPage.Expensive>().single()
        assertEquals("2026-01-28T08:00:00+01:00", expensive.start)
    }

    @Test fun theWebhookAnswerIsTheCommandsInItsEnvelope() {
        val outcome = YearReviewRead.answer(200, overWebhook(fixture("full_year.json")))
        assertTrue(outcome is YearReviewRead.Outcome.Read)
        assertNotNull(YearReviewRead.decode(JSONObject(overWebhook(fixture("car_only.json"))), strict = true))
    }

    @Test fun stateNoneHasNoBandNoRowAndNoReview() {
        val outcome = YearReviewRead.answer(200, overWebhook(fixture("state_none.json"))) as YearReviewRead.Outcome.Read
        assertEquals(YearReviewAnswer.State.NONE, outcome.answer.state)
        assertFalse(outcome.answer.banner)
        assertFalse(outcome.answer.row)
        assertNull(outcome.answer.review)
    }

    @Test fun anOlderHomeAssistantHasNoReview() {
        assertEquals(YearReviewRead.Outcome.None, YearReviewRead.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(YearReviewRead.Outcome.None, YearReviewRead.answer(400, """{"ok": false, "error": "spotnav_no_site", "action": "get_year_review"}"""))
        assertEquals(YearReviewRead.Outcome.None, YearReviewRead.answer(200, "not json"))
        val newer = JSONObject(fixture("full_year.json").toString()).put("api_version", 2).put("ok", true)
        assertEquals(YearReviewRead.Outcome.None, YearReviewRead.answer(200, newer.toString()))
    }

    @Test fun noAnswerIsAskedAgainLater() {
        assertEquals(YearReviewRead.Outcome.Later, YearReviewRead.answer(null, null))
        assertEquals(YearReviewRead.Outcome.Later, YearReviewRead.answer(502, "<html>"))
    }

    @Test fun anUnknownKeyOrPageIsLetBeInTheAppAndRefusedInTheContract() {
        val json = JSONObject(fixture("full_year.json").toString())
        json.put("new_key", 1)
        json.getJSONObject("review").getJSONArray("pages").put(JSONObject().put("id", "pool"))
        val lenient = YearReviewRead.decode(json, strict = false)
        assertNotNull(lenient)
        assertFalse(lenient!!.review!!.pages.any { it.id == "pool" })
        assertNull(YearReviewRead.decode(json, strict = true))
    }

    @Test fun aBrokenPromiseIsNoReview() {
        val readyWithout = JSONObject(fixture("full_year.json").toString()).put("review", JSONObject.NULL)
        assertNull(YearReviewRead.decode(readyWithout, strict = false))
        val badPercent = JSONObject(fixture("full_year.json").toString())
        badPercent.getJSONObject("review").getJSONArray("pages").getJSONObject(2).put("cheap_share_pct", 140)
        assertNull(YearReviewRead.decode(badPercent, strict = false))
        val badStart = JSONObject(fixture("full_year.json").toString())
        badStart.getJSONObject("review").getJSONArray("pages").getJSONObject(6).put("start", "28 January")
        assertNull(YearReviewRead.decode(badStart, strict = false))
    }

    @Test fun thePayloads() {
        val read = YearReviewRead.payload("secret")
        assertEquals("get_year_review", read.getString("action"))
        assertEquals(1, read.getInt("api_version"))
        assertEquals("secret", read.getString("app_id"))
        val seen = YearReviewSeen.payload("secret", 2026, YearReviewSeen.Seen.DISMISSED)
        assertEquals("year_review_seen", seen.getString("action"))
        assertEquals(2026, seen.getInt("year"))
        assertEquals("dismissed", seen.getString("seen_action"))
        assertTrue(YearReviewSeen.accepted(200, """{"ok": true, "action": "year_review_seen"}"""))
        assertFalse(YearReviewSeen.accepted(400, """{"ok": false, "error": "Unsupported action"}"""))
    }
}
