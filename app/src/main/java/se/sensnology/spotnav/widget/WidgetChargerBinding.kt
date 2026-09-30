package se.sensnology.spotnav.widget

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore

/** One widget's stored charger binding; [initialized] tells "never bound" apart from "decided, no charger". */
internal data class WidgetChargerBinding(val initialized: Boolean, val chargerProfileId: String?)

/**
 * Persists which [ChargerProfile.localId] (if any) each app-widget is bound to, in the `widget_settings`
 * preferences file.
 *
 * One string per widget id (`"<appWidgetId>.chargerBinding"`) so a single write or removal is always
 * complete: absent = never decided, `"none"` = decided, no charger, `"profile:<localId>"` = bound.
 *
 * A decided binding never follows the active profile afterwards. It is decided by [setBinding], or by
 * the one-time lazy default in [binding] (bind to the active profile at that moment, if any).
 *
 * Concurrency: [forContext] hands out fresh instances, so all instances share one process-wide [lock].
 * [binding] does check, evaluate the candidate outside the lock (it may take [ChargerProfileStore]'s
 * lock; never nest them), then re-check before writing. [removalGeneration] lets that re-check tell
 * "never decided" from "just removed", so a completed [removeBinding] is never undone by an in-flight
 * [binding]. It has one entry per removed widget id, so it is left unpruned.
 */
internal class WidgetChargerBindingStore(private val store: KeyValueStore) {

    /**
     * Returns this widget's binding, persisting the one-time lazy default if it was never decided.
     * [currentActiveProfileId] runs outside [lock] and only when needed; the candidate is discarded if
     * another caller decided first or [removeBinding] ran meanwhile.
     */
    fun binding(appWidgetId: Int, currentActiveProfileId: () -> String?): WidgetChargerBinding {
        val (firstCheck, generationAtRead) = synchronized(lock) {
            readBinding(appWidgetId) to generationOf(appWidgetId)
        }
        if (firstCheck.initialized) return firstCheck

        val candidateProfileId = currentActiveProfileId()

        return synchronized(lock) {
            val recheck = readBinding(appWidgetId)
            when {
                recheck.initialized -> recheck
                generationOf(appWidgetId) != generationAtRead ->
                    // Removed while the candidate was being evaluated: stay undecided.
                    WidgetChargerBinding(initialized = false, chargerProfileId = null)
                else -> {
                    writeBinding(appWidgetId, candidateProfileId)
                    WidgetChargerBinding(initialized = true, chargerProfileId = candidateProfileId)
                }
            }
        }
    }

    /**
     * The binding exactly as stored, with no lazy default. For a caller about to decide the binding
     * itself: reading through [binding] would bind to the active profile as a side effect.
     */
    fun storedBinding(appWidgetId: Int): WidgetChargerBinding = synchronized(lock) { readBinding(appWidgetId) }

    /** Explicitly sets (and marks decided) this widget's binding — `null` means "No charger", not "undecided". */
    fun setBinding(appWidgetId: Int, chargerProfileId: String?) {
        synchronized(lock) { writeBinding(appWidgetId, chargerProfileId) }
    }

    /** Removes only this widget's binding and bumps its removal generation (see the class doc). */
    fun removeBinding(appWidgetId: Int) {
        synchronized(lock) {
            store.remove(key(appWidgetId))
            removalGeneration[appWidgetId] = generationOf(appWidgetId) + 1
        }
    }

    private fun readBinding(appWidgetId: Int): WidgetChargerBinding = decode(store.getString(key(appWidgetId)))

    /** Callers must hold [lock]. */
    private fun writeBinding(appWidgetId: Int, chargerProfileId: String?) {
        store.putString(key(appWidgetId), encode(chargerProfileId))
    }

    private fun key(appWidgetId: Int) = "$appWidgetId.chargerBinding"

    companion object {
        private const val PREFS = "widget_settings"
        private const val NONE_VALUE = "none"
        private const val PROFILE_PREFIX = "profile:"

        private val lock = Any()

        // Guarded by lock.
        private val removalGeneration = mutableMapOf<Int, Int>()

        /** Callers must hold [lock]. */
        private fun generationOf(appWidgetId: Int): Int = removalGeneration.getOrDefault(appWidgetId, 0)

        private fun encode(chargerProfileId: String?): String =
            if (chargerProfileId != null) "$PROFILE_PREFIX$chargerProfileId" else NONE_VALUE

        /** Strict decoding: anything but `"none"` or `"profile:<non-blank id>"` decodes to "decided, no charger", never a guess. */
        private fun decode(raw: String?): WidgetChargerBinding = when {
            raw == null -> WidgetChargerBinding(initialized = false, chargerProfileId = null)
            raw == NONE_VALUE -> WidgetChargerBinding(initialized = true, chargerProfileId = null)
            raw.startsWith(PROFILE_PREFIX) && raw.length > PROFILE_PREFIX.length ->
                WidgetChargerBinding(initialized = true, chargerProfileId = raw.substring(PROFILE_PREFIX.length))
            else -> WidgetChargerBinding(initialized = true, chargerProfileId = null)
        }

        fun forContext(context: Context): WidgetChargerBindingStore =
            WidgetChargerBindingStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
