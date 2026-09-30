package se.sensnology.spotnav.ha.pairing

import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.widget.WidgetChargerBinding
import se.sensnology.spotnav.widget.WidgetChargerBindingStore
import se.sensnology.spotnav.widget.WidgetChargerResolver

/**
 * What one successful pairing approval means for this device: the profiles it provisions, and
 * whether the widget that asked for the pairing should be bound to one of them.
 */
internal object ChargerPairingApproval {
    /** What should happen to the pairing widget's binding. */
    sealed interface Binding {
        /**
         * Nothing changes. Either this widget has a valid binding to keep, or the approval returned
         * nothing unambiguous to bind.
         */
        data object Unchanged : Binding

        /**
         * Bind this local profile id to the widget that asked. Always a [ChargerProfile.localId] —
         * never a remote charger id and never a webhook id, which lives on the profile and not in a
         * binding.
         */
        data class Bind(val localId: String) : Binding
    }

    /**
     * The approval's effect: the profiles to write, the local id a fresh installation should
     * activate, and the binding decision.
     */
    data class Applied(
        val profiles: List<ChargerProfile>,
        /** The first charger the approval returned: what a device with nothing active yet activates. */
        val firstLocalId: String?,
        val binding: Binding,
        /**
         * The profiles this approval *re-paired*: the device already held them, and the instance
         * has just vouched for them again.
         */
        val rePaired: List<String> = emptyList()
    )

    /**
     * The approval's effect on [existing], or `null` when this was not a successful approval at
     * all.
     */
    fun apply(
        outcome: PairingStop,
        existing: List<ChargerProfile>,
        baseUrl: String,
        storedBinding: WidgetChargerBinding,
        newLocalId: () -> String
    ): Applied? {
        if (outcome !is PairingStop.Approved) return null
        val profiles = mutableListOf<ChargerProfile>()
        val returnedLocalIds = mutableListOf<String>()
        val rePaired = mutableListOf<String>()
        outcome.chargers.forEach { charger ->
            val held = existing.firstOrNull { it.baseUrl == baseUrl && it.remoteChargerId == charger.id }
            val profile = if (held == null) {
                ChargerProfile(
                    localId = newLocalId(),
                    // Not a name anyone typed here, and deliberately so: the label then reads
                    // `remoteChargerName`, which follows a rename in Home Assistant on every poll.
                    displayName = "",
                    baseUrl = baseUrl,
                    webhookId = charger.webhookId,
                    remoteChargerId = charger.id,
                    remoteChargerName = charger.name
                )
            } else {
                rePaired += held.localId
                held.copy(
                    baseUrl = baseUrl,
                    webhookId = charger.webhookId,
                    remoteChargerId = charger.id,
                    remoteChargerName = charger.name
                )
            }
            returnedLocalIds += profile.localId
            profiles += profile
        }
        return Applied(
            profiles = profiles,
            firstLocalId = returnedLocalIds.firstOrNull(),
            rePaired = rePaired,
            binding = decide(
                returnedLocalIds = returnedLocalIds,
                storedBinding = storedBinding,
                // Everything valid *after* this approval: the profiles the device already held,
                // plus the ones it is about to hold.
                validLocalIds = (existing.map { it.localId } + returnedLocalIds).toSet()
            )
        )
    }

    /**
     * Given a successful approval's returned local ids and a widget's binding as stored, what that
     * widget's binding should be.
     */
    fun decide(
        returnedLocalIds: List<String>,
        storedBinding: WidgetChargerBinding,
        validLocalIds: Set<String>
    ): Binding {
        val held = storedBinding.chargerProfileId
        if (held != null && held in validLocalIds) return Binding.Unchanged
        return if (returnedLocalIds.size == 1) Binding.Bind(returnedLocalIds.single()) else Binding.Unchanged
    }
    /**
     * The four things an approval writes, as one interface, so that the **order** it writes them in
     * is testable rather than only visible.
     */
    interface Device {
        fun forgetCached(localId: String)

        /** Insert or replace one profile. */
        fun upsert(profile: ChargerProfile)

        /** The app-wide active profile, read: a device that already has one keeps it. */
        fun activeProfileId(): String?

        /** Make this local profile the app-wide active one. */
        fun activate(localId: String)

        /** Bind this local profile id to the widget that asked for the pairing. */
        fun bind(localId: String)

        /** Repaint the screen. Every write above must already have happened. */
        fun repaint()
    }

    fun commit(applied: Applied?, device: Device) {
        if (applied == null) return
        applied.rePaired.forEach { device.forgetCached(it) }
        applied.profiles.forEach { device.upsert(it) }
        val active = device.activeProfileId()
        if (active == null && applied.firstLocalId != null) device.activate(applied.firstLocalId)
        (applied.binding as? Binding.Bind)?.let { device.bind(it.localId) }
        device.repaint()
    }
}
