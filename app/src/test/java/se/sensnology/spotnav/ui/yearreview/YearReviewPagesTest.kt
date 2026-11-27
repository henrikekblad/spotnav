package se.sensnology.spotnav.ui.yearreview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.yearreview.YearReview
import se.sensnology.spotnav.ha.yearreview.YearReviewRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The story's words from the integration's own reviews, as the card's `year-review-pages.ts` words them. */
class YearReviewPagesTest {
    private val nbsp = ' '

    private fun review(name: String, edit: (JSONObject) -> Unit = {}): YearReview {
        val json = HaFixtures.json("year_review/v1/$name").also(edit)
        return YearReviewRead.decode(json, strict = false)!!.review!!
    }

    private fun text(language: String) = YearReviewText(
        CardStringsXml.words(language), language, Locale.forLanguageTag(language), Locale.forLanguageTag(language)
    )

    /** Swedish numbers group with a no-break space; the tests read them with a plain one. */
    private fun plain(line: String?) = line?.replace(nbsp, ' ')?.replace(' ', ' ')

    @Test fun theFullYearInSwedish() {
        val sv = text("sv")
        val pages = sv.pages(review("full_year.json"), supportReachable = true)
        assertEquals(listOf("cover", "saved", "car", "sun", "battery", "fuse", "expensive", "thanks"), pages.map { it.id })
        val cover = pages[0]
        assertEquals("Ditt 2026", cover.words)
        assertTrue(cover.fireworks)
        assertNull(cover.badge)
        val saved = pages[1]
        assertEquals(1483.4, saved.figure!!.value, 1e-9)
        assertEquals("kr", saved.figure!!.unit)
        assertTrue("≈ where estimated", saved.figure!!.approx)
        assertEquals(listOf("Batteriet 305 kr sedan 15 oktober", "Bilen 1 156 kr", "Värmen 22 kr sedan 1 november"), saved.chips.map(::plain))
        val car = pages[2]
        assertEquals(86.0, car.meter!!, 0.0)
        assertEquals("billigast 0,50 kr/kWh, 1 januari", car.chips[2])
        assertEquals("209 laddningar", car.chips[0])
        assertEquals("Priserna är spotpris plus dina avgifter.", car.foot.last())
        val sun = pages[3]
        assertTrue("export paid: sold", sun.say!!.endsWith("resten såldes."))
        val battery = pages[4]
        assertEquals(366.0, battery.figure!!.value, 1e-9)
        assertFalse(battery.figure!!.approx)
        assertEquals("≈ 18 varv", battery.chips[0])
        assertFalse("no kWh in/out chips", battery.chips.any { it.contains("kWh") && !it.contains("/kWh") })
        val fuse = pages[5]
        assertEquals("gånger", fuse.figure!!.unit)
        assertEquals("Sedan 1 februari.", fuse.foot[0])
        val expensive = pages[6]
        assertEquals(7.93, expensive.figure!!.value, 1e-9)
        assertEquals(2, expensive.figure!!.digits)
        assertEquals("kr/kWh", expensive.figure!!.unit)
        assertEquals("28 januari kl. 08:00. Då köpte du bara 0,3 kWh.", expensive.say)
        val thanks = pages[7]
        assertEquals("Tack för året!", thanks.words)
        assertEquals(listOf("Berättelsen finns kvar under Inställningar → Support till 31 januari."), thanks.foot)
    }

    @Test fun englishNeverSaysDearAndWordsTheFigures() {
        val en = text("en")
        val pages = en.pages(review("full_year.json"), supportReachable = false)
        val all = pages.flatMap { listOfNotNull(it.eyebrow, it.words, it.say) + it.chips + it.foot }
        assertTrue(all.none { it.contains("dear", ignoreCase = true) })
        assertTrue(all.none { Regex("\\b(smart|AI|we|us)\\b").containsMatchIn(it) })
        assertEquals("28 January at 08:00. You bought only 0.3 kWh then.", pages.first { it.id == "expensive" }.say)
        assertTrue("no foot without Settings → Support", pages.last().foot.isEmpty())
    }

    @Test fun theSunWithoutPaidExportWentOutToTheGrid() {
        val sv = text("sv")
        val review = review("full_year.json") { json ->
            json.getJSONObject("review").getJSONArray("pages").getJSONObject(3).put("export_paid", false)
        }
        val sun = sv.pages(review, true).first { it.id == "sun" }
        assertTrue(sun.say!!.endsWith("resten gick ut på nätet."))
    }

    @Test fun aCarWithoutACheapShareHasNoFigure() {
        val review = review("full_year.json") { json ->
            json.getJSONObject("review").getJSONArray("pages").getJSONObject(2).put("cheap_share_pct", JSONObject.NULL)
        }
        val car = text("sv").pages(review, true).first { it.id == "car" }
        assertNull(car.figure)
        assertNull(car.meter)
        assertEquals("Bilen laddade hemma i år.", car.say)
    }

    @Test fun aBatteryWithoutOwnMoneyTellsWhatItSavedElseWhatItGaveBack() {
        val savedOnly = review("full_year.json") { json ->
            json.getJSONObject("review").getJSONArray("pages").getJSONObject(4).put("earned", JSONObject.NULL)
        }
        val saved = text("sv").pages(savedOnly, true).first { it.id == "battery" }
        assertTrue(saved.figure!!.approx)
        assertEquals(2175.0, saved.figure!!.value, 1e-9)
        assertEquals(listOf("≈ 18 varv"), saved.chips)
        val kwhOnly = review("full_year.json") { json ->
            json.getJSONObject("review").getJSONArray("pages").getJSONObject(4).put("earned", JSONObject.NULL).put("saved", JSONObject.NULL)
        }
        val gave = text("sv").pages(kwhOnly, true).first { it.id == "battery" }
        assertEquals("kWh", gave.figure!!.unit)
        assertEquals(1044.0, gave.figure!!.value, 1e-9)
        val nothing = review("full_year.json") { json ->
            json.getJSONObject("review").getJSONArray("pages").getJSONObject(4)
                .put("earned", JSONObject.NULL).put("saved", JSONObject.NULL).put("kwh_out", JSONObject.NULL)
        }
        assertFalse(text("sv").pages(nothing, true).any { it.id == "battery" })
    }

    @Test fun aPartYearSaysSinceWhenAndCarriesTheBadge() {
        val review = review("fresh_install.json")
        val cover = text("sv").pages(review, true).first()
        if (review.since != null) assertTrue(cover.say!!.startsWith("SpotNav har styrt hos dig sedan "))
        assertEquals(review.partial, cover.badge != null)
    }

    @Test fun everyFixtureIsWordedInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) for (file in HaFixtures.files("year_review/v1")) {
            val answer = YearReviewRead.decode(JSONObject(file.readText()), strict = true)!!
            val review = answer.review ?: continue
            val pages = text(language).pages(review, true)
            assertEquals("${file.name} $language starts with the cover", "cover", pages.first().id)
            assertEquals("${file.name} $language ends with thanks", "thanks", pages.last().id)
            assertTrue(pages.all { it.eyebrow.isNotEmpty() })
        }
    }
}
