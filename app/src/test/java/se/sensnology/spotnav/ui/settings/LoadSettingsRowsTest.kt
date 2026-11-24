package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.site.HeatLoadSettings
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ha.site.HeatSettingsRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The Pool, Spa and Bastu settings sections, read only, from `get_heat`'s `config` as the card lists them. */
class LoadSettingsRowsTest {
    private fun read(path: String): HeatSettings = (HeatSettingsRead.decode(HaFixtures.json(path)) as SiteRead.Read).value

    private val sv = LoadSettingsRows(CardStringsXml.words("sv"), Locale.forLanguageTag("sv"))

    @Test fun theStoredBlocksDecode() {
        val settings = read("heat_card/controlled.json")
        assertEquals(
            HeatLoadSettings.Pool("climate.poolpump", "switch.pool_cirkulation", "sensor.pool_vatten", null, 1.6, null, controlHeatPump = false, controlCirculation = true),
            settings.pool
        )
        assertEquals(HeatLoadSettings.Spa("climate.spa", "balboa", 30.0, 3.0, false), settings.spa)
        assertEquals(HeatLoadSettings.Sauna("switch.bastu", "relay", 9.0, safetyDeclared = false, control = false), settings.sauna)
        assertTrue(settings.spaStatus != null && settings.poolStatus != null)
        // The webhook's answer of a site without them: no sections.
        val plain = read("webhook/get_heat_success.json")
        assertNull(plain.pool)
        assertEquals(emptyList<LoadSettingsRows.Section>(), sv.sections(plain))
    }

    @Test fun eachSetUpLoadHasTheCardsRows() {
        val sections = sv.sections(read("heat_card/controlled.json")).associateBy { it.kind }
        assertEquals(listOf("pool", "spa", "sauna"), sections.keys.toList())
        val pool = sections.getValue("pool").rows.associate { it.label to it.value }
        assertEquals("Efter vattentemperaturen", pool["Filtrering per dygn"])
        assertEquals("1,6 kW, angiven", pool["Poolvärmepumpens effekt"])
        assertEquals("På", pool["Styr cirkulationen"])
        assertEquals("Av", pool["Styr poolens värmepump"])
        val spa = sections.getValue("spa").rows.associate { it.label to it.value }
        assertEquals("30,0 °C", spa["Mellan baden"])
        assertEquals("3,0 kW, angiven", spa["Spavärmarens effekt"])
        assertEquals("Av", spa["Styr spat"])
        val sauna = sections.getValue("sauna").rows.associate { it.label to it.value }
        // A relay sauna without its declared safety measure: the declaration, and no control row.
        assertEquals("Nej, eller vet inte", sauna["Aggregatets eget säkerhetsskydd"])
        assertNull(sauna["Styr bastun"])
    }

    @Test fun thePowerIsEnteredLearnedOrTypical() {
        assertEquals("2,0 kW, angiven", sv.powerWords(2.0, 3.0, "learned"))
        assertEquals("3,0 kW, inlärd", sv.powerWords(null, 3.0, "learned"))
        assertEquals("3,0 kW, vanligt värde tills den är inlärd", sv.powerWords(null, 3.0, "typical"))
        assertEquals("Inte angiven", sv.powerWords(null, null, null))
    }
}
