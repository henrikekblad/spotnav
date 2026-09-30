package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.ha.dashboard.StatusCharger

/** How the chargers this app holds line up with the chargers an instance reports. */
object ChargerReconciliation {
    enum class State {
        /** Reported by Home Assistant under the name this app already knows. */
        IN_SYNC,

        /**
         * Reported under a different name -- which includes being reported for the first time to a
         * profile that never had a name from Home Assistant. Either way the fix is the same field.
         */
        RENAMED,

        /** The app holds it, Home Assistant does not report it: stale, not deleted. */
        GONE,

        /**
         * Nothing can be said: the profile belongs to another instance, or it has no
         * `remoteChargerId` to match on.
         */
        UNKNOWN
    }

    data class Verdict(val profile: ChargerProfile, val state: State)

    data class Report(
        val verdicts: List<Verdict>,
        /**
         * Chargers Home Assistant reports that this app holds no webhook for. Shown, not hidden,
         * and the reason to pair again.
         */
        val unprovisioned: List<StatusCharger>,
        /**
         * The profiles to write back: a [State.RENAMED] charger with its new `remoteChargerName`,
         * and nothing else changed -- local id, base url, webhook, display name, selected vehicle
         * and target are carried through.
         */
        val updates: List<ChargerProfile>
    ) {
        val stale: List<ChargerProfile>
            get() = verdicts.filter { it.state == State.GONE }.map { it.profile }
    }

    /** What [chargers] -- as reported by the instance at [baseUrl] -- means for [profiles]. */
    fun reconcile(
        profiles: List<ChargerProfile>,
        baseUrl: String,
        chargers: List<StatusCharger>
    ): Report {
        val mine = profiles.filter { it.baseUrl == baseUrl }
        val byChargerId = chargers.associateBy { it.id }
        val updates = mutableListOf<ChargerProfile>()
        val verdicts = mine.map { profile ->
            val inHa = profile.remoteChargerId?.let { byChargerId[it] }
            val state = when {
                // Nothing to match on: this profile predates any dashboard that named its charger,
                // and guessing would be worse than silence.
                profile.remoteChargerId == null -> State.UNKNOWN
                inHa == null -> State.GONE
                inHa.name == profile.remoteChargerName -> State.IN_SYNC
                else -> State.RENAMED
            }
            if (state == State.RENAMED) updates += profile.copy(remoteChargerName = inHa?.name)
            Verdict(profile, state)
        }

        val held = mine.mapNotNull { it.remoteChargerId }.toSet()
        return Report(
            verdicts = verdicts,
            unprovisioned = chargers.filterNot { it.id in held },
            updates = updates
        )
    }
}
