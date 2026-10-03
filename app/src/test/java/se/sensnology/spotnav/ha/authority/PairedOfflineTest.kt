package se.sensnology.spotnav.ha.authority

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.ConfirmedChartRules
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.io.File

/** A paired screen that cannot reach Home Assistant: */
class PairedOfflineTest {
    private val catalogue = listOf(RelayFixtures.se4)
    private val presentation = HaPresentation.QUARTER_HOUR
    private val written = SettingsFixtures.parsed(revision = 6, areaId = "SE4", amps = 16, requestedKwh = 20.5)
    private val autoRecord = SettingsFixtures.parsed(
        revision = 7, areaId = "SE4", amps = 16, requestedKwh = 20.5
    )
    private val localInputs = LocalPlanningInputs.of(WidgetSettings(area = "SE4", chargingAmps = 10, chargingKwh = 12.0))

    // Who may be touched

    @Test
    fun anOfflineRecordOffersNoPairedWriteAndSaysSo() {
        val offline = VisibleAuthority.ReadOnlyOffline(written)
        // The one predicate every control asks: not editable, with or without a confirmed record.
        assertFalse("a cached record nothing can check is read-only", offline.pairedControlsEnabled)
        assertFalse("and so is one with nothing cached at all", VisibleAuthority.ReadOnlyOffline(null).pairedControlsEnabled)

        // The sentence is the state's own, localized in all five locales, and it names Home
        // Assistant rather than a bare "offline".
        for (locale in listOf("values", "values-sv", "values-nb", "values-da", "values-fi")) {
            val xml = read("src/main/res/$locale/strings.xml")
            val withRecord = valueOf(xml, "authority_offline")
            val without = valueOf(xml, "authority_offline_none")
            assertTrue("$locale names Home Assistant", withRecord.contains("Home Assistant"))
            assertTrue("$locale names the revision", withRecord.contains("%1${'$'}d"))
            assertTrue("$locale names Home Assistant", without.contains("Home Assistant"))
        }
        // English, read as the words: the two sentences say what the screen means.
        val english = read("src/main/res/values/strings.xml")
        assertTrue(valueOf(english, "authority_offline").contains("read-only"))
        assertTrue(valueOf(english, "authority_offline_none").contains("no price graph is drawn"))
    }

    @Test
    fun everyPairedStateAnswersTheSameQuestionOnce() {
        // The predicate is a table, so an offline or incomplete state cannot each be remembered
        // differently by a different control.
        assertFalse("offline, with a record", VisibleAuthority.ReadOnlyOffline(written).pairedControlsEnabled)
        assertFalse("offline, without one", VisibleAuthority.ReadOnlyOffline(null).pairedControlsEnabled)
        assertFalse(
            "an incomplete record with nothing confirmed",
            VisibleAuthority.Incomplete(listOf(HaPlanningInputs.Reason.FISCAL_VAT), lastConfirmed = null).pairedControlsEnabled
        )
        assertTrue(
            "an incomplete record whose own values are known is editable -- they are what is missing",
            VisibleAuthority.Incomplete(listOf(HaPlanningInputs.Reason.FISCAL_VAT), written).pairedControlsEnabled
        )
        assertTrue("a confirmed external record", VisibleAuthority.AutoRemote(written, null, 6, AuthorityAvailability.CONFIRMED).pairedControlsEnabled)
        assertTrue("a confirmed auto record", VisibleAuthority.AutoRemote(written, null, 6, AuthorityAvailability.CONFIRMED).pairedControlsEnabled)
        assertFalse(
            "an unconfirmed one is not",
            VisibleAuthority.AutoRemote(written, null, 6, AuthorityAvailability.CACHED).pairedControlsEnabled
        )
        // Nothing is paired, so the widget's own record is the form's: exactly as before pairing.
        assertTrue("a local owner", VisibleAuthority.LocalOwner(localInputs).pairedControlsEnabled)
    }

    @Test
    fun twoProfilesCannotShareAPriceSubjectOrAMemo() {
        // The isolation the whole flow rests on, as values.
        val mine = PriceRequestKey("profile-a", "SE4", 1)
        val otherProfile = PriceRequestKey("profile-b", "SE4", 1)
        val otherGeneration = PriceRequestKey("profile-a", "SE4", 2)
        val otherArea = PriceRequestKey("profile-a", "NO1", 1)
        assertTrue("my own answer is mine", AuthorityRefresh.accepts(mine, mine))
        for (theirs in listOf(otherProfile, otherGeneration, otherArea)) {
            assertFalse("$theirs is not mine", AuthorityRefresh.accepts(theirs, mine))
            assertFalse("and mine is not theirs", AuthorityRefresh.accepts(mine, theirs))
            assertFalse("nor is a missing answer any of them", AuthorityRefresh.accepts(null, theirs))
        }
    }

    // ---- Reading production sources

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("${file.absolutePath} must exist", file.isFile)
        return file.readText()
    }

    /** The value of one string resource, read from a locale's own `strings.xml`. */
    private fun valueOf(xml: String, name: String): String = xml
        .lineSequence().first { it.contains(name) }
        .substringAfter('>').substringBefore('<').trim()
}
