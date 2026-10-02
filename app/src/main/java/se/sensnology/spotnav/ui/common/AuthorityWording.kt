package se.sensnology.spotnav.ui.common

import android.content.Context
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.authority.HaPlanningInputs
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.settings.SettingsSaveTarget
import se.sensnology.spotnav.ha.settings.SettingsUpdate

/** One stable, user-actionable sentence per missing field. */
internal fun ViewScope.authorityReasonText(reason: HaPlanningInputs.Reason): String = when (reason) {
    HaPlanningInputs.Reason.AREA_UNKNOWN -> t(R.string.authority_reason_area)
    HaPlanningInputs.Reason.PHASES -> t(R.string.authority_reason_phases)
    HaPlanningInputs.Reason.AMPS -> t(R.string.authority_reason_amps)
    HaPlanningInputs.Reason.FISCAL_VAT -> t(R.string.authority_reason_vat)
    HaPlanningInputs.Reason.FISCAL_TAX -> t(R.string.authority_reason_tax)
    HaPlanningInputs.Reason.FISCAL_TRANSFER -> t(R.string.authority_reason_transfer)
    HaPlanningInputs.Reason.DEPARTURE -> t(R.string.authority_reason_departure)
    HaPlanningInputs.Reason.TARGET -> t(R.string.authority_reason_target)
    HaPlanningInputs.Reason.PRESENTATION -> t(R.string.authority_reason_presentation)
    HaPlanningInputs.Reason.REJECTED -> t(R.string.authority_reason_rejected)
}

/** A refusal, as a sentence -- never a code and never an exception's prose. */
internal fun ViewScope.authorityRefusalText(outcome: SettingsUpdate.Outcome): String = when (outcome) {
    is SettingsUpdate.Outcome.Invalid ->
        if (outcome.code == "invalid_departure") t(R.string.authority_refused_departure) else t(R.string.authority_refused_invalid)
    is SettingsUpdate.Outcome.NotCommitted -> t(R.string.authority_refused_not_committed)
    SettingsUpdate.Outcome.Unavailable -> t(R.string.authority_unreachable)
    else -> t(R.string.authority_refused_unreadable)
}

/**
 * The one owner an admitted Save is sent through: resolved from the Save's own profile id,
 * immediately before the request, and validated against it.
 */
internal fun settingsSaveTarget(context: Context, profileId: String): SettingsSaveTarget? =
    ChargerProfileStore.forContext(context.applicationContext)
        .getProfile(profileId)
        ?.takeIf { it.localId == profileId }
        ?.let { owner ->
            SettingsSaveTarget { decision ->
                HomeAssistantClient.updateSettings(
                    owner.toHomeAssistantSettings(), decision.expectedRevision, decision.replacement
                )
            }
        }

/** The screens' one mapping from an authority state to a sentence. */
internal fun ViewScope.authorityStateNote(state: VisibleAuthority): String? = when (state) {
    is VisibleAuthority.LocalOwner -> null
    is VisibleAuthority.AutoRemote -> null
    is VisibleAuthority.Incomplete -> t(
        R.string.authority_incomplete,
        state.reasons.joinToString(", ") { authorityReasonText(it) }
    )
    is VisibleAuthority.ReadOnlyOffline -> if (state.answeredWithoutRecord) {
        t(R.string.authority_no_settings)
    } else {
        state.lastConfirmed?.let { t(R.string.authority_offline, it.revision) }
            ?: t(R.string.authority_offline_none)
    }
}
