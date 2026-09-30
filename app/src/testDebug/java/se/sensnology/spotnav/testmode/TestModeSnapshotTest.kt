package se.sensnology.spotnav.testmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore

/** What a snapshot covers, what it looks like on disk, and when it is complete enough to restore. */
class TestModeSnapshotTest {
    private val charger = TestModeSnapshot.FILES[0]
    private val widget = TestModeSnapshot.FILES[1]
    private val vehicles = TestModeSnapshot.FILES[2]

    // Built over TestModeSnapshot.FILES rather than by listing files here: a hand-written list is
    // exactly what went stale when a fourth file was added, and a fixture that cannot notice is
    // worse than no fixture.
    private val captured: Map<String, Map<String, Any?>> =
        TestModeSnapshot.FILES.associateWith { file ->
            when (file) {
                charger -> mapOf("state" to "{\"profiles\":[]}")
                widget -> mapOf("3.chargerBinding" to "profile:abc", "3.chargingKwh" to 20, "3.vat" to true)
                vehicles -> mapOf("capacity:leaf" to "40.0")
                else -> mapOf("base_url" to "http://192.168.1.2:8123")
            }
        }

    @Test fun everyCapturedFileComesBackWithItsKeysAndTypes() {
        val stored = TestModeSnapshot.encode(captured)

        assertEquals(captured, TestModeSnapshot.decode(stored))
    }

    @Test fun theStoredLayoutNamesEachFileAndKey() {
        val stored = TestModeSnapshot.encode(captured)

        assertEquals(true, stored[TestModeSnapshot.MARKER])
        assertEquals("{\"profiles\":[]}", stored["file.charger_profiles.state"])
        assertEquals("profile:abc", stored["file.widget_settings.3.chargerBinding"])
        assertEquals(20, stored["file.widget_settings.3.chargingKwh"])
        assertEquals(true, stored["file.widget_settings.3.vat"])
        assertEquals("40.0", stored["file.vehicle_capacities.capacity:leaf"])
    }

    @Test fun aFileThatHeldNothingIsMarkedAsCapturedEmpty() {
        val stored = TestModeSnapshot.encode(TestModeSnapshot.FILES.associateWith { emptyMap<String, Any?>() })

        // "Empty" is a state worth restoring: it is what a fresh install looks like, and without a
        // marker it is indistinguishable from "not captured".
        assertEquals(true, stored["empty.charger_profiles"])
        assertEquals(true, stored["empty.widget_settings"])
        assertEquals(true, stored["empty.vehicle_capacities"])
        assertEquals(captured.keys.associateWith { emptyMap<String, Any?>() }, TestModeSnapshot.decode(stored))
    }

    @Test fun theEmptyMarkerIsNotAKeyOfTheFileItDescribes() {
        val stored = TestModeSnapshot.encode(
            TestModeSnapshot.FILES.associateWith { if (it == charger) mapOf("state" to "s") else emptyMap() }
        )

        // Otherwise the restore would write a setting called "empty" back.
        assertEquals(emptyMap<String, Any?>(), TestModeSnapshot.decode(stored)!![widget])
    }

    // all or nothing

    @Test fun aSnapshotMissingAFileIsRefused() {
        val complete = TestModeSnapshot.encode(captured)

        for (file in TestModeSnapshot.FILES) {
            val withoutOne = complete.filterKeys { !it.startsWith("file.$file.") && it != "empty.$file" }
            assertNull("missing $file", TestModeSnapshot.decode(withoutOne))
        }
    }

    @Test fun theMarkerAloneIsNotASnapshot() {
        assertNull(TestModeSnapshot.decode(mapOf(TestModeSnapshot.MARKER to true)))
        assertNull(TestModeSnapshot.decode(emptyMap()))
    }

    @Test fun aFileOutsideTheKnownSetIsNotRestored() {
        // Only this app wrote the snapshot, but a preference key is not a capability: an unknown
        // file is ignored, not written.
        val stored = TestModeSnapshot.encode(captured) + mapOf(
            "file.app_language.state" to "sv",
            "empty.app_language" to true
        )

        assertEquals(captured.keys, TestModeSnapshot.decode(stored)!!.keys)
    }

    @Test fun aSnapshotTakenBeforeAFileWasCoveredIsStillComplete() {
        // The upgrade case, and the reason `covered` is recorded at all: a snapshot written when
        // only three files were captured must still restore those three, not be refused because a
        // fourth exists now.
        val older = TestModeSnapshot.FILES.take(3)
        val stored = TestModeSnapshot.encode(captured.filterKeys { it in older })

        val decoded = TestModeSnapshot.decode(stored)

        assertEquals(older.toSet(), decoded!!.keys)
        assertTrue(TestModeSnapshot.FILES.last() !in decoded)
    }

    @Test fun aFileAddedLaterIsLeftAloneRatherThanCleared() {
        // What the device keeps: a file the snapshot never captured is absent from the result, so
        // the store has nothing to clear for it.
        val stored = TestModeSnapshot.encode(captured.filterKeys { it != TestModeSnapshot.FILES.last() })

        assertNull(TestModeSnapshot.decode(stored)!![TestModeSnapshot.FILES.last()])
    }

    @Test fun anUnrelatedKeyIsNotASnapshot() {
        assertNull(TestModeSnapshot.decode(mapOf("something_else" to "x")))
    }

    // the file it writes

    @Test fun theConfirmedSettingsCacheIsCoveredUnderTheNameItsOwnerUses() {
        // One string, owned by the store that writes the file: a snapshot that claimed a different
        // name would restore nothing while looking complete.
        assertTrue(TestModeSnapshot.FILES.contains(ConfirmedSettingsStore.PREFS))

        val contents = mapOf(
            ConfirmedSettingsStore.PREFS to mapOf("confirmed.local-a" to "{\"schema\":1}")
        )
        val stored = TestModeSnapshot.encode(contents)

        assertEquals("{\"schema\":1}", stored["file.spotnav_confirmed_settings.confirmed.local-a"])
        assertEquals(contents, TestModeSnapshot.decode(stored))
    }

    @Test fun aSnapshotFromBeforeTheCacheWasCoveredRestoresSafelyAndLeavesItAlone() {
        // The four-file era, which is what a device upgrading while in test mode still holds: it
        // must restore its four files and must not clear the cache it never captured.
        val older = TestModeSnapshot.FILES.take(4)
        val stored = TestModeSnapshot.encode(captured.filterKeys { it in older })

        val decoded = TestModeSnapshot.decode(stored)!!

        assertEquals(older.toSet(), decoded.keys)
        assertTrue(ConfirmedSettingsStore.PREFS !in decoded)
    }
}
