package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.prices.IncludedPart
import se.sensnology.spotnav.testing.HaFixtures
import java.io.File
import java.time.LocalDate

/** Home Assistant's pinned `price_days` answers, read as the app reads them. */
class PriceDaysReadTest {
    private fun body(name: String): String = File(HaFixtures.root, "webhook/$name.json").readText()

    private fun read(name: String): PriceDaysRead = checkNotNull(PriceDaysDecoder.decode(body(name))) { "$name decodes" }

    @Test fun theRelaysSwedishDaysAreReadWithTheirRate() {
        val read = read("price_days_relay_se4")
        assertEquals("SE4", read.area.id)
        assertEquals("SEK", read.area.currency)
        assertEquals("Europe/Stockholm", read.area.marketTz)
        assertEquals("relay", read.providerKey)
        assertEquals(listOf(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-23")), read.files.keys.toList())
    }

    @Test fun nordPoolsFinnishDaysAreOnTheBrusselsCalendar() {
        val read = read("price_days_nordpool_fi")
        assertEquals("FI", read.area.id)
        assertEquals("Europe/Helsinki", read.area.tz)
        // Home Assistant's provider dates the files in Brussels, unlike the list's Finnish calendar.
        assertEquals("Europe/Brussels", read.area.marketTz)
        assertEquals("12:45", read.area.publication.time.toString())
        assertEquals("nordpool", read.providerKey)
        assertEquals(3, read.files.size)
    }

    @Test fun aGreatBritainRegionWithoutAnEicIsReadWithItsIncludedParts() {
        val read = read("price_days_relay_gb")
        assertEquals("GB-C", read.area.id)
        assertEquals("GBP", read.area.currency)
        assertEquals("Europe/Paris", read.area.marketTz)
        assertTrue(read.area.includes(IncludedPart.VAT))
        assertEquals(2, read.files.size)
    }

    @Test fun refusalsAreHomeAssistantsCodes() {
        assertEquals(PriceDaysAnswer.Refused("spotnav_no_price_area"), PriceDaysDecoder.answer(400, body("price_days_no_price_area")))
        assertEquals(PriceDaysAnswer.Refused("spotnav_no_prices"), PriceDaysDecoder.answer(400, body("price_days_no_prices")))
    }

    @Test fun anAnswerKeepsTheTextItWasReadFrom() {
        val text = body("price_days_nordpool_fi")
        val answer = PriceDaysDecoder.answer(200, text) as PriceDaysAnswer.Answered
        assertEquals(text, answer.body)
        assertEquals(read("price_days_nordpool_fi"), answer.read)
    }

    @Test fun anythingElseIsAFailureNotARefusal() {
        assertEquals(PriceDaysAnswer.Failed, PriceDaysDecoder.answer(null, null))
        assertEquals(PriceDaysAnswer.Failed, PriceDaysDecoder.answer(500, body("price_days_no_prices")))
        assertEquals(PriceDaysAnswer.Failed, PriceDaysDecoder.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(PriceDaysAnswer.Failed, PriceDaysDecoder.answer(200, "not json"))
        assertEquals(PriceDaysAnswer.Failed, PriceDaysDecoder.answer(200, body("price_days_no_prices")))
    }

    @Test fun anotherVersionOrAnUnreadableAreaIsNoAnswer() {
        val fi = JSONObject(body("price_days_nordpool_fi"))
        assertNull(PriceDaysDecoder.decode(JSONObject(fi.toString()).put("v", 2).toString()))
        assertNull(PriceDaysDecoder.decode(JSONObject(fi.toString()).put("ok", "true").toString()))
        assertNull(PriceDaysDecoder.decode(JSONObject(fi.toString()).apply { getJSONObject("area").put("tz", "Not/AZone") }.toString()))
        assertNull(PriceDaysDecoder.decode(JSONObject(fi.toString()).apply { remove("area") }.toString()))
    }

    @Test fun oneBadFileIsDroppedNotTheAnswer() {
        val fi = JSONObject(body("price_days_nordpool_fi"))
        val files = fi.getJSONObject("files")
        // A file on another calendar than the area's, one for another area, and a key that is no date.
        files.getJSONObject("2026-10-29").put("market_tz", "Europe/Helsinki")
        files.getJSONObject("2026-10-30").put("area", "SE4")
        files.put("tomorrow", files.getJSONObject("2026-10-31"))
        val read = checkNotNull(PriceDaysDecoder.decode(fi.toString())) { "the answer stands" }
        assertEquals(listOf(LocalDate.parse("2026-10-31")), read.files.keys.toList())
    }

    @Test fun unknownKeysAreIgnoredAndTheProfileIsNotRead() {
        val fi = JSONObject(body("price_days_nordpool_fi")).put("later", JSONObject().put("x", 1)).put("profile", "anything")
        assertEquals(read("price_days_nordpool_fi"), PriceDaysDecoder.decode(fi.toString()))
    }

    @Test fun theRequestNamesTheActionItsVersionAndTheReads() {
        val payload = PriceDaysDecoder.payload()
        assertEquals("price_days", payload.getString("action"))
        assertEquals(1, payload.getInt("api_version"))
        assertEquals(1, payload.getInt("version"))
        assertEquals(WebhookReads.FIELDS.size, payload.getJSONArray("reads").length())
    }
}
