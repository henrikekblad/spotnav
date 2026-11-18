package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.time.LocalDate

/** Which boxes the heat page has under its Now box, and what its shared picker offers. */
class HeatBoxesTest {
    private val now = Instant.parse("2026-11-16T16:15:00Z").toEpochMilli()

    private fun heat(name: String = "villa.json", edit: (org.json.JSONObject) -> Unit = {}): Heat {
        val json = HaFixtures.json("heat_card/$name")
        edit(json)
        return (HeatRead.decode(json) as SiteRead.Read).value
    }

    private fun noMeter() = heat { it.put("meter", org.json.JSONObject.NULL) }

    @Test fun beforeTheHeatingHasAnsweredThereIsNoBoxAndNoPicker() {
        assertFalse(HeatBoxes.use(null))
        assertFalse(HeatBoxes.plan(null, now))
        assertFalse(HeatBoxes.details(null))
        assertEquals(emptyList<HistoryPeriod>(), HeatBoxes.pickerKinds(null, now))
    }

    @Test fun aVillaWithAMeterHasBothBoxesAndTheWholePicker() {
        val villa = heat()
        assertTrue(HeatBoxes.use(villa))
        assertTrue(HeatBoxes.plan(villa, now))
        assertTrue(HeatBoxes.details(villa))
        assertEquals(HistoryPeriod.entries, HeatBoxes.pickerKinds(villa, now))
    }

    @Test fun withoutAMeterThereIsNoUseBoxAndThePickerOffersDaysAlone() {
        val h = noMeter()
        assertFalse(HeatBoxes.use(h))
        assertEquals(listOf(HistoryPeriod.DAY), HeatBoxes.pickerKinds(h, now))
        // A history is read for a past day's plan only.
        assertFalse(HeatBoxes.needsHistory(h, PeriodChoice(HistoryPeriod.DAY), now))
        assertTrue(HeatBoxes.needsHistory(h, PeriodChoice(HistoryPeriod.DAY, LocalDate.parse("2026-11-15")), now))
    }

    @Test fun aSiteWithNothingToDrawHasNoBoxAtAll() {
        val empty = heat {
            it.put("meter", org.json.JSONObject.NULL)
            it.put("indoor_day", org.json.JSONObject.NULL)
            it.getJSONObject("band").put("hours", org.json.JSONArray())
        }
        assertFalse(HeatBoxes.plan(empty, now))
        assertEquals(emptyList<HistoryPeriod>(), HeatBoxes.pickerKinds(empty, now))
        assertFalse(HeatBoxes.needsHistory(empty, PeriodChoice(HistoryPeriod.MONTH), now))
    }

    @Test fun aMeterNeedsTheHistoryOfEveryPeriod() {
        val villa = heat()
        for (period in HistoryPeriod.entries) assertTrue(HeatBoxes.needsHistory(villa, PeriodChoice(period), now))
    }
}
