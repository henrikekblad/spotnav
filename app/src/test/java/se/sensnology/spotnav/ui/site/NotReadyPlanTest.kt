package se.sensnology.spotnav.ui.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.NotReady
import se.sensnology.spotnav.ha.house.Plan
import se.sensnology.spotnav.ha.house.PlanRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/** `get_plan`'s top-level `not_ready` and the pool's, the spa's and the sauna's lanes, against the integration's plan documents. */
class NotReadyPlanTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun json(name: String): JSONObject = HaFixtures.json("plan/$name")

    private fun plan(name: String, edit: (JSONObject) -> Unit = {}): Plan = (PlanRead.decode(json(name).also(edit)) as SiteRead.Read).value

    private fun sheet(plan: Plan, language: String = "sv") = NotReadyText(text(language), plan.zone, plan.money)

    @Test fun everyPlanDocumentIsListedAndRead() {
        val expected = mapOf(
            "car_only.json" to false, "not_ready.json" to true, "past_day.json" to false, "pool.json" to false,
            "spa_sauna.json" to false, "today_mixed.json" to false, "villa.json" to false
        )
        assertEquals(expected.keys, HaFixtures.files("plan").map { it.name }.toSet())
        for ((name, notReady) in expected) assertEquals(name, notReady, plan(name).notReady != null)
    }

    @Test fun theBlockHasTheCarAndTheBathRankedByWhatTheyFree() {
        val block = plan("not_ready.json").notReady!!
        assertEquals(listOf("charger_garage" to "car", "spa" to "spa"), block.items.map { it.loadId to it.kind })
        val spa = block.items[1]
        assertEquals(35.0, spa.reachC!!, 0.0)
        assertEquals("import_target", spa.cause)
        assertEquals(Instant.parse("2026-11-17T19:00:00Z"), spa.choices.first { it.code == NotReady.LATER }.later)
        assertEquals(listOf(null, "spa", "spa", "charger_garage", "charger_garage"), block.ranked.map { it.loadId })
        assertTrue(block.asks)
    }

    @Test fun theSheetSaysEachLoadAndEveryChoiceTheMostFreedFirst() {
        val p = plan("not_ready.json")
        val words = sheet(p)
        val block = p.notReady!!
        assertEquals("Blir inte klart i tid", words.title())
        assertEquals(listOf("Garage 07:00: 3,0 kWh saknas", "Spa 19:00: når 35 °C"), block.items.map(words::line))
        val buttons = words.buttons(block)
        assertEquals(
            listOf(
                "Tillåt toppen för alla",
                "Spa: Senare badtid (20:00)",
                "Spa: Ta det som ryms (35 °C)",
                "Garage: Senare avresa (08:00)",
                "Garage: Ta det som ryms (≈ 14,8 kWh)"
            ),
            buttons.map { it.label }
        )
        assertEquals(listOf(NotReadyText.Load("charger_garage", "car"), NotReadyText.Load("spa", "spa")), buttons[0].press.loads)
        // The notice carries the first three.
        assertEquals(buttons.take(3), words.buttons(block, limit = 3))
    }

    @Test fun eachPressGoesToItsLoadsOwnAction() {
        val car = NotReadyText.Load("charger_garage", "car")
        assertEquals("release_import_target", NotReadyText.action(NotReady.ACCEPT_PEAK, car))
        assertEquals("take_what_fits", NotReadyText.action(NotReady.TAKES_WHAT_FITS, car))
        assertEquals("later_departure", NotReadyText.action(NotReady.LATER, car))
        assertEquals("later_bath", NotReadyText.action(NotReady.LATER, NotReadyText.Load("spa", "spa")))
        assertEquals("later_session", NotReadyText.action(NotReady.LATER, NotReadyText.Load("sauna", "sauna")))
        assertEquals("accept_peak", NotReadyText.action(NotReady.ACCEPT_PEAK, NotReadyText.Load("sauna", "sauna")))
        assertEquals("take_what_fits", NotReadyText.action(NotReady.TAKES_WHAT_FITS, NotReadyText.Load("spa", "spa")))
    }

    @Test fun oneLoadLeftIsNamedByItsChoiceAloneAndAChosenOneAsksNoMore() {
        val p = plan("not_ready.json") { json ->
            json.getJSONObject("not_ready").getJSONArray("items").getJSONObject(0).put("chosen", "takes_what_fits")
        }
        val words = sheet(p)
        val block = p.notReady!!
        assertEquals("Garage 07:00: tar det som ryms", words.line(block.items[0]))
        assertEquals(listOf("Tillåt toppen", "Senare badtid (20:00)", "Ta det som ryms (35 °C)"), words.buttons(block).map { it.label })
        assertEquals(listOf(NotReadyText.Load("spa", "spa")), words.buttons(block)[0].press.loads)
    }

    @Test fun aBlockOfAKindTheAppDoesNotKnowLeavesThePlanWithoutTheSheet() {
        val p = plan("not_ready.json") { json ->
            json.getJSONObject("not_ready").getJSONArray("items").getJSONObject(1).put("kind", "hot_tub")
        }
        assertNull(p.notReady)
        assertFalse(p.hours.isEmpty())
        val english = sheet(plan("not_ready.json"), "en")
        assertEquals("Not ready in time", english.title())
    }

    @Test fun thePoolsAndTheSpasLanesSayWhatTheyWouldDo() {
        val pool = plan("pool.json")
        val t = text()
        val circulation = pool.loads.first { it.kind == "pool_circulation" }
        assertEquals("Skulle: Filtrerar 6 h av 5 h per dygn", HousePlanModel.laneReason(t, pool, circulation))
        assertEquals("Skulle: Värmer 2 h", HousePlanModel.laneReason(t, pool, pool.loads.first { it.kind == "pool_heat" }))
        val spa = plan("spa_sauna.json")
        assertEquals("Skulle: 38 °C kl. 07:30", HousePlanModel.laneReason(t, spa, spa.loads.first { it.kind == "spa" }))
        assertEquals("Skulle: Inget planerat", HousePlanModel.laneReason(t, spa, spa.loads.first { it.kind == "sauna" }))
        val run = spa.loads.first { it.kind == "spa" }.let { load -> HousePlanModel.runWords(t, load, load.runs.first()) }
        assertTrue(run, run.startsWith("Spat värms upp"))
    }
}
