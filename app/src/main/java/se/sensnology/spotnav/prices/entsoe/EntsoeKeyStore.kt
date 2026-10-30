package se.sensnology.spotnav.prices.entsoe

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.prices.PriceFailure
import se.sensnology.spotnav.prices.PriceMarket
import java.time.Instant

/** What is known about the phone's ENTSO-E key. */
internal enum class KeyState {
    /** No key, or one that no longer opens. */
    NONE,

    /** Saved while ENTSO-E could not be reached: used, and confirmed by the first answer. */
    UNCHECKED,

    /** ENTSO-E answered a request made with it. */
    OK,

    /** ENTSO-E refused it: actionable, the person replaces it. */
    REJECTED
}

/** What checking a key before saving it came to. */
internal enum class KeyCheck {
    /** ENTSO-E answered (a document or "no data"): saved as [KeyState.OK]. */
    ACCEPTED,

    /** ENTSO-E refused it: not saved. */
    REJECTED,

    /** ENTSO-E could not be asked or did not say: saved as [KeyState.UNCHECKED]. */
    UNCHECKED
}

/**
 * The one ENTSO-E key of this phone (relevant only while unpaired: a paired phone gets prices from Home
 * Assistant, which holds its own). Stored sealed by a [SecretBox] in prefs `entsoe_key`, beside its
 * [KeyState]. Never logged, never in a message, shown back only as [masked].
 */
internal class EntsoeKeyStore(private val store: KeyValueStore, private val box: SecretBox) {
    /** The key, or `null` when none is held or the held one does not open. */
    fun key(): EntsoeKey? {
        val sealed = store.getString(KEY_SEALED) ?: return null
        return box.open(sealed)?.let(::EntsoeKey)?.takeUnless { it.isEmpty }
    }

    fun state(): KeyState {
        if (key() == null) return KeyState.NONE
        return store.getString(KEY_STATE)?.let { stored -> KeyState.entries.firstOrNull { it.name == stored } } ?: KeyState.UNCHECKED
    }

    /** Save [key] as [state]; `false` when it could not be sealed (nothing is stored then). */
    fun save(key: EntsoeKey, state: KeyState): Boolean {
        val sealed = box.seal(key.reveal()) ?: return false
        store.putString(KEY_SEALED, sealed)
        store.putString(KEY_STATE, state.name)
        return true
    }

    /**
     * Save [key] after a check: a key ENTSO-E refused is not saved, one that could not be checked is
     * saved as unchecked. The UI's Save (step A8) calls this.
     */
    fun saveChecked(key: EntsoeKey, check: KeyCheck): Boolean = when (check) {
        KeyCheck.REJECTED -> false
        KeyCheck.ACCEPTED -> save(key, KeyState.OK)
        KeyCheck.UNCHECKED -> save(key, KeyState.UNCHECKED)
    }

    /** A later request was refused: the person has something to fix. */
    fun markRejected() {
        if (key() != null) store.putString(KEY_STATE, KeyState.REJECTED.name)
    }

    /** A later request was answered: an unchecked key is now known to work. */
    fun markAccepted() {
        if (key() != null && state() == KeyState.UNCHECKED) store.putString(KEY_STATE, KeyState.OK.name)
    }

    fun remove() {
        store.remove(KEY_SEALED)
        store.remove(KEY_STATE)
    }

    /** The key as it may be shown back: its last four characters, the rest dots. */
    fun masked(): String? = key()?.let { masked(it) }

    companion object {
        internal const val PREFS = "entsoe_key"
        internal const val KEY_SEALED = "sealed"
        internal const val KEY_STATE = "state"

        fun masked(key: EntsoeKey): String = "••••" + key.reveal().takeLast(4)

        fun forContext(context: Context): EntsoeKeyStore =
            EntsoeKeyStore(SharedPreferencesKeyValueStore(context, PREFS), AndroidKeyStoreBox)

        /**
         * Check [key] with one request: today's market day of [area], or of [fallback] (DE-LU) when the
         * area has no ENTSO-E route. A refusal is [KeyCheck.REJECTED]; any answer that shows the key was
         * accepted (a document, "no data", even a document this app refuses) is [KeyCheck.ACCEPTED];
         * anything else is [KeyCheck.UNCHECKED].
         */
        fun check(key: EntsoeKey, area: PriceMarket?, fallback: PriceMarket?, client: EntsoeClient, now: Instant): KeyCheck {
            if (key.isEmpty) return KeyCheck.REJECTED
            val asked = area?.takeIf { it.entsoe != null } ?: fallback?.takeIf { it.entsoe != null } ?: return KeyCheck.UNCHECKED
            val day = now.atZone(asked.marketZoneId).toLocalDate()
            return when (val fetched = client.fetch(key, asked.id, asked.entsoe!!, asked.marketZoneId, day)) {
                is EntsoeFetch.Fetched, EntsoeFetch.Missing -> KeyCheck.ACCEPTED
                is EntsoeFetch.Failed -> when (fetched.failure) {
                    PriceFailure.ENTSOE_KEY_REJECTED -> KeyCheck.REJECTED
                    PriceFailure.INVALID -> KeyCheck.ACCEPTED
                    else -> KeyCheck.UNCHECKED
                }
            }
        }
    }
}
