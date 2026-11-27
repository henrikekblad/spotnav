package se.sensnology.spotnav.ui.yearreview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.yearreview.YearReviewAnswer
import se.sensnology.spotnav.ha.yearreview.YearReviewRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The picture to share, laid out as the card lays out its own, and when the band and the row show. */
class YearReviewShareLayoutTest {
    private val sv = YearReviewText(CardStringsXml.words("sv"), "sv", Locale.forLanguageTag("sv"), Locale.forLanguageTag("sv"))

    private fun answer(name: String, edit: (JSONObject) -> Unit = {}): YearReviewAnswer =
        YearReviewRead.decode(HaFixtures.json("year_review/v1/$name").also(edit), strict = false)!!

    @Test fun theFirstFourTilesPresent() {
        val tiles = YearReviewShareLayout.tiles(sv, answer("full_year.json").review!!)
        assertEquals(listOf("saved", "car", "sun", "battery"), tiles.map { it.art })
        assertEquals("≈ 1 483 kr", tiles[0].value.replace(' ', ' '))
        assertEquals("86 %", tiles[1].value.replace(' ', ' '))
        assertEquals("Batteriet tjänade", tiles[3].label)
        // Never a time or a date on the picture.
        assertFalse(tiles.any { Regex("\\d{2}:\\d{2}").containsMatchIn(it.value + it.label) })
    }

    @Test fun aBatteryWithoutItsOwnMoneyShowsWhatItSavedElseWhatItGaveBack() {
        val saved = answer("full_year.json") { it.getJSONObject("review").getJSONArray("pages").getJSONObject(4).put("earned", JSONObject.NULL) }
        val tile = YearReviewShareLayout.tiles(sv, saved.review!!).last()
        assertEquals("Batteriet sparade", tile.label)
        assertTrue(tile.value.startsWith("≈ "))
        val kwh = answer("full_year.json") {
            it.getJSONObject("review").getJSONArray("pages").getJSONObject(4).put("earned", JSONObject.NULL).put("saved", JSONObject.NULL)
        }
        assertEquals("Batteriet gav tillbaka", YearReviewShareLayout.tiles(sv, kwh.review!!).last().label)
    }

    @Test fun theTilesLeaveNoHole() {
        assertEquals(0, YearReviewShareLayout.boxes(0).size)
        val one = YearReviewShareLayout.boxes(1).single()
        assertEquals(80, one.x)
        assertEquals(920, one.width)
        assertEquals(760, one.height)
        val two = YearReviewShareLayout.boxes(2)
        assertTrue(two.all { it.width == 920 })
        assertEquals(listOf(400, 800), two.map { it.y })
        val three = YearReviewShareLayout.boxes(3)
        assertEquals(listOf(440, 440, 920), three.map { it.width })
        assertEquals(listOf(80, 560, 80), three.map { it.x })
        val four = YearReviewShareLayout.boxes(4)
        assertEquals(listOf(80, 560, 80, 560), four.map { it.x })
        assertEquals(listOf(400, 400, 800, 800), four.map { it.y })
        // Everything inside the picture, above its foot line.
        for (count in 1..4) for (box in YearReviewShareLayout.boxes(count)) {
            assertTrue(box.x + box.width <= YearReviewShareLayout.WIDTH - 80)
            assertTrue(box.y + box.height <= 1230)
        }
        assertEquals(4, YearReviewShareLayout.boxes(7).size)
    }

    @Test fun theLogoSitsTopRight() {
        assertEquals(840, YearReviewShareLayout.LOGO_X)
        assertEquals(76, YearReviewShareLayout.LOGO_Y)
        assertEquals(160, YearReviewShareLayout.LOGO_SIZE)
    }

    @Test fun aSubtitleNamesOnlyTheMonth() {
        val fresh = answer("fresh_install.json").review!!
        val sub = YearReviewShareLayout.subtitle(sv, fresh)
        if (fresh.since != null) assertTrue(sub.startsWith("med SpotNav, sedan ")) else assertEquals("med SpotNav", sub)
        assertFalse(Regex("\\d").containsMatchIn(sub))
    }

    @Test fun wrapAndFit() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals(listOf("Bilen laddad", "i billiga…"), YearReviewShareLayout.wrap(measure, "Bilen laddad i billiga timmar idag", 120))
        assertEquals(listOf("Sparat"), YearReviewShareLayout.wrap(measure, "Sparat", 120))
        assertEquals(84, YearReviewShareLayout.fit({ _, _ -> 10f }, "x", 100))
        assertEquals(48, YearReviewShareLayout.fit({ _, _ -> 1000f }, "x", 100))
        assertEquals(60, YearReviewShareLayout.fit({ _, px -> px * 1.6f }, "x", 100))
    }

    @Test fun theStarsFallTheSameEachTime() {
        val a = YearReviewShareLayout.seeded(2026)
        val b = YearReviewShareLayout.seeded(2026)
        val first = List(5) { a() }
        assertEquals(first, List(5) { b() })
        // The card's own sequence for the same year (`year-review-share.ts`, `seeded(2026)`).
        assertEquals(0.45540769933722913, first[0], 1e-15)
        assertEquals(0.30849614599719644, first[1], 1e-15)
        assertEquals(0.6611574492417276, first[2], 1e-15)
    }

    @Test fun theBandAndTheRow() {
        val ready = answer("full_year.json")
        assertTrue(YearReviewOffer.banner(ready, seenHere = false))
        assertFalse("seen on this phone: never again", YearReviewOffer.banner(ready, seenHere = true))
        assertTrue(YearReviewOffer.row(ready))
        val none = answer("state_none.json")
        assertFalse(YearReviewOffer.banner(none, false))
        assertFalse(YearReviewOffer.row(none))
        assertFalse("an older Home Assistant", YearReviewOffer.banner(null, false))
        assertFalse(YearReviewOffer.row(null))
        assertEquals("site:1|site_villa|2026", YearReviewOffer.seenKey("site:1", ready))
    }
}
