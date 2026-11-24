package se.sensnology.spotnav.push

import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore

/**
 * Instant notifications, decided away from Android: turning them on (a push token, the relay's
 * reference for it, then every paired Home Assistant told the reference), keeping Home Assistant up to
 * date when the token, the events or the paired chargers change, and turning them off again. Every
 * step blocks: never on the main thread.
 *
 * A Home Assistant that answers `phone_notifications` (SpotNav 3.0) is told the reference once for the
 * installation ([phones]) while instant notifications are on, `null` otherwise: which events wake the
 * phone is Home Assistant's own choice for it. An older one ([Phones.LEGACY]: 2.x, 3.0.0b4) is told
 * per charger with `push_register` as before: the reference and the chosen events while instant
 * notifications *and* this phone's own notifications are on, and nothing otherwise.
 */
internal class PushRegistration(
    private val store: PushStore,
    private val tokens: Tokens,
    private val relay: (String) -> PushRelay.Outcome,
    private val homeAssistant: (localId: String, pushRef: String?, events: List<String>) -> Boolean,
    private val local: () -> Local,
    private val phones: (door: String, pushRef: String?) -> Phones = { _, _ -> Phones.LEGACY }
) {
    /** What telling one Home Assistant the reference through `phone_notifications` came to. */
    enum class Phones {
        ACCEPTED,

        /** A Home Assistant without the action: told per charger with `push_register`. */
        LEGACY,

        /** No answer: asked again at the next sync. */
        FAILED
    }

    /** What one Home Assistant was told through `phone_notifications`: the reference, or `null` (none). */
    data class DoorSent(val ref: String?)

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
        val identifying: Set<String> = emptySet(),
        /** The paired Home Assistants by address, each with its paired profiles (see `PhoneDoor`). */
        val doors: Map<String, List<String>> = emptyMap()
    ) {
        fun eventsFor(localId: String): List<String> =
            if (localId in identifying) events else events.filter { it != IDENTIFY_EVENT }

        companion object {
            /** What [local] says for the paired [profiles]. */
            fun of(local: LocalNotificationStore, profiles: List<String>, doors: Map<String, List<String>> = emptyMap()) = Local(
                enabled = local.enabled,
                profiles = profiles,
                events = NotificationEvent.entries.filter { it in local.events }.map { it.wire },
                identifying = local.identifying(profiles),
                doors = doors
            )
        }
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
        val instant = store.pushRef?.takeIf { store.enabled }
        val ref = instant?.takeIf { local.enabled }
        // Profiles unpaired since: Home Assistant forgot the reference when the pairing went.
        (store.known - local.profiles.toSet()).forEach { store.remember(it, null) }
        (store.doorsTold() - local.doors.keys).forEach { store.rememberDoor(it, null) }
        // Each Home Assistant once through `phone_notifications`; an older one's chargers below, as before.
        val legacy = local.profiles.filter { localId -> local.doors.values.none { localId in it } }.toMutableList()
        for ((door, profiles) in local.doors) {
            val wanted = DoorSent(instant)
            if (store.doorSent(door) == wanted) continue
            when (runCatching { phones(door, instant) }.getOrDefault(Phones.FAILED)) {
                Phones.ACCEPTED -> {
                    store.rememberDoor(door, wanted)
                    // Its chargers' own registrations are Home Assistant's to carry over (it adopted the reference).
                    profiles.forEach { store.remember(it, null) }
                }
                Phones.LEGACY -> {
                    store.rememberDoor(door, null)
                    legacy += profiles
                }
                Phones.FAILED -> Unit
            }
        }
        for (localId in legacy) {
            val wanted = ref?.let { Sent(it, local.eventsFor(localId)) }
            if (store.sent(localId) == wanted) continue
            val accepted = runCatching { homeAssistant(localId, wanted?.ref, wanted?.events.orEmpty()) }.getOrDefault(false)
            if (accepted) store.remember(localId, wanted)
        }
        store.known = local.profiles.filter { store.sent(it) != null }.toSet()
    }
}
