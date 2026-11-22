package se.sensnology.spotnav.ui.site

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.Plan
import se.sensnology.spotnav.ha.house.PlanRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The Plan box's words and drawing, as the card's Plan tab says them (`house-plan.ts`). */
class HousePlanModelTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun plan(name: String = "get_plan_success.json", edit: (JSONObject) -> Unit = {}): Plan =
        (PlanRead.decode(HaFixtures.json("webhook/$name").also(edit)) as SiteRead.Read).value

    /** A battery and a watched water heater beside the car, along the fixture's hours. */
    private fun busy(json: JSONObject) {
        val n = json.getJSONArray("hours").length()
        fun series(vararg values: Double) = JSONArray().apply { for (i in 0 until n) put(values.getOrElse(i) { 0.0 }) }
        val hours = json.getJSONArray("hours")
        fun at(i: Int) = hours.getJSONObject(i).getString("start")
        json.getJSONArray("loads").put(
            JSONObject().put("id", "battery").put("kind", "home_battery").put("name", "Sigen").put("state", "live").put("in_house", false)
                .put("kw", series(2.0, -1.5)).put("level", JSONObject.NULL)
                .put("runs", JSONArray()
                    .put(JSONObject().put("start", at(0)).put("end", at(1)).put("what", "grid_charge").put("reason", "cheap_before_dear").put("kw", 2.0).put("kwh", 2.0))
                    .put(JSONObject().put("start", at(1)).put("end", at(2)).put("what", "cover").put("reason", JSONObject.NULL).put("kw", -1.5).put("kwh", 1.5)))
                .put("summary", JSONObject().put("charge_kwh", 2.0).put("grid_charge_kwh", 2.0).put("cover_kwh", 1.5))
                .put("does_not_fit", JSONObject.NULL)
        )
        json.getJSONArray("loads").put(
            JSONObject().put("id", "switch.vvb").put("kind", "water_heater").put("name", JSONObject.NULL).put("state", "would").put("in_house", true)
                .put("kw", series(0.0, 0.0, 3.0)).put("level", JSONObject.NULL)
                .put("runs", JSONArray()
                    .put(JSONObject().put("start", at(2)).put("end", at(3)).put("what", "on").put("reason", "cheap").put("kw", 3.0).put("kwh", 3.0))
                    .put(JSONObject().put("start", at(3)).put("end", at(4)).put("what", "paused").put("reason", "ceiling").put("kw", JSONObject.NULL).put("kwh", JSONObject.NULL)))
                .put("summary", JSONObject().put("on_hours", 1).put("kw", 3.0))
                .put("does_not_fit", JSONObject.NULL)
        )
        json.put("would", true)
        hours.getJSONObject(2).put("house_kw", 4.0).put("net_kw", 4.5)
    }

    @Test fun theSummaryAndTheHeadings() {
        val c = HousePlanModel.content(text(), plan())
        assertEquals("Plan för de närmaste 36 timmarna", c.title)
        assertEquals(listOf("Beräknad kostnad", "Högsta timme från nätet", "Månadens topp hittills"), c.stats.map { it.label })
        assertEquals("–", c.stats[0].value)
        assertEquals("från nätet, de närmaste 36 timmarna", c.stats[0].note)
        assertEquals("4,0 kW", c.stats[2].value)
        assertTrue(c.stats[2].note, c.stats[2].note.endsWith("08:00 · upp till den är gratis"))
        assertNull("the prices are known", c.noPrices)
        assertNull(c.wouldNote)
        assertEquals("Per last", c.perLoad)
        assertEquals("I ord", c.inWords)
        assertNull(c.nothing)
        // Without a month's peak: the power tariff's line instead.
        val none = HousePlanModel.content(text(), plan { it.put("month_peak", JSONObject.NULL) })
        assertEquals(listOf("Effekttaxa", "–", "ingen i elpriset"), none.stats[2].let { listOf(it.label, it.value, it.note) })
        // A cost in the minor unit is said in the major one; the highest hour against the ceiling.
        val costed = HousePlanModel.content(text(), plan {
            it.getJSONObject("summary").put("cost", 12345).put("peak_kw", 5.1).put("peak_start", "2026-09-22T16:00:00+00:00")
                .put("peak_ceiling_kw", 3.97).put("peak_ceiling", "power_tariff").put("over", true)
        })
        assertEquals("123 kr", costed.stats[0].value)
        assertEquals(listOf("5,1 kW", "kl. 18:00 · över taket"), listOf(costed.stats[1].value, costed.stats[1].note))
        assertEquals(true, costed.stats[1].over)
    }

    @Test fun theCarsLaneAndWords() {
        val c = HousePlanModel.content(text(), plan())
        val car = c.lanes.single()
        assertEquals("entry_a", car.name)
        assertEquals("20,8 kWh, 12–16", car.reason)
        assertEquals((3..6).toList(), car.blocks.map { it.index })
        assertTrue(car.blocks.all { it.layer == PlanLayer.CAR && !it.would })
        assertNull(car.doesNotFit)
        assertEquals(listOf(HousePlanModel.Said("12–16", "entry_a laddar 20,8 kWh med 5,2 kW", false, "car")), c.words)
        assertTrue(c.rolling!!, c.rolling!!.startsWith("09:00 · 18:00 · 03:00 · 12:00 → ett rullande dygn från nu"))
    }

    @Test fun theDoesNotFitLineIsSaidInTheCarsLane() {
        val lane = HousePlanModel.content(text(), plan("get_plan_does_not_fit.json")).lanes.single()
        assertEquals("Ryms inte under taket (Effektmål 6,0 kW): 16,2 kWh till 14:30 – 7,1 kWh hinner.", lane.doesNotFit)
        val chosen = HousePlanModel.content(text(), plan("get_plan_takes_what_fits.json")).lanes.single()
        assertEquals("Bilen tar det som ryms till 14:30 (6,9 kWh).", chosen.doesNotFit)
    }

    @Test fun theChartStacksTheLoadsAndLabelsTheCeiling() {
        val chart = HousePlanModel.content(text(), plan(edit = ::busy)).chart
        assertEquals(36, chart.hours.size)
        // The battery charges above zero, then covers below it; the watched water heater is in the house's figure.
        assertEquals(listOf(PlanLayer.HOUSE, PlanLayer.BATTERY_IN), chart.hours[0].above.map { it.layer })
        assertEquals(listOf(PlanLayer.BATTERY_OUT), chart.hours[1].below.map { it.layer })
        val third = chart.hours[2]
        assertEquals(listOf(PlanLayer.HOUSE, PlanLayer.WATER_HEATER), third.above.map { it.layer })
        assertEquals(1.0, third.above[0].kw, 1e-9)
        assertTrue("watched: faded", third.above[1].would)
        assertTrue("4,5 kW over 3,97 kW", third.over)
        assertEquals("Effekttak 4,0 kW", chart.ceilingLabel)
        assertEquals("09", chart.hours[0].tick)
        assertNull(chart.hours[1].tick)
        assertEquals(chart.hours.indexOfFirst { it.dayCue != null }, 15)
        assertTrue(chart.maxKw >= 4.5)
        assertTrue(chart.minKw <= -1.5)
        assertEquals(setOf(PriceTone.CHEAP, PriceTone.MID, PriceTone.EXPENSIVE), chart.hours.mapNotNull { it.tone }.toSet())
    }

    @Test fun aWatchedLoadSaysWould() {
        val c = HousePlanModel.content(text(), plan(edit = ::busy))
        assertEquals("Blekt: vad SpotNav skulle göra med en last som den bara visar. Den lasten styrs inte nu.", c.wouldNote)
        val heater = c.lanes.first { it.kind == "water_heater" }
        assertEquals("Beredare", heater.name)
        assertTrue(heater.would)
        assertEquals("Skulle: Får värmas 1 h, billiga timmar", heater.reason)
        val battery = c.lanes.first { it.kind == "home_battery" }
        assertEquals("Husbatteri", battery.name)
        assertEquals("Täcker 1,5 kWh · nätladdning av husbatteriet 2,0 kWh", battery.reason)
        assertEquals(listOf(PlanLayer.BATTERY_IN, PlanLayer.BATTERY_OUT), battery.blocks.map { it.layer })
        val said = c.words.map { it.sentence to it.would }
        assertTrue(said.toString(), ("Nätladdning av husbatteriet, 2,0 kW: billigt före dyra timmar" to false) in said)
        assertTrue(said.toString(), ("Beredaren värms: billig timme" to true) in said)
        assertTrue(said.toString(), ("Beredaren pausad: för taket" to true) in said)
        assertEquals("skulle", c.wouldTag)
        val legend = c.legend.map { it.second }
        assertEquals(listOf("Hus", "Beredare", "Bil", "Husbatteri laddar", "Husbatteri täcker", "Från nätet"), legend)
    }

    @Test fun nothingPlannedAndNoPrices() {
        val c = HousePlanModel.content(text(), plan {
            it.put("loads", JSONArray())
            val hours = it.getJSONArray("hours")
            for (i in 0 until hours.length()) hours.getJSONObject(i).put("price", JSONObject.NULL)
        })
        assertEquals("Inget planerat de närmaste timmarna.", c.nothing)
        assertEquals("Priserna för de här timmarna är inte kända ännu.", c.noPrices)
        assertNull(c.perLoad)
        assertTrue(c.lanes.isEmpty())
        assertTrue(c.chart.hours.all { it.tone == null })
    }

    @Test fun aLaterKindIsDrawnByItsRuns() {
        val c = HousePlanModel.content(text("en"), plan {
            val car = it.getJSONArray("loads").getJSONObject(0)
            it.getJSONArray("loads").put(JSONObject(car.toString()).put("kind", "pool_pump").put("name", "Pool"))
        })
        val pool = c.lanes.last()
        assertEquals("Pool", pool.name)
        assertEquals("Pool", pool.reason)
        assertEquals(PlanLayer.HOUSE, pool.blocks.first().layer)
    }

    @Test fun theBoxIsLeftOutWhereThePlanIsNotServed() {
        val p = plan()
        assertEquals(PlanBoxState.Hidden, PlanBoxState.of(false, p, false, false))
        assertEquals(PlanBoxState.Hidden, PlanBoxState.of(null, null, false, false))
        assertEquals(PlanBoxState.Hidden, PlanBoxState.of(null, null, true, false))
        assertEquals(PlanBoxState.Line("house.loading"), PlanBoxState.of(true, null, false, false))
        assertEquals(PlanBoxState.Line("house.failed"), PlanBoxState.of(true, null, true, false))
        assertEquals(PlanBoxState.Line("hist.unsupported"), PlanBoxState.of(null, null, false, true))
        assertEquals(PlanBoxState.Shown(p), PlanBoxState.of(true, p, true, false))
    }

    @Test fun everyWordIsInEveryLanguage() {
        val shapes = listOf(plan(edit = ::busy), plan("get_plan_does_not_fit.json"), plan("get_plan_takes_what_fits.json"))
        for (language in CardStringsXml.LANGUAGES) {
            for (shape in shapes) {
                val c = HousePlanModel.content(text(language), shape)
                val said = listOf(c.title, c.lede, c.fromGrid, c.inWords, c.wouldTag, c.chart.priceStrip, c.chart.description) +
                    listOfNotNull(c.noPrices, c.wouldNote, c.perLoad, c.rolling, c.nothing, c.chart.ceilingLabel) +
                    c.stats.flatMap { listOf(it.label, it.note) } + c.legend.map { it.second } +
                    c.lanes.flatMap { listOfNotNull(it.name, it.reason, it.doesNotFit) } + c.words.map { it.sentence }
                for (line in said) assertFalse("$language: $line", line.contains("{") || line.contains("house.plan.") || line.contains("doesNotFit."))
            }
        }
    }
}
