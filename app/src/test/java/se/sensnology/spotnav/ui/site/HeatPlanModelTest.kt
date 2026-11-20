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
        // The hot water's own runs today (the meter's, the button's), not the band's hours.
        assertEquals(2, c.water.size)
        assertEquals(4, chart(heat { it.put("hot_water", org.json.JSONObject.NULL) }).water.size)
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
            json.put("hot_water", org.json.JSONObject.NULL)
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
                val c = HeatPlanModel.today(h, now, t) ?: continue
                val said = HeatPlanModel.legend(t, c).map { it.label } + HeatPlanModel.reviewLines(t, h).map { it.second } +
                    HeatPlanModel.hotWaterLines(t, h).map { it.second } + h.hotWater?.planned.orEmpty().map { HeatPlanModel.plannedWaterText(t, h, it) } +
                    h.hotWater?.blocking?.cuts.orEmpty().flatMap { listOf(HeatPlanModel.cutText(t, it), HeatPlanModel.cutText(t, it.copy(live = true))) } +
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

    @Test fun thePlannedBoostsAndThePeaksAreOnTodaysChart() {
        val h = heat()
        val c = HeatPlanModel.today(h, now, text())!!
        // Today's two would-be boosts (tomorrow's is not today's), and the peaks as ticks.
        assertEquals(2, c.planned.size)
        assertEquals(listOf("dusch 05:00", "dusch 17:00"), c.peaks.map { it.label })
        // Today's cut; tomorrow's is not today's.
        assertEquals(1, c.notes.size)
        // The readout says the boost, the peak and the cut at their step.
        assertTrue(HeatPlanModel.figures(text(), c, Instant.parse("2026-11-16T13:00:00Z").toEpochMilli()).contains("Skulle ha värmt varmvatten 14:00"))
        assertTrue(HeatPlanModel.figures(text(), c, Instant.parse("2026-11-16T04:00:00Z").toEpochMilli()).contains("dusch 05:00"))
        assertTrue(HeatPlanModel.figures(text(), c, Instant.parse("2026-11-16T15:05:00Z").toEpochMilli()).contains("skulle förkorta blockeringen före duschen 17:00"))
        // Without words asked for, the chart still has the marks.
        assertEquals(2, HeatPlanModel.today(h, now)!!.planned.size)
        // A day with only planned boosts still names the hot water in the legend.
        val plannedOnly = heat { json -> json.getJSONObject("hot_water").put("runs_today", org.json.JSONArray()) }
        assertTrue(HeatPlanModel.legend(text(), HeatPlanModel.today(plannedOnly, now, text())!!).any { it.kind == "hot_water" })
    }

    @Test fun eachBoostInWordsAsTheCardSaysIt() {
        val h = heat()
        val (morning, evening, tomorrow) = h.hotWater!!.planned
        assertEquals("Skulle ha värmt varmvatten 03:15 · pumpen körde själv 05:10", HeatPlanModel.plannedWaterText(text(), h, morning))
        assertEquals("Skulle ha värmt varmvatten 14:00", HeatPlanModel.plannedWaterText(text(), h, evening))
        assertEquals("Skulle värma varmvatten 03:30–04:00 · 76 öre/kWh · klart till 05:00", HeatPlanModel.plannedWaterText(text(), h, tomorrow))
        assertEquals(
            "Varmvatten 03:30–04:00 · 76 öre/kWh · klart till 05:00 · på solel",
            HeatPlanModel.plannedWaterText(text(), h, tomorrow.copy(live = true, reason = "sun"))
        )
    }

    @Test fun theCutHoursInWords() {
        val cut = heat().hotWater!!.blocking!!.cuts[0]
        assertEquals("skulle förkorta blockeringen före duschen 17:00", HeatPlanModel.cutText(text(), cut))
        assertEquals("blockering förkortad före duschen 17:00", HeatPlanModel.cutText(text(), cut.copy(live = true)))
        assertEquals("blockering förkortad så att varmvattnet hinner värmas", HeatPlanModel.cutText(text(), cut.copy(reason = "day_cap", peak = null, live = true)))
        assertEquals("skulle förkorta blockeringen för varmvattnets periodiska höjning", HeatPlanModel.cutText(text(), cut.copy(reason = "hygiene", peak = null)))
        assertEquals("blockering flyttad för varmvattnet", HeatPlanModel.cutText(text(), cut.copy(reason = "moved", peak = null, live = true)))
    }

    @Test fun theHotWatersLinesUnderTheReview() {
        val lines = HeatPlanModel.hotWaterLines(text(), heat())
        assertEquals(listOf("water-gate", "water-blocking-gate", "water-review", "water-peaks", "water-blocking-review"), lines.map { it.first })
        assertEquals(
            listOf(
                "Varmvatten i skuggning: dag 5 av 7, 8 av 10 toppar träffade",
                "Blockeringar kring varmvattnet i skuggning: dag 5 av 14, 8 av 10 toppar träffade",
                "Varmvatten i går: 1,1 kWh flyttade · 0,61 kr sparade (skuggning)",
                "Toppar: 05:00 värmdes sent efter en blockering · skulle ha värmt varmvatten 03:15 · pumpen körde själv 05:10; 17:00 ingen tappning",
                "Blockeringar i går: 2 h skulle ha förkortats för varmvattnet · 1 sena uppvärmningar före en topp"
            ),
            lines.map { it.second }
        )
        // Watch only (the pump's own price control): nothing about SpotNav's hot water.
        assertEquals(emptyList<Pair<String, String>>(), HeatPlanModel.hotWaterLines(text(), heat("network_pump.json")))
        assertEquals(emptyList<Pair<String, String>>(), HeatPlanModel.hotWaterLines(text(), heat("sg_api_pump.json")))
        val week = heat { it.getJSONObject("hot_water").getJSONObject("gate").put("days", 8).put("weekdays", 6) }
        assertEquals("Varmvatten i skuggning: dag 7 av 7, 8 av 10 toppar träffade · 6 av 7 veckodagar", HeatPlanModel.hotWaterLines(text(), week)[0].second)
        val ready = heat { it.getJSONObject("hot_water").getJSONObject("gate").put("open", true) }
        assertEquals("Varmvatten: redo att styras med ”Styr värmen”", HeatPlanModel.hotWaterLines(text(), ready)[0].second)
        val live = heat { it.getJSONObject("hot_water").getJSONObject("blocking").put("mode", "live").put("strict", true) }
        val liveLines = HeatPlanModel.hotWaterLines(text(), live).toMap()
        assertEquals("SpotNav håller sina blockeringar undan varmvattnet", liveLines["water-blocking-live"])
        assertEquals("Blockeringarna hålls längre från varmvattnet efter för många sena uppvärmningar", liveLines["water-blocking-backed-off"])
        val none = heat { it.getJSONObject("hot_water").put("blocking", org.json.JSONObject.NULL) }
        assertEquals(listOf("water-gate", "water-review", "water-peaks"), HeatPlanModel.hotWaterLines(text(), none).map { it.first })
    }
}
