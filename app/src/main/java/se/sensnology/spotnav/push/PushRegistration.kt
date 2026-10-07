package se.sensnology.spotnav.push

/**
 * Instant notifications, decided away from Android: turning them on (a push token, the relay's
 * reference for it, then every paired charger's Home Assistant told the reference and this phone's
 * chosen events), keeping Home Assistant up to date when the token, the events or the paired
 * chargers change, and turning them off again. Every step blocks: never on the main thread.
 *
 * What each Home Assistant should hold is the reference and the chosen events while instant
 * notifications *and* this phone's own notifications are on, and nothing otherwise: a wake-up only
 * runs this phone's own check.
 */
internal class PushRegistration(
    private val store: PushStore,
    private val tokens: Tokens,
    private val relay: (String) -> PushRelay.Outcome,
    private val homeAssistant: (localId: String, pushRef: String?, events: List<String>) -> Boolean,
    private val local: () -> Local
) {
    interface Tokens {
        fun token(): String?
        fun delete()
    }

    /**
     * This phone's own notifications: on or off, the paired profiles, the chosen events (wire ids), and
     * the profiles whose Home Assistant identifies cars: only those are given `vehicle_identify`, which
     * one without identification refuses (and with it the whole registration).
     */
    data class Local(
        val enabled: Boolean,
        val profiles: List<String>,
        val events: List<String>,
        val identifying: Set<String> = emptySet()
    ) {
        fun eventsFor(localId: String): List<String> =
            if (localId in identifying) events else events.filter { it != IDENTIFY_EVENT }
    }

    /** What one profile's Home Assistant was given. */
    data class Sent(val ref: String, val events: List<String>)

    enum class Result { ON, OFF, NO_TOKEN, SERVER_OFF, RATE_LIMITED, FAILED }

    private companion object {
        const val IDENTIFY_EVENT = "vehicle_identify"
    }

    /** Turn instant notifications on. Anything but [Result.ON] leaves them off. */
    @Synchronized
    fun enable(): Result {
        val token = runCatching { tokens.token() }.getOrNull()
        if (token.isNullOrBlank()) return off(Result.NO_TOKEN)
        return when (val outcome = relay(token)) {
            is PushRelay.Outcome.Registered -> {
                store.enabled = true
                store.pushRef = outcome.pushRef
                sync()
                Result.ON
            }
            PushRelay.Outcome.Disabled -> off(Result.SERVER_OFF)
            PushRelay.Outcome.RateLimited -> off(Result.RATE_LIMITED)
            PushRelay.Outcome.Failed -> off(Result.FAILED)
        }
    }

    private fun off(result: Result): Result {
        store.enabled = false
        store.pushRef = null
        runCatching { tokens.delete() }
        sync()
        return result
    }

    /** A new push token: registered again, and every Home Assistant given the new reference. */
    @Synchronized
    fun onNewToken(token: String) {
        if (!store.enabled || token.isBlank()) return
        val outcome = relay(token)
        if (outcome is PushRelay.Outcome.Registered) {
            store.pushRef = outcome.pushRef
            sync()
        }
    }

    /** Turn instant notifications off: every Home Assistant told to forget, and the token deleted. */
    @Synchronized
    fun disable() {
        store.enabled = false
        store.pushRef = null
        sync()
        // A Home Assistant that could not be reached keeps a reference that no longer leads here:
        // the relay answers it "unknown" and it drops the reference itself.
        runCatching { tokens.delete() }
    }

    /** Tell every Home Assistant whose state differs from what it should hold. Never throws. */
    @Synchronized
    fun sync() {
        val local = local()
        val ref = store.pushRef?.takeIf { store.enabled && local.enabled }
        // Profiles unpaired since: Home Assistant forgot the reference when the pairing went.
        (store.known - local.profiles.toSet()).forEach { store.remember(it, null) }
        for (localId in local.profiles) {
            val wanted = ref?.let { Sent(it, local.eventsFor(localId)) }
            if (store.sent(localId) == wanted) continue
            val accepted = runCatching { homeAssistant(localId, wanted?.ref, wanted?.events.orEmpty()) }.getOrDefault(false)
            if (accepted) store.remember(localId, wanted)
        }
        store.known = local.profiles.filter { store.sent(it) != null }.toSet()
    }
}
