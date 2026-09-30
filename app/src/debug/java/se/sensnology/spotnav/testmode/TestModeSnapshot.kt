package se.sensnology.spotnav.testmode

import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.widget.WidgetDashboardStore
import se.sensnology.spotnav.widget.WidgetPlanSnapshotStore

/** What one preference file holds, as `SharedPreferences.getAll()` hands it over. */
typealias TestModeEntries = Map<String, Any?>

/** What a snapshot covers: one entry map per file it will restore. */
typealias TestModeContents = Map<String, TestModeEntries>

/** What a snapshot of the user's real setup consists of, and whether a stored one can be put back. */
object TestModeSnapshot {
    /** The marker whose presence means "a snapshot was taken". */
    const val MARKER = "snapshot"

    /** Every preference file test mode dirties, and therefore covers. */
    val FILES = listOf(
        "charger_profiles",
        "widget_settings",
        "vehicle_capacities",
        "home_assistant_instance",
        ConfirmedSettingsStore.PREFS,
        WidgetPlanSnapshotStore.PREFS,
        WidgetDashboardStore.PREFS
    )

    /** Which files this snapshot captured, so a later [FILES] cannot disown it. */
    const val COVERED = "covered"

    private const val ENTRY_PREFIX = "file."
    private const val EMPTY_PREFIX = "empty."

    /** The snapshot key one [key] of [file] is stored under. */
    fun entryKey(file: String, key: String): String = "$ENTRY_PREFIX$file.$key"

    /** The marker that says [file] was captured while holding nothing. */
    fun emptyKey(file: String): String = "$EMPTY_PREFIX$file"

    /**
     * The flat preference content for [captured]: the marker, one empty marker per file that held
     * nothing, and every entry with its own key and type.
     */
    fun encode(captured: TestModeContents): Map<String, Any?> = buildMap {
        put(MARKER, true)
        // What this snapshot claims to cover, recorded rather than inferred from [FILES].
        put(COVERED, captured.keys.toSet())
        captured.forEach { (file, entries) ->
            if (entries.isEmpty()) put(emptyKey(file), true)
            entries.forEach { (key, value) -> put(entryKey(file, key), value) }
        }
    }

    /**
     * What a stored snapshot means: the entries to write back, one map per file, with exactly the
     * files it is safe to replace. `null` when this is not a snapshot, or not a complete one.
     */
    fun decode(snapshot: Map<String, Any?>): TestModeContents? =
        if (snapshot[MARKER] == true &&
            snapshot.keys.any { it.startsWith(ENTRY_PREFIX) || it.startsWith(EMPTY_PREFIX) }
        ) decodeFiles(snapshot) else null

    /** One entry map per file the snapshot says it covered. */
    private fun decodeFiles(snapshot: Map<String, Any?>): TestModeContents? {
        val claimed = (snapshot[COVERED] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: return null
        val contents = mutableMapOf<String, TestModeEntries>()
        for (file in claimed) {
            if (file !in FILES) continue
            val prefix = entryKey(file, "")
            val entries = snapshot
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { (key, _) -> key.removePrefix(prefix) }
            val empty = snapshot[emptyKey(file)] == true
            if (entries.isEmpty() && !empty) return null
            contents[file] = entries
        }
        return contents
    }
}
