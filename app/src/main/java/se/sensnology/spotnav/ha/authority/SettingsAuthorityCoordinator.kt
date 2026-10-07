package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.prices.PriceMarket

/**
 * One charger profile's settings authority, resolved: what the charger's dashboard says the
 * settings are.
 */
internal class SettingsAuthorityCoordinator(
    /** The profile snapshot: read once, at the start, and never again. */
    private val profiles: () -> List<ChargerProfile>,
    /** The catalogue the app is currently using, read at the same moment as the rest. */
    private val catalogue: () -> List<PriceMarket>,
    /** Fetch one profile's scoped dashboard, failing rather than throwing when it cannot be read. */
    private val fetchDashboard: (ChargerProfile) -> Result<Dashboard>,
    /** Where the last server-confirmed record per profile is folded. */
    private val cache: ConfirmedSettingsStore
) {
    /** What one operation concluded -- never prose, a URL, a webhook id, a token or a live reading. */
    sealed interface Outcome {
        /** The named profile does not exist in the captured snapshot. */
        data object ProfileMissing : Outcome

        /** The current revision cannot be checked; a cached record may be shown, no edit is allowed. */
        data class Unreachable(
            val lastConfirmed: HaPlanningSettings?,
            /**
             * Home Assistant answered, and stated no settings record (rather than not answering at
             * all).
             */
            val answeredWithoutRecord: Boolean = false
        ) : Outcome

        /** The charger's own record is the settings. */
        data class RemoteAuthoritative(
            val settings: HaPlanningSettings,
            val adaptation: HaPlanningInputs
        ) : Outcome

        /** A newer operation for this profile has already published; nothing was changed. */
        data object Superseded : Outcome
    }

    /**
     * Where each profile's accepted generation and published result live. One coordinator, one
     * lock.
     */
    private val lock = Any()

    /** The newest generation admitted (reserved) per profile: this is what makes an operation stale. */
    private val accepted = mutableMapOf<String, Long>()

    /** The last outcome this coordinator published per profile -- what a caller renders. */
    private val publishedOutcomes = mutableMapOf<String, Outcome>()

    /** The last result this coordinator published for [localId], or `null` if none ever was. */
    fun published(localId: String): Outcome? = synchronized(lock) { publishedOutcomes[localId] }

    /** Claims [generation] as [localId]'s newest accepted operation, or refuses it. */
    private fun admit(localId: String, generation: Long): Boolean = synchronized(lock) {
        val newest = accepted[localId]
        if (newest != null && generation <= newest) {
            false
        } else {
            accepted[localId] = generation
            true
        }
    }

    /**
     * Folds one dashboard into the cache, but only while [generation] is still the accepted one --
     * as **one** critical section.
     */
    private fun observeIfCurrent(localId: String, generation: Long, dashboard: Dashboard): Boolean =
        synchronized(lock) {
            if (accepted[localId] != generation) {
                false
            } else {
                cache.observeDashboard(localId, dashboard)
                true
            }
        }

    /**
     * Records [outcome] as this profile's visible result, but only while [generation] is still the
     * accepted one -- a late answer cannot overwrite a newer result, and a superseded operation
     * cannot publish at all.
     */
    private fun publish(localId: String, generation: Long, outcome: Outcome): Outcome = synchronized(lock) {
        if (accepted[localId] != generation) {
            Outcome.Superseded
        } else {
            publishedOutcomes[localId] = outcome
            outcome
        }
    }

    /** Resolve the authority for one profile and publish what it means. */
    fun reconcile(localId: String, generation: Long): Outcome {
        if (!admit(localId, generation)) return Outcome.Superseded

        val capturedProfile = profiles().firstOrNull { it.localId == localId }
            ?: return publish(localId, generation, Outcome.ProfileMissing)
        val capturedCatalogue = catalogue()

        val dashboard = fetchDashboard(capturedProfile).getOrNull()
        if (dashboard == null) {
            return publish(localId, generation, Outcome.Unreachable(cache.confirmed(localId)))
        }

        // After the answer, before it is folded into the cache -- and in the same critical section
        // as the fold itself.
        if (!observeIfCurrent(localId, generation, dashboard)) return Outcome.Superseded
        val record = dashboard.settings
            ?: return publish(
                localId,
                generation,
                Outcome.Unreachable(cache.confirmed(localId), answeredWithoutRecord = true)
            )
        return publish(
            localId,
            generation,
            Outcome.RemoteAuthoritative(record, HaPlanningAdapter.of(record, capturedCatalogue))
        )
    }
}
