package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.PairedOfflineTest
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.PlannerControl
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.io.File

/** The strategy row and its chooser, as the screen's own model. */
class ChargingStrategyUiTest {
    private val catalogue = listOf(RelayFixtures.se4)
    private val presentation = HaPresentation.QUARTER_HOUR

    /** The charger's record, in both of the two ownership spellings the contract can carry. */
    private val externalRecord = SettingsFixtures.parsed(revision = 5, areaId = "SE4")
    private val automaticRecord = SettingsFixtures.parsed(revision = 6, areaId = "SE4")

    private val localInputs = LocalPlanningInputs.of(
        WidgetSettings(area = "SE4", chargingAmps = 10, chargingKwh = 12.0)
    )

    private fun external(record: HaPlanningSettings = externalRecord): VisibleAuthority =
        VisibleAuthority.AutoRemote(
            settings = record,
            remotePlan = null,
            revision = record.revision,
            availability = AuthorityAvailability.CONFIRMED
        )

    private fun automatic(record: HaPlanningSettings = automaticRecord): VisibleAuthority =
        VisibleAuthority.AutoRemote(
            settings = record,
            remotePlan = null,
            revision = record.revision,
            availability = AuthorityAvailability.CONFIRMED
        )

    /** The decision a charging, unpaused automatic charger states: */
    private fun pauseControl(): AutoControl =
        AutoControl(
            AutoControl.ACTION_STOP,
            AutoControl.ACTION_PAUSE,
            listOf(AutoControl.PAUSE_UNTIL_RESUMED)
        )

    /** One face, from the binding the screen would have derived for it (see [ChargerBinding]). */
    private fun ui(
        authority: VisibleAuthority?,
        binding: ChargerBinding = ChargerBinding.UNPAIRED,
        action: ChargerAction? = ChargerAction.START,
        control: AutoControl? = null,
        strategyOptions: Set<HaSettingsStrategy> = setOf(HaSettingsStrategy.CHEAPEST)
    ): ChargingStrategyUi = ChargingStrategyPresentation.of(binding, authority, action, control, strategyOptions)

    /** This widget's resolved binding: a configured charger, or none at all. */
    private fun configured() = ChargerProfile(
        localId = "local-a",
        displayName = "A",
        baseUrl = "https://spotnav.example.invalid:8123",
        webhookId = "webhook-secret-a"
    )

    /** The screen's own first frame, from exactly what it knows when it is assembled. */
    private fun firstFrame(
        profile: ChargerProfile?,
        authority: VisibleAuthority? = null,
        action: ChargerAction? = ChargerAction.START,
        control: AutoControl? = null
    ): ChargingStrategyUi = ChargingStrategyScreenFace.first(profile, authority, action, control)

    // ---- 1. Paired automatic:

    @Test
    fun aPairedAutomaticChargerSaysBilligastAndAutomaticPlanningInHomeAssistant() {
        val face = ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY)

        assertEquals(ChargingStrategy.CHEAPEST, face.strategy)
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, face.owner)
        assertEquals(StrategyStatus.CONFIRMED, face.status)
        assertTrue("the record owns the values", face.homeAssistantOwns)
        assertTrue("Home Assistant already plans, so choosing it changes nothing", face.automaticOwner)
        assertFalse("and it is not a second time-settable state", face.readOnly)

        val cheapest = face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }
        assertNull("implemented, so it has no gap to explain", cheapest.gap)
        assertTrue("it is what runs", cheapest.chosen)
        assertFalse("and choosing it is not a change", cheapest.selectable)
        assertEquals(StrategyIntent.NothingToDo, ChargingStrategyPresentation.intent(face, ChargingStrategy.CHEAPEST))

    }

    @Test
    fun theStrategyRowExistsOnlyWhereHomeAssistantPlans() {
        assertFalse(ui(VisibleAuthority.LocalOwner(localInputs)).strategyRowVisible)
        assertFalse(firstFrame(profile = null).strategyRowVisible)
        assertTrue(ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY).strategyRowVisible)
        assertTrue(ui(external(), ChargerBinding.PAIRED_WITH_AUTHORITY).strategyRowVisible)
        assertTrue("a pairing still awaiting its first answer is not local", ui(null, ChargerBinding.PAIRED_AWAITING_STATUS).strategyRowVisible)
    }

    // ---- 2 and 3. Phases:

    @Test
    fun aPairedChargerWithdrawsBothPhaseControlsAndAnUnpairedOneKeepsThem() {
        // Both spellings of a paired concrete charger, because the OCPP topology is Home
        // Assistant's to know in each of them -- and both of the two surfaces answer together, so
        // the row cannot be hidden while the control behind it stays live.
        for (paired in listOf(
            ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY),
            ui(external(), ChargerBinding.PAIRED_WITH_AUTHORITY)
        )) {
            val face = paired
            assertTrue("$paired owns the record", face.homeAssistantOwns)
            assertFalse("$paired offers no phase row", face.phaseRowVisible)
            assertFalse("$paired leaves no live phase control", face.phaseRadioEnabled)
        }

        // Unpaired:
        val unpaired = ui(VisibleAuthority.LocalOwner(localInputs))

        assertFalse(unpaired.homeAssistantOwns)
        assertTrue("an unpaired widget keeps its phase row", unpaired.phaseRowVisible)
        assertTrue("and its phase control", unpaired.phaseRadioEnabled)
        assertEquals(PlanningOwner.THIS_PHONE_LOCAL, unpaired.owner)

        // Before any answer at all nothing has withdrawn anything:
        val unknown = ui(null)
        assertTrue("nothing has said otherwise yet", unknown.phaseRowVisible)
        assertTrue(unknown.phaseRadioEnabled)
        assertEquals(StrategyStatus.UNAVAILABLE, unknown.status)
    }





    // ---- 5. Solar and Hybrid:

    @Test
    fun aStrategyHeldBackForTheTotalGridPowerSaysSo() {
        val face = ChargingStrategyPresentation.of(
            ChargerBinding.PAIRED_WITH_AUTHORITY, automatic(), ChargerAction.START, null,
            setOf(HaSettingsStrategy.CHEAPEST),
            setOf(HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        )
        assertEquals(StrategyGap.TOTAL_GRID_POWER, face.choices.first { it.strategy == ChargingStrategy.SOLAR }.gap)
        assertEquals(StrategyGap.TOTAL_GRID_POWER, face.choices.first { it.strategy == ChargingStrategy.HYBRID }.gap)
        assertNull(face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }.gap)
    }

    @Test
    fun solarAndHybridAreUnavailableRowsWhenThisChargerDoesNotOfferThem() {
        // The default -- no recognisable strategy_options at all -- is cheapest-only, so neither
        // row is offered here.
        for (face in listOf(ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY), ui(external(), ChargerBinding.PAIRED_WITH_AUTHORITY), ui(VisibleAuthority.LocalOwner(localInputs)))) {
            val solar = face.choices.first { it.strategy == ChargingStrategy.SOLAR }
            val hybrid = face.choices.first { it.strategy == ChargingStrategy.HYBRID }
            assertEquals(StrategyGap.MEASURED_SOLAR_PRODUCTION, solar.gap)
            assertEquals(StrategyGap.SOLAR_MEASUREMENT_AND_CONTROL, hybrid.gap)
            assertFalse("Sol is never the chosen row here", solar.chosen)
            assertFalse("and not selectable while unoffered", solar.selectable)
            assertFalse("nor is Hybrid chosen", hybrid.chosen)
            assertFalse(hybrid.selectable)
            assertEquals(3, face.choices.size)

            // The tap's own answer, for both rows and in a state where a change *would* be
            // admissible:
            assertEquals(StrategyIntent.Unavailable, ChargingStrategyPresentation.intent(face, ChargingStrategy.SOLAR))
            assertEquals(StrategyIntent.Unavailable, ChargingStrategyPresentation.intent(face, ChargingStrategy.HYBRID))
        }
    }

    @Test
    fun solarAndHybridBecomeWritableRowsExactlyWhenThisChargerOffersThem() {
        // Selectability comes from strategy_options alone:
        val offered = setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        val face = ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY, strategyOptions = offered)

        val solar = face.choices.first { it.strategy == ChargingStrategy.SOLAR }
        val hybrid = face.choices.first { it.strategy == ChargingStrategy.HYBRID }
        assertNull("an offered strategy has no gap to explain", solar.gap)
        assertNull(hybrid.gap)
        assertFalse(solar.chosen)
        assertTrue("offered, not chosen, writable: selectable", solar.selectable)
        assertFalse(hybrid.chosen)
        assertTrue(hybrid.selectable)

        assertEquals(
            StrategyIntent.Save(HaSettingsStrategy.SOLAR),
            ChargingStrategyPresentation.intent(face, ChargingStrategy.SOLAR)
        )
        assertEquals(
            StrategyIntent.Save(HaSettingsStrategy.HYBRID),
            ChargingStrategyPresentation.intent(face, ChargingStrategy.HYBRID)
        )

        // Offering solar and hybrid never makes an unoffered charger's rows selectable:
        val notOffered = ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY)
        assertFalse(notOffered.choices.first { it.strategy == ChargingStrategy.SOLAR }.selectable)
    }

    @Test
    fun anOfferedButAlreadyActiveSolarStrategyIsChosenAndNotASelectableChange() {
        // The confirmed record already states solar:
        val solarRecord = automaticRecord.copy(strategy = HaSettingsStrategy.SOLAR)
        val offered = setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        val face = ui(automatic(solarRecord), ChargerBinding.PAIRED_WITH_AUTHORITY, strategyOptions = offered)

        assertEquals(ChargingStrategy.SOLAR, face.strategy)
        val solar = face.choices.first { it.strategy == ChargingStrategy.SOLAR }
        assertTrue(solar.chosen)
        assertFalse("what already runs is never a selectable change", solar.selectable)
        assertEquals(StrategyIntent.NothingToDo, ChargingStrategyPresentation.intent(face, ChargingStrategy.SOLAR))

        val cheapest = face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }
        assertFalse(cheapest.chosen)
        assertTrue(cheapest.selectable)
        assertEquals(
            StrategyIntent.Save(HaSettingsStrategy.CHEAPEST),
            ChargingStrategyPresentation.intent(face, ChargingStrategy.CHEAPEST)
        )
    }

    @Test
    fun anOfferedStrategyIsStillNotSelectableWhileTheRecordIsNotWritable() {
        // Offline:
        val offered = setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        val face = ui(VisibleAuthority.ReadOnlyOffline(automaticRecord), strategyOptions = offered)
        val solar = face.choices.first { it.strategy == ChargingStrategy.SOLAR }
        assertNull("offered, so no gap -- it is writability that withholds it", solar.gap)
        assertFalse(solar.selectable)
        assertEquals(StrategyIntent.NotWritable, ChargingStrategyPresentation.intent(face, ChargingStrategy.SOLAR))
    }

    // ---- 6.

    @Test
    fun theImmediateActionsAreCarriedThroughAndChangeNeitherStrategyNorOwner() {
        // The existing model's own answers, passed through:
        val reference = ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY, action = null)
        for (action in listOf<ChargerAction?>(null, ChargerAction.START, ChargerAction.STOP)) {
            val face = ui(automatic(), ChargerBinding.PAIRED_WITH_AUTHORITY, action = action)
            assertSame("the action is carried through unchanged: $action", action, face.immediateAction)
            assertEquals("strategy is not the action's to change: $action", reference.strategy, face.strategy)
            assertEquals("ownership is not the action's to change: $action", reference.owner, face.owner)
            assertEquals("nor the chooser's own rows: $action", reference.choices, face.choices)
            assertEquals("nor the phase editor: $action", reference.phaseRowVisible, face.phaseRowVisible)
            assertEquals("nor the phase control: $action", reference.phaseRadioEnabled, face.phaseRadioEnabled)
            assertEquals("nor the automatic control: $action", reference.automaticControl, face.automaticControl)
        }
    }

    // ---- 11.

    @Test
    fun aChooserIsScopedToTheScreenThatOpenedIt() {
        // Two instances, because that is what two screens have -- and neither may answer for the
        // other, whichever way the screens differ (another profile, another widget, another
        // activity).
        val first = StrategyChooserState()
        val second = StrategyChooserState()
        val here = ChooserSubject("local-a", 1)

        assertFalse("nothing is open to begin with", first.isCurrent(here))
        first.opened(here)
        assertTrue(first.isCurrent(here))
        assertFalse("another screen's chooser is not this one", second.isCurrent(here))
        assertFalse("another generation is not this one", first.isCurrent(here.copy(screenGeneration = 2)))
        assertFalse("another profile is not this one", first.isCurrent(here.copy(profileId = "local-b")))
        first.closed()
        assertFalse("a closed chooser answers for nothing", first.isCurrent(here))
    }

    // ---- The first frame:

    @Test
    fun aPairedChargerWithNoAnswerYetIsPendingRatherThanPhoneLocal() {
        // Executed through the screen's own first-frame path:
        val face = firstFrame(configured())

        assertEquals("no owner may be claimed yet", PlanningOwner.UNKNOWN, face.owner)
        assertNotEquals(
            "and it is emphatically not this phone planning",
            PlanningOwner.THIS_PHONE_LOCAL, face.owner
        )
        assertEquals(StrategyStatus.PENDING, face.status)
        assertFalse("no one has said Home Assistant owns it", face.homeAssistantOwns)
        assertFalse("nor that it is the one planning", face.automaticOwner)
        assertTrue("and nothing may be written from this state", face.readOnly)
        assertEquals(ChargingStrategy.CHEAPEST, face.strategy)

        val cheapest = face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }
        assertTrue("the strategy in force is the implemented one", cheapest.chosen)
        assertFalse("but not something this state can be asked to change", cheapest.selectable)
        assertEquals(StrategyIntent.NotWritable, ChargingStrategyPresentation.intent(face, ChargingStrategy.CHEAPEST))
    }

    @Test
    fun theFirstFrameOfAPairedChargerWithdrawsBothPhaseSurfaces() {
        // Both surfaces, from the one first face: the card's row and the control behind it.
        val face = firstFrame(configured())
        assertFalse("no phase row on the first frame of a paired charger", face.phaseRowVisible)
        assertFalse("and no live phase control behind it", face.phaseRadioEnabled)

        // Unpaired: nothing else owns the wiring, so both stay, exactly as before pairing.
        val unpaired = firstFrame(profile = null)
        assertTrue(unpaired.phaseRowVisible)
        assertTrue(unpaired.phaseRadioEnabled)
    }

    @Test
    fun theFirstFrameOfAPairedChargerOffersNoAutomaticControl() {
        // The charger's own decision is not in hand yet -- it arrives with the first dashboard --
        // and a control derived from "nothing was said" would be a pause or a resume this phone
        // invented.
        assertNull("the first frame offers no automatic control", firstFrame(configured()).automaticControl)
        // Even with a decision in hand, nothing may be sent before the record is known:
        assertNull(
            "a pending face offers no control, whatever a stale decision says",
            firstFrame(configured(), control = pauseControl()).automaticControl
        )
    }

    @Test
    fun anUnpairedFirstFrameKeepsLocalOwnershipAndItsOwnPhases() {
        // Nothing is paired, and nothing has to change here:
        val face = firstFrame(profile = null)

        assertEquals(PlanningOwner.THIS_PHONE_LOCAL, face.owner)
        assertEquals(StrategyStatus.UNAVAILABLE, face.status)
        assertFalse(face.homeAssistantOwns)
        assertFalse("a local screen is not read-only", face.readOnly)
        assertTrue("its phase editor is its own", face.phaseRowVisible)
        assertTrue(face.phaseRadioEnabled)
        assertNull("and there is no automatic planning to pause", face.automaticControl)

        val stale = ChargerProfile(localId = "local-a", displayName = "A", baseUrl = "", webhookId = "")
        assertEquals(PlanningOwner.THIS_PHONE_LOCAL, firstFrame(stale).owner)
        assertNull(firstFrame(stale).automaticControl)
    }

    @Test
    fun theConfirmedPairedStatesKeepTheirAcceptedPresentation() {
        // A confirmed Auto record and a confirmed compatibility one, through the same first-frame
        // seam:
        val automaticFace = firstFrame(configured(), automatic(), control = pauseControl())
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, automaticFace.owner)
        assertEquals(StrategyStatus.CONFIRMED, automaticFace.status)
        assertFalse(automaticFace.readOnly)
        assertFalse("Auto: phases are Home Assistant's", automaticFace.phaseRowVisible)
        assertEquals("and the host's own decision is the control", PlannerControl.PAUSE, automaticFace.automaticControl)

        val externalFace = firstFrame(configured(), external())
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, externalFace.owner)
        assertEquals(StrategyStatus.CONFIRMED, externalFace.status)
        assertFalse("the compatibility record's topology is Home Assistant's too", externalFace.phaseRowVisible)
        assertNull("and it offers no automatic control: nothing automatic executes for it", externalFace.automaticControl)

        // The remembered record of an uncheckable one keeps its own owner and the read-only
        // sentence -- and offers no control either, because a read-only screen may send nothing.
        val offlineFace = firstFrame(configured(), VisibleAuthority.ReadOnlyOffline(automaticRecord), control = pauseControl())
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, offlineFace.owner)
        assertEquals(StrategyStatus.REMEMBERED_READ_ONLY, offlineFace.status)
        assertTrue(offlineFace.readOnly)
        assertNull("nothing may be sent from a read-only screen", offlineFace.automaticControl)
    }

    @Test
    fun thePendingRuleNeitherInventsNorRemovesAnImmediateAction() {
        // Start/Stop availability is the existing model's answer, passed through in every state --
        // and the pending rule changes nothing about it.
        for (action in listOf<ChargerAction?>(null, ChargerAction.START, ChargerAction.STOP)) {
            val face = firstFrame(configured(), action = action)
            assertSame("the action is the model's own: $action", action, face.immediateAction)
            assertEquals("and it does not change the owner: $action", PlanningOwner.UNKNOWN, face.owner)
            assertEquals("nor the status: $action", StrategyStatus.PENDING, face.status)
        }
    }

    @Test
    fun theBindingIsDerivedFromTheConfiguredChargerAndTheAnswer() {
        // The one typed fact, in all three values plus the disagreement rule.
        assertEquals("nothing bound", ChargerBinding.UNPAIRED, ChargerBinding.of(false, false))
        assertEquals(
            "no configured charger means no pairing, whatever an answer says",
            ChargerBinding.UNPAIRED, ChargerBinding.of(false, true)
        )
        assertEquals(ChargerBinding.PAIRED_AWAITING_STATUS, ChargerBinding.of(true, false))
        assertEquals(ChargerBinding.PAIRED_WITH_AUTHORITY, ChargerBinding.of(true, true))

        // An answer in hand outranks a caller that still says it is waiting:
        val answered = ChargingStrategyPresentation.of(
            binding = ChargerBinding.PAIRED_AWAITING_STATUS,
            authority = automatic(),
            immediateAction = ChargerAction.START,
            control = pauseControl()
        )
        assertNotEquals("an answered binding is not pending", StrategyStatus.PENDING, answered.status)
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, answered.owner)
        assertEquals("and its decision is the control", PlannerControl.PAUSE, answered.automaticControl)
    }

    @Test
    fun theStrategyCopyIsLocalizedInEveryLocaleAndNamesNoWireValue() {
        val locales = listOf("values", "values-sv", "values-nb", "values-da", "values-fi", "values-de", "values-nl", "values-es", "values-fr")
        val keys = listOf(
            "strategy_title_cheapest", "strategy_title_solar", "strategy_title_hybrid",
            "strategy_field_label",
            "strategy_change_read_only",
            "strategy_chooser_title",
            "strategy_choice_active", "strategy_choice_automatic",
            "strategy_gap_solar_measurement", "strategy_gap_solar_control",
            "strategy_row_description",
            // The two controls, named once per language:
            "home_assistant_pause_automatic", "home_assistant_resume_automatic"
        )
        // The words a person must never read in normal copy:
        val forbidden = listOf("auto_price", "external", "External", "auto price", "revision", "CAS")
        for (locale in locales) {
            val xml = read("src/main/res/$locale/strings.xml")
            for (key in keys) {
                val value = valueOf(xml, key)
                assertTrue("$locale must carry $key", value.isNotBlank())
                for (word in forbidden) {
                    assertFalse("$locale's $key must not say \"$word\": $value", value.contains(word))
                }
            }
        }

        // The locked product vocabulary itself: Billigast in Swedish, and never a wire value.
        assertEquals("Cheapest", valueOf(read("src/main/res/values/strings.xml"), "strategy_title_cheapest"))
        assertEquals("Billigast", valueOf(read("src/main/res/values-sv/strings.xml"), "strategy_title_cheapest"))
        assertEquals("Laddstrategi", valueOf(read("src/main/res/values-sv/strings.xml"), "strategy_field_label"))
        assertEquals("Sol", valueOf(read("src/main/res/values-sv/strings.xml"), "strategy_title_solar"))
        assertEquals("Hybrid", valueOf(read("src/main/res/values-sv/strings.xml"), "strategy_title_hybrid"))

        assertEquals(
            "Pause automatic charging",
            valueOf(read("src/main/res/values/strings.xml"), "home_assistant_pause_automatic")
        )
        assertEquals(
            "Resume automatic charging",
            valueOf(read("src/main/res/values/strings.xml"), "home_assistant_resume_automatic")
        )
        assertEquals(
            "Pausa automatisk laddning",
            valueOf(read("src/main/res/values-sv/strings.xml"), "home_assistant_pause_automatic")
        )
        assertEquals(
            "Återuppta automatisk laddning",
            valueOf(read("src/main/res/values-sv/strings.xml"), "home_assistant_resume_automatic")
        )
    }

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("${file.absolutePath} must exist", file.isFile)
        return file.readText()
    }

    /** The value of one string resource, read from a locale's own `strings.xml`. */
    private fun valueOf(xml: String, name: String): String = xml
        .lineSequence().first { it.contains("name=\"$name\"") }
        .substringAfter('>').substringBefore('<').trim()
}
