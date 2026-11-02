package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.PlanPreview
import se.sensnology.spotnav.ha.dashboard.DashboardStatus
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.ha.dashboard.StatusLine
import se.sensnology.spotnav.ha.dashboard.StatusTone
import java.time.Instant
import java.time.ZoneId

class PlanPreviewTextTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = Instant.parse("2026-09-22T06:00:00Z")
    private fun format(language: String) = StatusFormat(language, zone, "SEK", "kr")
    private fun at(text: String) = Instant.parse(text)

    private fun plan(finish: String, vararg periods: Pair<String, String>) = PlanPreview.Plan(
        periods = periods.map { PlanPreview.Period(at(it.first), at(it.second)) },
        plannedKwh = 20.0, powerKw = 6.9, cost = 56.65, currency = "SEK", finish = at(finish), shortOfDeadline = false
    )

    private fun preview(plan: PlanPreview.Plan?, lines: List<StatusLine> = emptyList(), tone: StatusTone = StatusTone.NORMAL) =
        PlanPreview.Preview("proposal_ready", plan, DashboardStatus(lines, tone), null)

    @Test fun thePlanLineNamesTheWindowsTheEnergyTheCostAndWhenItIsReady() {
        val text = PlanPreviewText.lines(
            preview(plan("2026-09-22T22:48:00Z", "2026-09-22T21:00:00Z" to "2026-09-22T22:48:00Z")), format("sv"), now
        )
        // Crossing midnight names the new day once, as the card does ("23:15–mån 2 00:30").
        assertEquals("23:00–ons 23 00:48 · 20 kWh · 56,65 kr · klar 00:48", text.plan)
    }

    @Test fun aLaterDayIsNamedOnceAndMorePeriodsAreCounted() {
        val text = PlanPreviewText.lines(
            preview(
                plan(
                    "2026-09-23T03:00:00Z",
                    "2026-09-22T09:00:00Z" to "2026-09-22T10:00:00Z",
                    "2026-09-23T01:00:00Z" to "2026-09-23T02:00:00Z",
                    "2026-09-23T02:30:00Z" to "2026-09-23T03:00:00Z"
                )
            ),
            format("en"), now
        )
        assertTrue(text.plan!!, text.plan!!.startsWith("11:00–12:00, Wed 23 03:00–04:00 +1 · "))
        assertTrue(text.plan!!, text.plan!!.endsWith("ready 05:00"))
    }

    @Test fun noPlanSaysWhyThroughTheStatusLinesAndLeavesTheStripOut() {
        val text = PlanPreviewText.lines(
            preview(null, listOf(StatusLine("waiting_for_publication", emptyMap())), StatusTone.NOTICE), format("en"), now
        )
        assertNull(text.plan)
        assertEquals(1, text.notes.size)
        assertTrue(text.notes.single().warning)
    }

    @Test fun readyIsLeftOutWhenTheShortfallLineAlreadyNamesThatTime() {
        val shortfall = StatusLine(
            "departure_shortfall", mapOf("departure" to "2026-09-22T22:48:00+00:00", "kwh" to 18.0, "requested" to 20.0)
        )
        val text = PlanPreviewText.lines(
            preview(plan("2026-09-22T22:48:00Z", "2026-09-22T21:00:00Z" to "2026-09-22T22:48:00Z"), listOf(shortfall), StatusTone.NOTICE),
            format("en"), now
        )
        assertTrue(text.plan!!, !text.plan!!.contains("ready"))
    }

    @Test fun theStripMarksPlannedQuartersAndColoursEachAgainstItsOwnDaysMean() {
        val strip = PlanPreview.Strip(
            at("2026-09-22T06:00:00Z"), at("2026-09-22T07:00:00Z"),
            listOf(
                PlanPreview.Quarter(at("2026-09-22T06:00:00Z"), 10.0, true),
                PlanPreview.Quarter(at("2026-09-22T06:15:00Z"), 30.0, false),
                PlanPreview.Quarter(at("2026-09-22T06:30:00Z"), 10.0, false),
                PlanPreview.Quarter(at("2026-09-22T06:45:00Z"), 30.0, false)
            )
        )
        val model = PlanPreviewText.strip(strip, zone)!!
        assertEquals(listOf(true, false, false, false), model.cells.map { it.planned })
        assertEquals(listOf(true, false, true, false), model.cells.map { it.cheap })
        assertEquals(0.25f, model.cells[1].left, 0.0001f)
        assertEquals("08:00", model.startLabel)
        assertEquals("09:00", model.endLabel)
        assertNull(PlanPreviewText.strip(strip, null))
    }
}
