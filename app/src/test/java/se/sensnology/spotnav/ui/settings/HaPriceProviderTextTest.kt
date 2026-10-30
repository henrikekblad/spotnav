package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.dashboard.DashboardPriceProvider
import java.io.File

/**
 * A paired phone's source line and Home Assistant's notice (design 5.4): every key and code the card has words
 * for has the card's words here, in every language, and nothing else is said.
 */
class HaPriceProviderTextTest {
    private fun provider(key: String?, code: String? = null, params: Map<String, String> = emptyMap()) =
        DashboardPriceProvider(key, null, null, code?.let { DashboardPriceProvider.Notice(it, params) })

    @Test fun eachSourceHasItsLine() {
        assertEquals(R.string.ha_price_provider_nordpool, HaPriceProviderText.line(provider("nordpool")))
        assertEquals(R.string.ha_price_provider_entsoe, HaPriceProviderText.line(provider("entsoe")))
        assertEquals(R.string.ha_price_provider_pvpc, HaPriceProviderText.line(provider("pvpc")))
        assertEquals(R.string.ha_price_provider_octopus, HaPriceProviderText.line(provider("octopus")))
        assertEquals(R.string.ha_price_provider_relay, HaPriceProviderText.line(provider("relay")))
        assertNull("no source at all", HaPriceProviderText.line(provider(null)))
        assertNull("a source this app has no words for", HaPriceProviderText.line(provider("tibber")))
        assertNull(HaPriceProviderText.line(null))
    }

    @Test fun eachNoticeHasItsSentenceWithTheArea() {
        val codes = listOf(
            "add_nordpool", "nordpool_not_loaded", "add_entsoe_key", "entsoe_key_rejected", "add_pvpc", "pvpc_not_loaded",
            "add_octopus", "octopus_not_loaded", "octopus_no_agile", "octopus_region_mismatch", "source_missing"
        )
        val texts = codes.map { code ->
            val sentence = HaPriceProviderText.notice(provider("relay", code), "SE4")
            assertNotNull(code, sentence)
            sentence!!.text
        }
        assertEquals("one sentence per code", codes.size, texts.toSet().size)
        assertEquals(
            HaPriceProviderText.Sentence(R.string.ha_price_notice_add_nordpool, listOf("SE4 – Malmö")),
            HaPriceProviderText.notice(provider("relay", "add_nordpool"), "SE4 – Malmö")
        )
        assertEquals(
            HaPriceProviderText.Sentence(R.string.ha_price_notice_octopus_region_mismatch, listOf("GB-C", "GB-A")),
            HaPriceProviderText.notice(provider("octopus", "octopus_region_mismatch", mapOf("region" to "GB-C")), "GB-A")
        )
    }

    @Test fun noNoticeOrOneWithoutWordsSaysNothing() {
        assertNull(HaPriceProviderText.notice(provider("nordpool"), "SE4"))
        assertNull(HaPriceProviderText.notice(provider("relay", "relay_ending"), "SE4"))
        assertNull(HaPriceProviderText.notice(null, "SE4"))
    }

    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(xml: String, name: String): String? = Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml)?.groupValues?.get(1)

    private val languages = listOf("values", "values-sv", "values-da", "values-nb", "values-fi", "values-de", "values-nl", "values-es", "values-fr")

    private val names = listOf(
        "ha_price_provider_nordpool", "ha_price_provider_entsoe", "ha_price_provider_pvpc", "ha_price_provider_octopus",
        "ha_price_provider_relay", "ha_price_notice_add_nordpool", "ha_price_notice_nordpool_not_loaded",
        "ha_price_notice_add_entsoe_key", "ha_price_notice_entsoe_key_rejected", "ha_price_notice_add_pvpc",
        "ha_price_notice_pvpc_not_loaded", "ha_price_notice_add_octopus", "ha_price_notice_octopus_not_loaded",
        "ha_price_notice_octopus_no_agile", "ha_price_notice_octopus_region_mismatch", "ha_price_notice_source_missing",
        "ha_price_open_home_assistant"
    )

    private val PLACEHOLDER = Regex("%[0-9][$]s")

    @Test fun everyWordIsTranslatedWithTheSamePlaceholders() {
        val english = xml("values")
        for (language in languages) {
            val body = xml(language)
            for (name in names) {
                val said = text(body, name)
                assertNotNull("$language $name", said)
                assertTrue("$language $name", said!!.isNotBlank())
                assertEquals(
                    "$language $name",
                    PLACEHOLDER.findAll(text(english, name)!!).map { it.value }.toSet(),
                    PLACEHOLDER.findAll(said).map { it.value }.toSet()
                )
            }
        }
    }

    /** The card's own Swedish (`frontend/src/i18n/sv.json`), and the app's own two key sentences and its button. */
    @Test fun theSwedishIsTheCards() {
        val sv = xml("values-sv")
        val expected = mapOf(
            "ha_price_provider_nordpool" to "Priser från Nord Pool (via Home Assistant)",
            "ha_price_provider_entsoe" to "Priser från ENTSO-E (din nyckel)",
            "ha_price_provider_relay" to "Priser från SpotNav-reläet",
            "ha_price_notice_add_nordpool" to "Hämta priserna för %1\$s via Home Assistants inbyggda Nord Pool-integration. " +
                "Vilket område du väljer när du lägger till den spelar ingen roll – SpotNav hämtar själv det område som behövs.",
            "ha_price_notice_nordpool_not_loaded" to "Nord Pool är tillagd i Home Assistant men startade inte.",
            "ha_price_notice_source_missing" to "Det finns ingen källa för priser i %1\$s. Välj ett annat område.",
            "ha_price_notice_add_entsoe_key" to
                "Priserna för %1\$s kommer från ENTSO-E med din egen kostnadsfria nyckel. Lägg till den i Home Assistant.",
            "ha_price_notice_entsoe_key_rejected" to "ENTSO-E godtog inte den sparade nyckeln. Ange en ny i Home Assistant.",
            "ha_price_open_home_assistant" to "Öppna Home Assistant"
        )
        for ((name, words) in expected) assertEquals(name, words, text(sv, name))
    }
}
