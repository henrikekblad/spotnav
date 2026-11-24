package se.sensnology.spotnav.notify

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import java.security.SecureRandom
import java.util.Base64

/**
 * This app's secret id (`app_id`, SpotNav 3.0): made once (32 random bytes, base64url without padding), kept in the
 * app's private preferences (never backed up, as the webhook ids), sent with a pairing request and with every
 * `phone_notifications` call, so Home Assistant knows which of its phones this app is. Home Assistant keeps only its
 * hash. Never shown, never logged: nothing here prints it.
 */
internal class AppIdentity(private val store: KeyValueStore, private val random: SecureRandom = SecureRandom()) {
    /** The id, made on the first call. */
    fun appId(): String = synchronized(lock) {
        store.getString(KEY)?.takeIf { VALID.matches(it) } ?: newId(random).also { store.putString(KEY, it) }
    }

    override fun toString(): String = "AppIdentity(<redacted>)"

    companion object {
        private const val PREFS = "spotnav_app_identity"
        private const val KEY = "app_id"
        private const val BYTES = 32
        private val lock = Any()

        /** What Home Assistant accepts as an app id: base64url text of 32 to 128 characters. */
        val VALID = Regex("^[A-Za-z0-9_-]{32,128}$")

        fun newId(random: SecureRandom = SecureRandom()): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(random::nextBytes))

        fun forContext(context: Context): AppIdentity =
            AppIdentity(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
