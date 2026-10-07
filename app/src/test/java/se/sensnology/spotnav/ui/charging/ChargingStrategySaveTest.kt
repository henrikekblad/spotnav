package se.sensnology.spotnav.ui.charging

import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.authority.WriteSubject
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.HaSettingsEditResult
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures

import org.junit.Assert.*

/**
 * Ownership is not an editable strategy on its own -- paired chargers always belong to HA -- but
 * *which* strategy Home Assistant runs is, exactly when this charger's own `strategy_options` names
 * it.
 */
class ChargingStrategySaveTest {
    private val record = SettingsFixtures.parsed(revision = 6)
    private val cheapestOnly = setOf(HaSettingsStrategy.CHEAPEST)
    private val allThree = setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)

    private fun paired(strategy: HaSettingsStrategy = HaSettingsStrategy.CHEAPEST): VisibleAuthority =
        VisibleAuthority.AutoRemote(record.copy(strategy = strategy), null, 6, AuthorityAvailability.CONFIRMED)

    private fun ui(state: VisibleAuthority?, strategyOptions: Set<HaSettingsStrategy> = cheapestOnly) =
        ChargingStrategyPresentation.of(
            ChargerBinding.of(true, state != null), state, ChargerAction.START, null, strategyOptions
        )

    @Test fun theActiveStrategyIsNeverASelectableChange() {
        val face = ui(paired())
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, face.owner)
        assertFalse(face.choices.first().selectable)
        assertEquals(StrategyIntent.NothingToDo, ChargingStrategyPresentation.intent(face, ChargingStrategy.CHEAPEST))
    }

    @Test fun solarAndHybridAreUnavailableWhenThisChargerDoesNotOfferThem() {
        val face = ui(paired(), cheapestOnly)
        for (strategy in listOf(ChargingStrategy.SOLAR, ChargingStrategy.HYBRID)) {
            assertEquals(StrategyIntent.Unavailable, ChargingStrategyPresentation.intent(face, strategy))
            val choice = face.choices.first { it.strategy == strategy }
            assertFalse(choice.selectable)
            assertNotNull("a strategy this charger does not offer names a gap", choice.gap)
        }
    }

    @Test fun solarAndHybridAreWritableWhenThisChargerOffersThem() {
        val face = ui(paired(), allThree)
        for (strategy in listOf(ChargingStrategy.SOLAR, ChargingStrategy.HYBRID)) {
            val choice = face.choices.first { it.strategy == strategy }
            assertNull("an offered strategy has no gap to explain", choice.gap)
            assertTrue("offered, not chosen and writable: selectable", choice.selectable)
            val intent = ChargingStrategyPresentation.intent(face, strategy)
            assertTrue("$intent must be an admitted write", intent is StrategyIntent.Save)
            assertEquals(
                when (strategy) {
                    ChargingStrategy.SOLAR -> HaSettingsStrategy.SOLAR
                    ChargingStrategy.HYBRID -> HaSettingsStrategy.HYBRID
                    else -> error("unreachable")
                },
                (intent as StrategyIntent.Save).strategy
            )
        }
        // The confirmed strategy itself is chosen, not offered as a change, even though this
        // charger's own options include it:
        val cheapest = face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }
        assertTrue(cheapest.chosen)
        assertFalse(cheapest.selectable)
    }

    @Test fun aSolarConfirmedStrategyIsTheChosenRowWhenOffered() {
        val face = ui(paired(HaSettingsStrategy.SOLAR), allThree)
        assertEquals(ChargingStrategy.SOLAR, face.strategy)
        assertTrue(face.choices.first { it.strategy == ChargingStrategy.SOLAR }.chosen)
        assertFalse(face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }.chosen)
        // Cheapest is still writable back to, because it is still offered and not the active row.
        assertTrue(face.choices.first { it.strategy == ChargingStrategy.CHEAPEST }.selectable)
    }

    @Test fun aPendingBindingNamesNoLocalOwnerAndOffersNoStrategyMutation() {
        val face = ui(null)
        assertEquals(PlanningOwner.UNKNOWN, face.owner)
        assertEquals(StrategyStatus.PENDING, face.status)
        assertTrue(face.readOnly)
        assertFalse(face.choices.any { it.selectable })
    }

    @Test fun anOfflineRecordStillNamesHomeAssistantAndCannotChangeStrategyEvenWhenOffered() {
        // Offline:
        val face = ui(VisibleAuthority.ReadOnlyOffline(record), allThree)
        assertEquals(PlanningOwner.HOME_ASSISTANT_AUTOMATIC, face.owner)
        assertTrue(face.readOnly)
        assertFalse(face.choices.any { it.selectable })
    }

    @Test fun aPairedChargerWithNoRecordToShowNeverClaimsPhoneOwnership() {
        assertEquals(PlanningOwner.UNKNOWN, ui(VisibleAuthority.ReadOnlyOffline(null)).owner)
    }

    @Test fun unpairedLocalPlanningStillHasItsOwnOwner() {
        val face = ChargingStrategyPresentation.of(ChargerBinding.UNPAIRED, VisibleAuthority.LocalOwner(null), null, null)
        assertEquals(PlanningOwner.THIS_PHONE_LOCAL, face.owner)
        assertTrue(face.phaseRowVisible)
        assertEquals(StrategyIntent.NothingToDo, ChargingStrategyPresentation.intent(face, ChargingStrategy.CHEAPEST))
    }

    @Test fun theNewRecordHasNoModeToChangeAndTheBodyHasNoMode() {
        assertFalse(HaPlanningSettings::class.java.declaredFields.any { it.name == "mode" })
        assertFalse(HaSettingsCodec.encodeBody(record).has("mode"))
    }

    /**
     * A write the server refuses leaves the picker on the **confirmed** strategy, never on the one
     * that was attempted:
     */
    @Test fun aRefusedStrategyWriteLeavesThePickerOnTheConfirmedStrategy() {
        val profileId = "local-a"
        val cache = ConfirmedSettingsStore(FakeKeyValueStore()) { }
        val controller = AuthorityController(
            profileId = profileId,
            screenGeneration = 1,
            coordinator = null,
            cache = cache,
            catalogue = { listOf(RelayFixtures.se4) }
        )
        val confirmed = record.copy(revision = 6, areaId = "SE4", strategy = HaSettingsStrategy.CHEAPEST)
        cache.record(profileId, confirmed)

        val edit = HaSettingsEdit.Strategy(HaSettingsStrategy.SOLAR)
        val built = HaSettingsEditor.replacement(confirmed, edit)
        val replacement = (built as HaSettingsEditResult.Ready).settings
        assertEquals("the attempted write really does carry the new strategy", HaSettingsStrategy.SOLAR, replacement.strategy)

        // The server refuses it (a 400):
        val refusal = SettingsUpdate.Outcome.Invalid("invalid_strategy", confirmed)
        val outcome = controller.onWriteAnswer(
            WriteSubject(profileId, operation = 0, revision = confirmed.revision),
            refusal
        )

        val shown = (outcome as? WriteOutcome.Reported)?.authority ?: (outcome as WriteOutcome.Applied).authority
        assertEquals(
            "the picker shows the confirmed strategy, not the attempted one",
            HaSettingsStrategy.CHEAPEST,
            shown.remoteSettings!!.strategy
        )
        assertEquals(HaSettingsStrategy.CHEAPEST, cache.confirmed(profileId)!!.strategy)
    }
}
