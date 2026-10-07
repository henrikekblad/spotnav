package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.HaPlanningInputs
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.SettingsAuthorityCoordinator
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.authority.WriteSubject
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One press of Save on the Settings screen: one document, one revision, one request. */
class PairedSettingsFormTest {
    private val profileId = "local-a"
    private val otherId = "local-b"
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private val presentation = HaPresentation.QUARTER_HOUR

    private val cache = ConfirmedSettingsStore(FakeKeyValueStore()) { }

    /** The widget's own record, deliberately disagreeing with the charger's. */
    private val local = WidgetSettings(
        area = "NO1", vat = true, tax = true, taxMinorUnit = 8.0,
        transfer = false, gridFeeMinorUnit = 0.0, chargerProfileId = profileId
    )

    /** The charger's confirmed record: SE4, a VAT rate, an explicit zero tax, a decimal fee. */
    private val record = SettingsFixtures.parsed(
        revision = 5,
        areaId = "SE4",
        overrides = JSONArray()
            .put(overrideRow("NO1", vat = 25.0, tax = 7.13))
            .put(overrideRow("SE4", vat = 12.0, tax = 0.0, transfer = 5.5))
    )

    /** One area's override row: a figure present means the component is on with that figure. */
    private fun overrideRow(areaId: String, vat: Double? = null, tax: Double? = null, transfer: Double? = null) =
        SettingsFixtures.override(
            areaId = areaId,
            vat = SettingsFixtures.fiscal(enabled = vat != null, value = vat),
            tax = SettingsFixtures.fiscal(enabled = tax != null, value = tax),
            transfer = SettingsFixtures.fiscal(enabled = transfer != null, value = transfer)
        )

    /** A transport that records every call it is asked to make. */
    private inner class RecordingTransport(
        private val answer: (SettingsSave.Send) -> SettingsUpdate.Outcome
    ) {
        val calls = mutableListOf<SettingsSave.Send>()

        fun call(decision: SettingsSave.Send): SettingsUpdate.Outcome {
            calls += decision
            return answer(decision)
        }
    }

    /** The controller the settings screen builds: */
    private fun controller(profile: String? = profileId): AuthorityController = AuthorityController(
        profileId = profile,
        screenGeneration = 1,
        coordinator = null,
        cache = cache,
        catalogue = { catalogue },
        presentation = { presentation }
    )

    /** A controller with the confirmed record already resolved, as the screen has on opening: */
    private fun pairedController(profile: String? = profileId): AuthorityController =
        controller(profile).also { authority ->
            if (profile != null && cache.confirmed(profile) == null) {
                cache.recordOutcome(profile, SettingsUpdate.Outcome.Updated(record))
            }
            authority.seedFromConfirmedRecord()
        }

    /** The screen's own press, through the session it calls. */
    private fun pressSave(
        authority: AuthorityController,
        values: SettingsFormValues,
        transport: RecordingTransport,
        available: (String) -> Boolean = { true }
    ): FormSaveOutcome {
        askedFor.clear()
        return SettingsFormSession.save(authority, values) { profileId ->
            askedFor += profileId
            if (available(profileId)) SettingsSaveTarget { decision -> transport.call(decision) } else null
        }
    }

    /** The owner ids the screen looked up for the last press. */
    private val askedFor = mutableListOf<String>()

    private fun external(record: HaPlanningSettings): PlanningInputs =
        SettingsFixtures.localInputs(record, catalogue, presentation)

    // The screen's
    // authority source, and the answers that leave a record standing.

    @Test
    fun aPairedScreenSeedsFromTheConfirmedRecordAndShowsNoLocalValues() {
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record))
        val authority = controller()

        val state = authority.seedFromConfirmedRecord()

        assertTrue("expected the record's state, got $state", state is VisibleAuthority.AutoRemote)
        assertEquals(record, (state as VisibleAuthority.AutoRemote).remoteSettings)
        assertTrue("a confirmed record may be edited", state.writable)
        // The form renders from that record -- SE4 and its own overrides -- and never from the
        // widget's stored copy, which disagrees with it on purpose.
        assertFalse(local.area == record.areaId)
        assertEquals(12.0, HaSettingsEditor.fiscalFor(record, HaAreaOverrideComponent.VAT, record.areaId).value!!, 0.0)
        assertTrue(local.vat)
    }

    @Test
    fun aPairedScreenWithNoConfirmedRecordIsReadOnly() {
        val authority = controller()

        val state = authority.seedFromConfirmedRecord()

        assertEquals(VisibleAuthority.ReadOnlyOffline(null), state)
        assertFalse("nothing confirmed may be edited", state!!.writable)
        assertEquals(
            SettingsSave.ReadOnly,
            authority.admitFormSave(SettingsFormValues("NO1", true, false, null, false, null))
        )
    }

    @Test
    fun anUnchangedFormProducesTheRecordItself() {
        // a record field this screen has no control for survives a Save untouched.
        val unchanged = SettingsFormValues("SE4", vat = true, tax = true, taxFigure = 0.0, transfer = true, transferFigure = 5.5)
        val built = PairedSettingsForm.replacement(record, unchanged, catalogue) as HaSettingsEditResult.Ready
        assertEquals(record, built.settings)
        assertEquals(record, record.copy())
    }

    @Test
    fun aPostCommitReconcileFailureShowsTheCommittedRecord() {
        val authority = pairedController()
        val committed = record.copy(revision = 7, amps = 8)
        val transport = RecordingTransport { SettingsUpdate.Outcome.CommittedButReconcileFailed(committed) }

        val save = pressSave(
            authority,
            SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null),
            transport
        ) as FormSaveOutcome.Sent

        assertEquals(1, transport.calls.size)
        assertEquals(
            "the committed record is what stands",
            committed,
            (save.outcome as WriteOutcome.Applied).authority.remoteSettings
        )
        assertEquals(SettingsUpdate.Outcome.CommittedButReconcileFailed(committed), save.answer)
        assertEquals(7, (authority.authority as VisibleAuthority.AutoRemote).revision)
    }

    @Test
    fun aRefusalKeepsTheConfirmedRecordAndTheAbilityToWrite() {
        val authority = pairedController()
        val transport = RecordingTransport { SettingsUpdate.Outcome.Invalid("invalid_area", null) }
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)

        val save = pressSave(authority, values, transport) as FormSaveOutcome.Sent

        assertEquals(1, transport.calls.size)
        // A pre-commit refusal changes neither the visible record nor what may be edited.
        val applied = save.outcome as WriteOutcome.Applied
        assertEquals(record, applied.authority.remoteSettings)
        assertTrue("the screen may still write after a refusal", applied.authority.writable)
        val next = authority.admitFormSave(values)
        assertTrue("and the next press is admitted: $next", next is SettingsSave.Send)
    }

    @Test
    fun profilesDoNotLeakValuesRevisionsOrWrites() {
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record))
        cache.recordOutcome(otherId, SettingsUpdate.Outcome.Updated(record.copy(revision = 2, amps = 8)))
        val a = pairedController(profileId)
        val b = pairedController(otherId)

        val stateA = a.authority as VisibleAuthority.AutoRemote
        val stateB = b.authority as VisibleAuthority.AutoRemote
        assertEquals("SE4", stateA.remoteSettings!!.areaId)
        assertEquals(5, stateA.revision)
        assertEquals(8, stateB.remoteSettings!!.amps)
        assertEquals(2, stateB.revision)

        // A decision admitted for one profile's screen is not admitted for the other's, and the
        // other's own operation counter is its own.
        val decisionA = a.admitFormSave(SettingsFormValues("NO1", true, false, null, false, null)) as SettingsSave.Send
        assertFalse("another owner's screen cannot send it", b.stillAdmitted(decisionA))
        assertTrue("while the screen it belongs to can", a.stillAdmitted(decisionA))
        assertEquals(2, (b.authority as VisibleAuthority.AutoRemote).revision)
    }

    // The document.

    @Test
    fun changingAreaVatTaxAndTransferIsOneCompleteReplacement() {
        // A form that changed all four field groups at once.
        val values = SettingsFormValues(
            areaId = "NO1", vat = true, tax = false, taxFigure = null, transfer = true, transferFigure = 3.25
        )

        val built = PairedSettingsForm.replacement(record, values, catalogue)
        assertTrue("expected a built document, got $built", built is HaSettingsEditResult.Ready)
        val replacement = (built as HaSettingsEditResult.Ready).settings

        assertEquals("NO1", replacement.areaId)
        assertEquals(
            HaAreaOverride(
                areaId = "NO1",
                vat = HaFiscalValue(enabled = true, value = 25.0),
                tax = HaFiscalValue.OFF,
                transfer = HaFiscalValue(enabled = true, value = 3.25)
            ),
            replacement.overrides.first { it.areaId == "NO1" }
        )
        // The revision travels in the model and never in the body: the caller names it separately.
        assertEquals(5, replacement.revision)
        assertFalse("a body states no revision", HaSettingsCodec.encodeBody(replacement).has("revision"))
    }

    @Test
    fun theReplacementRetainsEveryFieldTheFormDoesNotEdit() {
        val values = SettingsFormValues("SE4", vat = true, tax = true, taxFigure = 0.0, transfer = true, transferFigure = 5.5)
        val replacement = (PairedSettingsForm.replacement(record, values, catalogue) as HaSettingsEditResult.Ready).settings

        // Everything the Settings screen has no control for stays exactly as the charger stated it.
        assertEquals(record.phases, replacement.phases)
        assertEquals(record.amps, replacement.amps)
        assertEquals(record.requestedKwh, replacement.requestedKwh, 0.0)
        assertEquals(record.maxPeriods, replacement.maxPeriods)
        assertEquals(record.departureEnabled, replacement.departureEnabled)
        assertEquals(record.departureTime, replacement.departureTime)
        assertEquals(record.driver, replacement.driver)
        assertEquals(record.target, replacement.target)
        // And an unrelated area's money is untouched, value for value.
        assertEquals(
            record.copy(overrides = emptyList(), areaId = "x"),
            replacement.copy(overrides = emptyList(), areaId = "x")
        )
        assertEquals(record.overrides.first { it.areaId == "NO1" }, replacement.overrides.first { it.areaId == "NO1" })
    }

    @Test
    fun aFormerAreasFiguresAreNeverCopiedIntoTheSelectedArea() {
        // The form stands for SE4 and changes only the tax figure.
        val values = SettingsFormValues("SE4", vat = true, tax = true, taxFigure = 9.5, transfer = true, transferFigure = 5.5)
        val replacement = (PairedSettingsForm.replacement(record, values, catalogue) as HaSettingsEditResult.Ready).settings
        assertEquals(9.5, replacement.overrides.first { it.areaId == "SE4" }.tax.value!!, 0.0)
        assertEquals(7.13, replacement.overrides.first { it.areaId == "NO1" }.tax.value!!, 0.0)

        // Moving to NO1 with only the market's own VAT.
        val moved = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val movedRecord = (PairedSettingsForm.replacement(record, moved, catalogue) as HaSettingsEditResult.Ready).settings
        assertEquals(
            HaAreaOverride(areaId = "NO1", vat = HaFiscalValue(enabled = true, value = 25.0)),
            movedRecord.overrides.first { it.areaId == "NO1" }
        )
    }

    @Test
    fun emptyExplicitZeroAndDecimalFiscalFiguresStayDistinct() {
        val base = SettingsFixtures.parsed(revision = 2, areaId = "SE4")

        fun taxOf(tax: Boolean, figure: Double?): HaFiscalValue {
            val values = SettingsFormValues("SE4", vat = false, tax = tax, taxFigure = figure, transfer = false, transferFigure = null)
            val replacement = (PairedSettingsForm.replacement(base, values, catalogue) as HaSettingsEditResult.Ready).settings
            // No entry at all is the same statement as an all-off one.
            return replacement.overrides.firstOrNull { it.areaId == "SE4" }?.tax ?: HaFiscalValue.OFF
        }

        assertEquals("on with no figure", HaFiscalValue(enabled = true, value = null), taxOf(tax = true, figure = null))
        assertEquals("an explicit zero", HaFiscalValue(enabled = true, value = 0.0), taxOf(tax = true, figure = 0.0))
        assertEquals("a decimal", HaFiscalValue(enabled = true, value = 5.5), taxOf(tax = true, figure = 5.5))
        assertEquals("and off", HaFiscalValue.OFF, taxOf(tax = false, figure = 5.5))
        assertNotEquals(taxOf(tax = true, figure = null), taxOf(tax = true, figure = 0.0))

        // An area with no override of its own and nothing switched on gets no entry at all.
        val untouched = SettingsFormValues("SE5", false, false, null, false, null)
        val built = (PairedSettingsForm.replacement(base, untouched, catalogue) as HaSettingsEditResult.Ready).settings
        assertEquals(emptyList<HaAreaOverride>(), built.overrides)
    }

    @Test
    fun theVatSwitchChangesOnlyWhatItCanState() {
        val base = SettingsFixtures.parsed(revision = 2, areaId = "SE4")
        val market = catalogue.first { it.id == "SE4" }

        fun vatOf(vat: Boolean): HaFiscalValue {
            val values = SettingsFormValues("SE4", vat = vat, tax = false, taxFigure = null, transfer = false, transferFigure = null)
            val replacement = (PairedSettingsForm.replacement(base, values, catalogue) as HaSettingsEditResult.Ready).settings
            return replacement.overrides.firstOrNull { it.areaId == "SE4" }?.vat ?: HaFiscalValue.OFF
        }

        // Switching it on applies the market's own published rate, and switching it off is off.
        assertEquals(HaFiscalValue(enabled = true, value = market.vatPercent), vatOf(vat = true))
        assertEquals(HaFiscalValue.OFF, vatOf(vat = false))

        // A rate the record already states is kept exactly while the switch stays on.
        val kept = (PairedSettingsForm.replacement(
            record,
            SettingsFormValues("SE4", vat = true, tax = true, taxFigure = 0.0, transfer = true, transferFigure = 5.5),
            catalogue
        ) as HaSettingsEditResult.Ready).settings
        assertEquals(HaFiscalValue(enabled = true, value = 12.0), kept.overrides.first { it.areaId == "SE4" }.vat)
    }

    @Test
    fun theFormShowsAnAbsentFigureAsEmptyAndAStatedZeroAsZero() {
        assertEquals("", PairedSettingsForm.figureText(HaFiscalValue(enabled = true, value = null)))
        assertEquals("0.0", PairedSettingsForm.figureText(HaFiscalValue(enabled = true, value = 0.0)))
        assertEquals("12.5", PairedSettingsForm.figureText(HaFiscalValue(enabled = true, value = 12.5)))
    }

    // The
    // transaction.

    // The
    // orchestration.

    @Test
    fun everyPressGoesThroughTheSessionIncludingAnUnpairedOne() {
        val values = SettingsFormValues("NO1", vat = true, tax = true, taxFigure = 8.0, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val unpaired = controller(profile = null)

        val save = pressSave(unpaired, values, transport)

        // The unpaired screen is not a special path.
        assertEquals(FormSaveOutcome.NotSent(SettingsSave.LocalOnly), save)
        assertEquals(
            listOf(SaveEffect.PersistLocal(paired = false), SaveEffect.Publish),
            SettingsFormSession.effects(save)
        )
        assertEquals("no transport call whatsoever", 0, transport.calls.size)
        assertTrue("and no owner was looked up either", askedFor.isEmpty())
    }

    @Test
    fun aPairedOutcomeNeverPersistsItsFieldsLocally() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val paired = pairedController()

        val save = pressSave(paired, values, transport) as FormSaveOutcome.Sent
        val effects = SettingsFormSession.effects(save)

        // Exactly one local write, and it is the presentation-only one.
        assertEquals(1, effects.filterIsInstance<SaveEffect.PersistLocal>().size)
        assertEquals(SaveEffect.PersistLocal(paired = true), effects.first())
        assertFalse("and the widget is never published for a paired field", effects.contains(SaveEffect.Publish))
        assertEquals(1, transport.calls.size)

        // A read-only paired press writes nothing paired and sends nothing, and still keeps the
        // presentation settings on their own behaviour.
        val offline = pairedController()
        val admitted = offline.admitFormSave(values) as SettingsSave.Send
        offline.onWriteAnswer(
            WriteSubject(profileId, admitted.operation, admitted.expectedRevision),
            SettingsUpdate.Outcome.Unavailable
        )
        val refusedSave = pressSave(offline, values, transport)
        assertEquals(FormSaveOutcome.NotSent(SettingsSave.ReadOnly), refusedSave)
        assertEquals(
            listOf(
                SaveEffect.PersistLocal(paired = true),
                SaveEffect.RenderState(SettingsSave.ReadOnly, abandoned = false)
            ),
            SettingsFormSession.effects(refusedSave)
        )
        assertEquals("still exactly one request, the earlier one", 1, transport.calls.size)
    }

    @Test
    fun aSaveIsSentOnlyThroughTheOwnerItWasAdmittedFor() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }

        val save = pressSave(pairedController(), values, transport) as FormSaveOutcome.Sent

        assertEquals("the admitted owner was looked up", listOf(profileId), askedFor)
        assertEquals(1, transport.calls.size)
        assertEquals("and the request went through it", profileId, transport.calls.single().profileId)
        assertEquals(profileId, save.decision.profileId)
    }

    @Test
    fun aNewerAdmissionDuringTargetResolutionAbandonsTheOlderSave() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val authority = pairedController()

        // Press one.
        val parked = CountDownLatch(1)
        val released = CountDownLatch(1)
        val olderResult = AtomicReference<FormSaveOutcome>()
        val older = thread(name = "older-save") {
            olderResult.set(
                SettingsFormSession.save(authority, values) { _ ->
                    parked.countDown()
                    assertTrue("the resolver was released", released.await(10, TimeUnit.SECONDS))
                    SettingsSaveTarget { decision -> transport.call(decision) }
                }
            )
        }
        assertTrue("press one reached its resolver", parked.await(10, TimeUnit.SECONDS))

        // Press two, on the same screen and at the same displayed revision, runs to completion.
        val newer = pressSave(authority, values, transport) as FormSaveOutcome.Sent
        assertEquals("exactly one request so far", 1, transport.calls.size)

        released.countDown()
        older.join(10_000)
        assertEquals(
            FormSaveOutcome.NotSent(SettingsSave.ReadOnly, abandoned = true),
            olderResult.get()
        )
        assertEquals("press one sent nothing", 1, transport.calls.size)
        assertEquals("and the one request was press two's own", newer.decision.operation, transport.calls.single().operation)

        // The abandoned press writes no paired field.
        assertEquals(
            listOf(
                SaveEffect.PersistLocal(paired = true),
                SaveEffect.RenderState(SettingsSave.ReadOnly, abandoned = true)
            ),
            SettingsFormSession.effects(olderResult.get())
        )
    }

    @Test
    fun anOwnerThatCannotBeResolvedAbandonsTheSaveWithNoRequestAndNoPairedWrite() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        // Profile A is displayed and admitted; by the time the request would go out, only B exists.
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val authority = pairedController()

        val save = pressSave(authority, values, transport, available = { it == otherId })

        assertEquals("the admitted owner is the one that was looked up", listOf(profileId), askedFor)
        assertEquals(FormSaveOutcome.NotSent(SettingsSave.ReadOnly, abandoned = true), save)
        assertEquals("zero calls, to A and to B", 0, transport.calls.size)
        assertEquals(
            "and no paired field reaches the widget's record",
            listOf(
                SaveEffect.PersistLocal(paired = true),
                SaveEffect.RenderState(SettingsSave.ReadOnly, abandoned = true)
            ),
            SettingsFormSession.effects(save)
        )
        // The form is restored to what is confirmed, and the state is untouched by the abandonment.
        assertEquals(record, authority.authority!!.remoteSettings)
        assertTrue(authority.authority!!.writable)
    }

    @Test
    fun aNewerAdmittedSaveAbandonsAnOlderOneThatHasNotStarted() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val authority = pairedController()

        // Two presses land before either request goes out.
        val older = authority.admitFormSave(values) as SettingsSave.Send
        // Checked *before* any answer could have changed the revision.
        val provisional = authority.admitFormSave(values) as SettingsSave.Send
        assertTrue("the newer admission has the current operation", provisional.operation > older.operation)
        assertFalse("so the older one is abandoned before it ever reaches send", authority.stillAdmitted(older))
        assertTrue("while the newer one is still admitted", authority.stillAdmitted(provisional))

        // Press two runs to completion.
        val newer = pressSave(authority, values, transport) as FormSaveOutcome.Sent
        assertTrue(newer.decision.operation > provisional.operation)
        assertEquals("exactly one request in total", 1, transport.calls.size)
        assertEquals(newer.decision.operation, transport.calls.single().operation)
        assertEquals("and the older Save was never sent", 0, transport.calls.count { it == older })
        assertFalse("nor is it admitted any more", authority.stillAdmitted(older))
    }

    @Test
    fun onePressOfSaveMakesExactlyOneTransportCall() {
        val authority = pairedController()
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val values = SettingsFormValues("NO1", vat = true, tax = true, taxFigure = 7.0, transfer = true, transferFigure = 3.0)

        val save = pressSave(authority, values, transport)

        assertEquals("exactly one request", 1, transport.calls.size)
        assertTrue("and it was the admitted one", save is FormSaveOutcome.Sent)
        val sent = (save as FormSaveOutcome.Sent).decision
        assertEquals(profileId, sent.profileId)
        assertEquals(5, sent.expectedRevision)
        assertEquals("NO1", sent.replacement.areaId)
        // The one document carries all four groups at once.
        assertEquals(HaFiscalValue(enabled = true, value = 7.0), sent.replacement.overrides.first { it.areaId == "NO1" }.tax)
        assertEquals(HaFiscalValue(enabled = true, value = 3.0), sent.replacement.overrides.first { it.areaId == "NO1" }.transfer)
    }

    @Test
    fun aSuccessfulMultiFieldSaveAdvancesTheRevisionExactlyOnce() {
        val authority = pairedController()
        val committed = record.copy(revision = 6, areaId = "NO1")
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(committed) }

        val save = pressSave(
            authority,
            SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null),
            transport
        ) as FormSaveOutcome.Sent

        assertEquals(1, transport.calls.size)
        assertEquals(committed, save.outcome.let { (it as WriteOutcome.Applied).authority.remoteSettings })
        assertEquals(
            "the revision advanced once, and the next edit is sent at it",
            6,
            (authority.authority as VisibleAuthority.AutoRemote).revision
        )
        // And a screen built again from the cache shows exactly what the charger confirmed.
        val rebuilt = pairedController().authority as VisibleAuthority.AutoRemote
        assertEquals(committed, rebuilt.remoteSettings)
    }

    @Test
    fun oneSaveOfVatWritesItEvenWhenTheRevisionMovedMeanwhile() {
        // Home Assistant moves the revision on its own (a pause stored, a car's target taken over), so the
        // record this screen shows is often one behind: the first Save meets a conflict.
        val authority = pairedController()
        val server = record.copy(revision = 9, amps = 8)
        val vatOn = { fresh: HaPlanningSettings -> SettingsFormValues("SE4", vat = false, tax = true, taxFigure = 0.0, transfer = true, transferFigure = 5.5).let {
            assertEquals("the edit is built on the server's record", 9, fresh.revision)
            it
        } }
        val committed = server.copy(revision = 10)
        var answers = listOf<SettingsUpdate.Outcome>(SettingsUpdate.Outcome.Conflict(server), SettingsUpdate.Outcome.Updated(committed))
        val transport = RecordingTransport { answers.first().also { answers = answers.drop(1) } }
        val values = SettingsFormValues("SE4", vat = false, tax = true, taxFigure = 0.0, transfer = true, transferFigure = 5.5)

        val save = SettingsFormSession.save(authority, values, replay = vatOn) { SettingsSaveTarget { decision -> transport.call(decision) } }
            as FormSaveOutcome.Sent

        assertEquals("the one value is sent once more, on the server's record", 2, transport.calls.size)
        val again = transport.calls[1]
        assertEquals(9, again.expectedRevision)
        assertEquals("what changed elsewhere is kept", 8, again.replacement.amps)
        assertEquals(HaFiscalValue.OFF, again.replacement.overrides.first { it.areaId == "SE4" }.vat)
        assertEquals("and the row shows what was written", committed, (save.outcome as WriteOutcome.Applied).authority.remoteSettings)
    }

    @Test
    fun aSecondConflictInARowShowsTheServersRecordAndSendsNoMore() {
        val authority = pairedController()
        val server = record.copy(revision = 9, amps = 8)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Conflict(server.copy(revision = 9 + it.expectedRevision - 4)) }
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)

        val save = SettingsFormSession.save(authority, values, replay = { values }) { SettingsSaveTarget { decision -> transport.call(decision) } }
            as FormSaveOutcome.Sent

        assertEquals(2, transport.calls.size)
        assertTrue(save.answer is SettingsUpdate.Outcome.Conflict)
        assertEquals(
            "the server's record is what is shown",
            (save.answer as SettingsUpdate.Outcome.Conflict).current,
            (save.outcome as WriteOutcome.Applied).authority.remoteSettings
        )
    }

    @Test
    fun anUnpairedSaveStaysLocalAndSendsNothing() {
        val values = SettingsFormValues("NO1", vat = true, tax = true, taxFigure = 8.0, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }
        val unpaired = controller(profile = null)

        // Nothing Home Assistant owns applies.
        assertEquals(SettingsSave.LocalOnly, unpaired.admitFormSave(values))
        assertEquals(FormSaveOutcome.NotSent(SettingsSave.LocalOnly), pressSave(unpaired, values, transport))
        assertEquals(0, transport.calls.size)

        // A paired charger that cannot be checked is *not* the same decision.
        val unchecked = controller()
        unchecked.apply(SettingsAuthorityCoordinator.Outcome.Unreachable(null))
        assertEquals(SettingsSave.ReadOnly, unchecked.admitFormSave(values))
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun everyNotWritablePairedStateWritesNowhere() {
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 6)) }

        // Offline, reached the way this screen can reach it.
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record))
        val authority = pairedController()
        assertTrue("a confirmed record is writable", authority.authority!!.writable)
        val admitted = authority.admitFormSave(values) as SettingsSave.Send
        authority.onWriteAnswer(
            WriteSubject(profileId, admitted.operation, admitted.expectedRevision),
            SettingsUpdate.Outcome.Unavailable
        )
        assertEquals(VisibleAuthority.ReadOnlyOffline(record), authority.authority)
        assertEquals(SettingsSave.ReadOnly, authority.admitFormSave(values))
        assertEquals(FormSaveOutcome.NotSent(SettingsSave.ReadOnly), pressSave(authority, values, transport))
        assertEquals("no request for a screen that cannot check its revision", 0, transport.calls.size)

        // Every one of the other not-writable states the resolver can produce, reached through the
        // same entry point the screen's authority uses.
        listOf(
            SettingsAuthorityCoordinator.Outcome.Unreachable(record),
            SettingsAuthorityCoordinator.Outcome.Unreachable(null)
        ).forEach { outcome ->
            val controller = controller()
            controller.apply(outcome)
            val state = controller.authority!!
            assertFalse("$state must not be writable", state.writable)
            assertEquals("$state must not write", SettingsSave.ReadOnly, controller.admitFormSave(values))
        }

        // An incomplete record is the one settled exception, and it is the accepted rule.
        val incomplete = controller()
        incomplete.apply(
            SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(
                record, HaPlanningInputs.Incomplete(listOf(HaPlanningInputs.Reason.AMPS))
            )
        )
        assertTrue("an incomplete record may be edited", incomplete.authority!!.writable)
        assertTrue(incomplete.admitFormSave(values) is SettingsSave.Send)
    }

    @Test
    fun theContractCarriesNoPresentationFieldSoItCannotBecomeAPairedValue() {
        // The two local-only settings this screen keeps have no home in the contract at all.
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val replacement = (PairedSettingsForm.replacement(record, values, catalogue) as HaSettingsEditResult.Ready).settings
        val keys = HaSettingsCodec.encodeBody(replacement).keys().asSequence().toSet()
        assertEquals(HaSettingsCodec.encodeBody(record).keys().asSequence().toSet(), keys)
        assertFalse(keys.contains("interval_minutes"))
        assertFalse(keys.contains("show_charging_plan"))
    }

    @Test
    fun anAdmittedSaveIsAbandonedWhenTheOwnerTheScreenOrTheRevisionMoves() {
        val authority = pairedController()
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)
        val admitted = authority.admitFormSave(values) as SettingsSave.Send
        assertTrue("a fresh admission may be sent", authority.stillAdmitted(admitted))

        // Another owner, another screen pass, another revision.
        assertFalse("another profile is another owner", authority.stillAdmitted(admitted.copy(profileId = otherId)))
        assertFalse("another screen pass is another subject", authority.stillAdmitted(admitted.copy(screenGeneration = 2)))
        assertFalse("a newer displayed revision is another subject", authority.stillAdmitted(admitted.copy(expectedRevision = 6)))

        // The displayed record moved on and the charger could not be reached.
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record.copy(revision = 6)))
        authority.seedFromConfirmedRecord()
        assertFalse("the revision it was built on is no longer the displayed one", authority.stillAdmitted(admitted))
        authority.onWriteAnswer(WriteSubject(profileId, 99L, 6), SettingsUpdate.Outcome.Unavailable)
        assertFalse("and an unreachable charger abandons it too", authority.stillAdmitted(admitted))

        // An unpaired screen admits a local save instead, which is the only path that may write the
        // widget's own paired fields -- and the one that never makes a request.
        val transport = RecordingTransport { SettingsUpdate.Outcome.Updated(record.copy(revision = 7)) }
        assertTrue(controller(profile = null).admitFormSave(values) is SettingsSave.LocalOnly)
        assertEquals("nothing was sent", 0, transport.calls.size)
    }

    @Test
    fun anInFlightAnswerNeverLowersTheVisibleRevision() {
        val authority = pairedController()
        val values = SettingsFormValues("NO1", vat = true, tax = false, taxFigure = null, transfer = false, transferFigure = null)

        val transport = RecordingTransport {
            cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record.copy(revision = 8)))
            authority.seedFromConfirmedRecord()
            SettingsUpdate.Outcome.Updated(record.copy(revision = 6))
        }
        val save = pressSave(authority, values, transport) as FormSaveOutcome.Sent

        assertEquals(1, transport.calls.size)
        // The answer was folded, and what stands is what the *cache* says is current.
        val applied = save.outcome as WriteOutcome.Applied
        assertEquals(
            "the visible revision is the newer one",
            8,
            (applied.authority as VisibleAuthority.AutoRemote).revision
        )
        assertEquals(
            "and the answer's own older revision is not rendered",
            8,
            (authority.authority as VisibleAuthority.AutoRemote).revision
        )
    }
}
