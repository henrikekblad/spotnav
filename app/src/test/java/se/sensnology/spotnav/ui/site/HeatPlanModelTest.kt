package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.heat.HeatHistoryRead
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/** The Plan box as the card words it (the card's `heat-card.test.ts` chart and review cases, on the same fixtures). */
class HeatPlanModelTest {
    private val now = Instant.parse("2026-11-16T16:15:00Z").toEpochMilli()

    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun heat(name: String = "villa.json", edit: (org.json.JSONObject) -> Unit = {}): Heat {
        val json = HaFixtures.json("heat_card/$name")
        edit(json)
        return (HeatRead.decode(json) as SiteRead.Read).value
    }

    private fun history(name: String): HeatHistory = (HeatHistoryRead.decode(HaFixtures.json("heat_history/$name")) as SiteRead.Read).value

    private fun chart(h: Heat = heat()) = HeatPlanModel.today(h, now)!!

    @Test fun todaysChartHasTheRoomsThePlansHoursAndTheNowLine() {
        val c = chart()
        assertEquals(69, c.points.size)
        assertEquals(24, c.hours.size)
        assertEquals(now, c.nowMs)
        assertTrue(c.withColdest)
        assertTrue(c.shadow)
        assertEquals(7, c.hours.count { HeatPlanModel.lowering(it).planned })
        assertEquals(2, c.water.size)
        // The car's four hours in a row are one bar.
        assertEquals(1, HeatPlanModel.carRuns(c).size)
        assertEquals(4 * HeatPlanModel.HOUR_MS, HeatPlanModel.carRuns(c).single().let { it.endMs - it.startMs })
        // 3 hours the relay closed and 2 it is expected to.
        assertEquals(3, c.hours.count { val l = HeatPlanModel.lowering(it); l.relay > 0 && !l.relayExpected })
        assertEquals(2, c.hours.count { HeatPlanModel.lowering(it).relayExpected })
    }

    @Test fun theLegendHasOnlyWhatTheDayHas() {
        assertEquals(
            listOf("Rummen, medel", "Kallaste rummet", "Börvärde (SpotNav sänker)", "Komfortband 20,5–21,5 °C", "Planerad sänkning", "Reläet sänkte", "Reläet väntas sänka", "Varmvatten", "Bilen laddar"),
            HeatPlanModel.legend(text(), chart()).map { it.label }
        )
        // Nothing lowered and no room that differs from the mean: plain setpoint.
        val flat = heat { json ->
            val hours = json.getJSONObject("band").getJSONArray("hours")
            for (i in 0 until hours.length()) hours.getJSONObject(i).put("action", "normal").put("target_c", 21).put("relay_min", org.json.JSONObject.NULL).put("hot_water", false).put("car", false)
            val points = json.getJSONObject("indoor_day").getJSONArray("points")
            for (i in 0 until points.length()) points.getJSONObject(i).put("coldest_c", points.getJSONObject(i).getDouble("mean_c"))
        }
        assertEquals(listOf("Rooms, mean", "Setpoint", "Comfort band 20.5–21.5 °C"), HeatPlanModel.legend(text("en"), chart(flat)).map { it.label })
    }

    @Test fun aChosenStepSaysItsFiguresInWords() {
        val c = chart()
        val at = { iso: String -> Instant.parse(iso).toEpochMilli() }
        assertEquals("17:30 · börvärde 20,5 °C · reläet väntas sänka 60 min · planerad sänkning", HeatPlanModel.figures(text(), c, at("2026-11-16T16:30:00Z")))
        assertTrue(HeatPlanModel.figures(text(), c, at("2026-11-15T23:00:00Z")).let { Regex("^00:00 · rummen 21,2 °C · kallast Toalett NV 20,7 °C · börvärde 20,0 °C · natt$").matches(it) })
        // A time inside a step is the step's own.
        assertEquals(HeatPlanModel.figures(text(), c, at("2026-11-16T16:30:00Z")), HeatPlanModel.figures(text(), c, at("2026-11-16T16:41:00Z")))
        assertEquals(0, HeatPlanModel.stepIndex(c, c.startMs))
        assertEquals(HeatPlanModel.stepCount(c) - 1, HeatPlanModel.stepIndex(c, c.endMs + 1))
    }

    @Test fun aLoweringForAHouseThatIsTooWarmSaysSo() {
        val warm = heat { json -> json.getJSONObject("band").getJSONArray("hours").getJSONObject(17).put("reason", "room_warm") }
        assertEquals(
            "17:30 · börvärde 20,5 °C · reläet väntas sänka 60 min · planerad sänkning · för varmt inne",
            HeatPlanModel.figures(text(), chart(warm), Instant.parse("2026-11-16T16:30:00Z").toEpochMilli())
        )
    }

    @Test fun theDayWithoutRoomsIsStillDrawnAndWithoutBothItIsNot() {
        val bare = heat { it.put("indoor_day", org.json.JSONObject.NULL) }
        val c = chart(bare)
        assertTrue(c.points.isEmpty())
        assertEquals(24, c.hours.size)
        assertNotNull(HeatPlanModel.today(bare, now))
        val none = heat { it.put("indoor_day", org.json.JSONObject.NULL); it.getJSONObject("band").put("hours", org.json.JSONArray()) }
        assertNull(HeatPlanModel.today(none, now))
    }

    @Test fun yesterdaysReviewIsTwoLines() {
        assertEquals(
            listOf(
                "I går sänkte reläet 3 h: 1,58 kr · kallast 20,4\u00a0°C · 20 min under 20,5\u00a0°C",
                "SpotNavs plan 5 h: 3,19 kr · kallast 20,4\u00a0°C · 20 min under 20,5\u00a0°C · 2 h samma som reläet"
            ),
            HeatPlanModel.reviewLines(text(), heat()).map { it.second }
        )
        assertEquals(emptyList<Pair<String, String>>(), HeatPlanModel.reviewLines(text(), heat { it.put("review", org.json.JSONObject.NULL) }))
    }

    @Test fun aPastDayIsDrawnFromItsHistory() {
        val past = HeatPlanModel.past(history("villa_day.json"))!!
        assertTrue(past.points.isNotEmpty())
        assertTrue(past.hours.isNotEmpty())
        assertNull(past.nowMs)
        assertEquals(3, past.water.size)
        // The history's hours carry the kept plan alone: no relay, no car.
        assertTrue(past.hours.all { it.relayMin == null && !it.car })
        assertNull(HeatPlanModel.past(history("villa_month.json")))
    }

    @Test fun everyWordIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            for (name in listOf("villa.json", "controlled.json", "network_pump.json")) {
                val h = heat(name)
                val c = HeatPlanModel.today(h, now) ?: continue
                val said = HeatPlanModel.legend(t, c).map { it.label } + HeatPlanModel.reviewLines(t, h).map { it.second } +
                    (0 until HeatPlanModel.stepCount(c)).map { HeatPlanModel.figures(t, c, c.startMs + it * c.stepMs) }
                for (line in said) assertFalse("$language $name: $line", line.contains("heat.") || line.contains("{"))
            }
            val past = HeatPlanModel.past(history("zones_day.json"))!!
            for (line in HeatPlanModel.legend(t, past).map { it.label } + (0 until HeatPlanModel.stepCount(past)).map { HeatPlanModel.figures(t, past, past.startMs + it * past.stepMs) }) {
                assertFalse("$language: $line", line.contains("heat.") || line.contains("{"))
            }
            assertTrue(t.words.say("heat.chart.hint").isNotEmpty())
        }
    }
}
