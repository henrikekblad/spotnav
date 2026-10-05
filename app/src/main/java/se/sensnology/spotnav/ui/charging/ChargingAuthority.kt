package se.sensnology.spotnav.ui.charging

import android.view.View
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.authority.AuthorityRefresh
import se.sensnology.spotnav.ha.authority.CommitRoute
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.authority.WriteSubject
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.ui.common.authorityRefusalText
import se.sensnology.spotnav.ui.common.authorityStateNote

// The authority applied to the charging screen: which values are the record's, what may be edited,
// and how a write is routed and answered.

/** The exact confirmed values, written over what the controls' own steps put in their labels. */
internal fun ChargingScreen.showExactValues(record: HaPlanningSettings) {
    // Under "Fill" the label is the word, not the amount the record keeps beside it.
    if (!energy.filling()) energy.valueLabel.text = kwhText(record.requestedKwh)
    record.target.targetPercent?.let { targetSoc.valueLabel.text = t(R.string.percent_value_decimal, it) }
}

/** Show the record's own values in the existing controls. */
internal fun ChargingScreen.showRecordInControls(record: HaPlanningSettings) {
    authorityApplying = true
    try {
        // A field the record states nothing for is shown as "not set", never as this phone's own
        // default: the controls stay out of any edit until the person sets them.
        record.phases?.let { connection.setPhases(it) } ?: connection.setPhasesNotSet()
        connection.setAmpsNotSet(record.amps == null)
        if (record.amps != null) {
            // Never clamp a stored value: the slider spans the charger's own range and is widened
            // to hold a value outside it, so the app never rewrites amps it did not touch.
            connection.setAmps(record.amps, connection.minAmps(), connection.maxAmps())
        }
        connection.refreshValueLabel()
        chargerCard.refreshPhasesRow()
        energy.setKwh(record.requestedKwh, record.fillToLimit)
        energy.refreshValueLabel()
        planCard.periods.progress = (record.maxPeriods - 1).coerceIn(0, planCard.periods.max)
        planCard.refreshPeriodsLabel()
        planCard.setDriver(
            if (record.driver == HaSettingsDriver.TARGET_SOC) PlanDriver.TARGET_SOC else PlanDriver.KWH
        )
        targetSoc.applyRemote(record.target.targetPercent?.let { it.toInt().coerceIn(0, 100) })
        planCard.useDeparture.isChecked = record.departureEnabled
        val parts = record.departureTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (hour != null && minute != null) planCard.setDeparture(hour, minute)
        planCard.setDepartureDate(record.departureDate)
        planCard.setDepartureWeekdays(record.departureWeekdays)
        syncDepartureDays()
        planCard.refreshDepartureLabel()
        // The exact readings last, and handed back: the labels above were written from the
        // controls' own steps, and the record's decimals are what has to be visible.
        showExactValues(record)
    } finally {
        authorityApplying = false
    }
}

/** The strategy row's own words, and what a screen reader hears for it. */
internal fun ChargingScreen.paintStrategyRow(row: StrategyRow, ui: ChargingStrategyUi) {
    row.value.text = strategyTitleText(ui.strategy)
    row.row.contentDescription = t(
        R.string.strategy_row_description,
        t(R.string.strategy_field_label),
        row.value.text
    )
}

internal fun ChargingScreen.applyStrategy(ui: ChargingStrategyUi) {
    strategyUi = ui
    paintStrategyRow(planCard.strategy, ui)
    planCard.strategy.row.visibility = if (ui.strategyRowVisible) View.VISIBLE else View.GONE
    chargerCard.setPhaseEditor(ui.phaseRowVisible, ui.phaseRadioEnabled && planningControlsEnabled)
}

/** Repaint the strategy row for the one action the charger controls just computed. */
internal fun ChargingScreen.applyPrimaryAction(action: ChargerAction?) {
    if (!strategyWired) return
    if (strategyUi.immediateAction != action) applyStrategy(strategyFace(authority.authority))
}

/** Repaint the row for the control decision the last status stated: */
internal fun ChargingScreen.applyAcceptedControl(control: AutoControl?) {
    if (!strategyWired) return
    if (strategyUi.automaticControl != control?.plannerControl()) {
        applyStrategy(strategyFace(authority.authority))
    }
}

/**
 * Repaint the row for the strategy options the last status stated: a status that changed only which
 * strategies this charger offers still has to move the picker's own gaps and selectability.
 * Unconditional, unlike its two neighbours above:
 */
internal fun ChargingScreen.applyAcceptedStrategyOptions() {
    if (!strategyWired) return
    applyStrategy(strategyFace(authority.authority))
}

/** The one chooser behind the strategy row. */
internal fun ChargingScreen.openStrategyChooser() {
    val ui = strategyUi
    val subject = ChooserSubject(profileId, viewGeneration)
    strategyChooser.opened(subject)
    showStrategyChooser(
        ui = ui,
        onChoose = { chosen ->
            when (val intent = ChargingStrategyPresentation.intent(ui, chosen)) {
                StrategyIntent.NothingToDo -> Unit
                // Not selectable, so a row with a gap cannot be chosen from the dialog -- silent if
                // it is ever reached some other way.
                StrategyIntent.Unavailable -> Unit
                // The honest answer to "change this" while the record cannot be written: a
                // sentence, no write, and no fallback to this widget's own storage.
                StrategyIntent.NotWritable -> {
                    authorityNote = t(R.string.strategy_change_read_only)
                    render()
                }
                // One admitted change:
                is StrategyIntent.Save -> commitEdit(HaSettingsEdit.Strategy(intent.strategy))
            }
        },
        onClosed = {
            strategyChooser.closed()
            planCard.strategy.row.requestFocus()
        }
    )
}

internal fun ChargingScreen.setPlanningControlsEnabled(enabled: Boolean) {
    planningControlsEnabled = enabled
    connection.ampsSeek.isEnabled = enabled
    connection.phases.isEnabled = enabled && strategyUi.phaseRadioEnabled
    consumption.consumption.isEnabled = enabled
    energy.energy.isEnabled = enabled
    planCard.periods.isEnabled = enabled
    planCard.useDeparture.isEnabled = enabled
    planCard.departurePicker.isEnabled = enabled && planCard.useDeparture.isChecked
    planCard.dailyRadio.isEnabled = enabled
    planCard.onDateRadio.isEnabled = enabled
    planCard.refreshDepartureLabel()
    planCard.kwhOption.isEnabled = enabled
    planCard.targetOption.isEnabled = enabled
    targetSoc.slider.isEnabled = enabled
}

/**
 * Apply one authority state to the screen: the note it has to say, the values it owns, what may be
 * edited, and one render.
 */
internal fun ChargingScreen.applyState(state: VisibleAuthority?, note: String? = null) {
    // The strategy presentation for this pass, decided *before* anything is painted or enabled,
    // because it owns three answers the rest of this function would otherwise have to guess:
    applyStrategy(strategyFace(state))
    var text = note ?: state?.let { authorityStateNote(it) }
    // Offline is the banner's fact, said once, at the top of the page: the notes under the cards
    // carry everything else, so the same sentence is not written twice on one screen.
    val offlineSentence = (state as? VisibleAuthority.ReadOnlyOffline)?.let { authorityStateNote(it) }
    offlineNotice.text = offlineSentence
    offlineNotice.visibility = if (offlineSentence == null) View.GONE else View.VISIBLE
    if (offlineSentence != null && note == null) text = null
    syncPaired()
    if (state != null && state.haOwnsPlanning) {
        state.remoteSettings?.let { showRecordInControls(it) }
        setPlanningControlsEnabled(state.pairedControlsEnabled)
    } else {
        setPlanningControlsEnabled(state != null)
    }
    authorityNote = text
    render()
}

/** Restore what is shown and say why this commit wrote nothing. */
internal fun ChargingScreen.readOnlyNote() {
    pendingVehiclePick = null
    syncPaired()
    authority.authority?.remoteSettings?.let { showRecordInControls(it) }
    authority.authority?.let { authorityNote = authorityStateNote(it) }
    render()
}

/** Say that a control built a value the contract refuses, and change nothing. */
internal fun ChargingScreen.refusedNote() {
    pendingVehiclePick = null
    syncPaired()
    authorityNote = t(R.string.authority_refused_invalid)
    render()
}

/** Apply one settings answer this screen is still waiting for. */
internal fun ChargingScreen.applyWriteAnswer(subject: WriteSubject, answer: SettingsUpdate.Outcome, followUp: HaSettingsEdit?) {
    val outcome = authority.onWriteAnswer(subject, answer)
    val applied = outcome as? WriteOutcome.Applied ?: return
    val note = when (answer) {
        is SettingsUpdate.Outcome.Updated -> t(R.string.authority_saved)
        is SettingsUpdate.Outcome.CommittedButReconcileFailed ->
            t(R.string.authority_committed_unreconciled)
        is SettingsUpdate.Outcome.Conflict -> t(R.string.authority_changed_elsewhere)
        else -> authorityRefusalText(answer)
    }
    val previousArea = authority.priceArea()
    // Whatever the answer, the record is what the picker shows again (a saved pick is in it).
    pendingVehiclePick = null
    applyState(applied.authority, note)
    // Exactly one reload, and only when the effective area actually moved.
    if (AuthorityRefresh.areaChangeRequiresLoad(previousArea, authority.priceArea())) loadPrices()
    // `Applied` above is the operation-currency guard:
    if (AuthorityRefresh.settingsChangeNeedsDashboardReload(answer)) startDashboardFetch()
    // A follow-up is an edit the answer changed the *meaning* of (a conflicting target's derived
    // energy), never a replay of the same one.
    if (followUp != null) commitEdit(followUp)
}

/**
 * One settings request in flight, for the operation the controller reserved. A conflict in which only
 * the revision moved (a Start, Stop, pause or resume stored meanwhile) is folded in and the same edit
 * sent once more against the new revision, without a note; any other conflict is said as before.
 */
internal fun ChargingScreen.sendEdit(route: CommitRoute.Send, edit: HaSettingsEdit, retried: Boolean = false) {
    val profile = authorityProfile ?: return
    val subject = WriteSubject(profile.localId, route.operation, route.expectedRevision)
    session?.updateSettings(route.expectedRevision, route.replacement) { answer ->
        if (!retried && HaSettingsEditor.onlyRevisionMoved(route.base, answer) &&
            authority.onWriteAnswer(subject, answer) is WriteOutcome.Applied
        ) {
            val again = authority.beginWrite(edit)
            if (again is CommitRoute.Send) {
                sendEdit(again, edit, retried = true)
                return@updateSettings
            }
            // Nothing may be written now after all: show the record as it stands.
            authority.authority?.let { applyState(it, t(R.string.authority_changed_elsewhere)) }
            return@updateSettings
        }
        applyWriteAnswer(subject, answer, HaSettingsEditor.nextEdit(edit, answer))
    }
}

/** One deliberate edit, routed by the controller. */
internal fun ChargingScreen.commitEdit(edit: HaSettingsEdit) {
    when (val route = authority.beginWrite(edit)) {
        CommitRoute.LocalSave -> {
            saveCharging(currentSettings())
            render()
        }
        is CommitRoute.Send -> sendEdit(route, edit)
        CommitRoute.ReadOnly -> { readOnlyNote(); Unit }
        is CommitRoute.Refused -> refusedNote()
    }
}

/** The target slider's own commit. */
internal fun ChargingScreen.commitTargetPercent() {
    val edit = HaSettingsEdit.Driver(
        HaSettingsDriver.TARGET_SOC, targetIntentFor(HaSettingsDriver.TARGET_SOC)
    )
    when (val route = authority.beginWrite(edit)) {
        CommitRoute.LocalSave -> {
            targetSoc.commit()
            render()
        }
        is CommitRoute.Send -> sendEdit(route, edit)
        CommitRoute.ReadOnly -> readOnlyNote()
        is CommitRoute.Refused -> refusedNote()
    }
}
