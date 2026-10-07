package se.sensnology.spotnav.ui.settings

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * One price value changed at a time: the whole paired form (the record's other values as they stand)
 * with that one field changed, or this phone's own settings with that one field changed.
 */
class PriceEditsTest {
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private fun market(id: String) = catalogue.firstOrNull { it.id == id }

    private val record = SettingsFixtures.parsed(
        areaId = "SE4",
        overrides = JSONArray().put(
            SettingsFixtures.override(
                areaId = "SE4",
                vat = SettingsFixtures.fiscal(true),
                tax = SettingsFixtures.fiscal(true, 36.0),
                transfer = SettingsFixtures.fiscal(false)
            )
        )
    )

    @Test fun aPairedEditKeepsEveryOtherValueOfTheRecord() {
        val tax = PriceEdits.paired(record, PriceEdit.Tax(on = true, figure = 40.0))
        assertEquals("SE4", tax.areaId)
        assertTrue(tax.vat)
        assertEquals(40.0, tax.taxFigure!!, 0.0)
        assertFalse(tax.transfer)
        val vat = PriceEdits.paired(record, PriceEdit.Vat(false))
        assertFalse(vat.vat)
        assertEquals(36.0, vat.taxFigure!!, 0.0)
        val fee = PriceEdits.paired(record, PriceEdit.Transfer(on = true, figure = 25.0))
        assertTrue(fee.transfer)
        assertEquals(25.0, fee.transferFigure!!, 0.0)
        assertEquals(36.0, fee.taxFigure!!, 0.0)
    }

    @Test fun aPairedAreaChangeTakesTheRecordsOwnValuesForTheNewArea() {
        val area = PriceEdits.paired(record, PriceEdit.Area("NO1"))
        assertEquals("NO1", area.areaId)
        // The record has no override for NO1: every add-on reads off there.
        assertFalse(area.vat)
        assertFalse(area.tax)
    }

    @Test fun aLocalEditChangesOnlyItsOwnField() {
        val settings = WidgetSettings(area = "SE4", vat = true, tax = true, taxMinorUnit = 36.0, transfer = false, gridFeeMinorUnit = 30.0)
        assertEquals(settings.copy(vat = false), PriceEdits.local(settings, PriceEdit.Vat(false), ::market))
        assertEquals(settings.copy(tax = false), PriceEdits.local(settings, PriceEdit.Tax(on = false, figure = null), ::market))
        assertEquals(settings.copy(transfer = true, gridFeeMinorUnit = 25.0),
            PriceEdits.local(settings, PriceEdit.Transfer(on = true, figure = 25.0), ::market))
    }

    @Test fun aLocalAreaChangeTakesTheNewAreasSuggestedFiguresAsAPickDid() {
        val settings = WidgetSettings(area = "SE4", tax = true, taxMinorUnit = 36.0, gridFeeMinorUnit = 30.0)
        val norway = PriceEdits.local(settings, PriceEdit.Area("NO1"), ::market)
        assertEquals("NO1", norway.area)
        assertEquals(7.13, norway.taxMinorUnit, 0.0)
        // NO1 suggests no grid fee: the stored one is kept.
        assertEquals(30.0, norway.gridFeeMinorUnit, 0.0)
    }
}
