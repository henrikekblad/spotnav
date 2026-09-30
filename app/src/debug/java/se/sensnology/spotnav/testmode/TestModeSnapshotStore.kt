package se.sensnology.spotnav.testmode

import android.content.Context
import android.content.SharedPreferences

/**
 * The copy of the user's real setup while test mode is on — **every file it dirties, moved as
 * one**.
 */
internal class TestModeSnapshotStore(private val context: Context) {
    /**
     * Whether test mode is on: the real setup is set aside in here, **in any layout this app has
     * ever written**.
     */
    fun exists(): Boolean = TestModeSnapshot.decode(prefs().all) != null

    /** Put every covered file aside. */
    fun snapshot(): Boolean {
        if (prefs().all.isNotEmpty()) return false
        val captured = TestModeSnapshot.FILES.associateWith { file -> filePrefs(file).all }
        prefs().edit().apply {
            clear()
            TestModeSnapshot.encode(captured).forEach { (key, value) -> putTyped(key, value) }
        }.apply()
        return true
    }

    /**
     * Put the real setup back and leave test mode, file by file, exactly as each was captured. A
     * file that was captured empty is cleared again, which is what a fresh install entering test
     * mode first looks like.
     */
    fun restore(): Boolean {
        val contents = TestModeSnapshot.decode(prefs().all) ?: return false
        contents.forEach { (file, entries) ->
            filePrefs(file).edit().apply {
                clear()
                entries.forEach { (key, value) -> putTyped(key, value) }
            }.apply()
        }
        prefs().edit().clear().apply()
        return true
    }

    /**
     * A value back into preferences as the type it was read as. The set of types
     * `SharedPreferences` can hold is closed, so there is nothing here to skip: every value that
     * can be stored can be restored.
     */
    private fun SharedPreferences.Editor.putTyped(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        }
    }

    /**
     * A preference file by its own name, unchanged since it was written: this class deliberately
     * does not ask the stores for them, because the point is to move their bytes rather than to
     * reinterpret them.
     */
    private fun filePrefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "test_mode_snapshot"

        fun forContext(context: Context) = TestModeSnapshotStore(context.applicationContext)
    }
}
