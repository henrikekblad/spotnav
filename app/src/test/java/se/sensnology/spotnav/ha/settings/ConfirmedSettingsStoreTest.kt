package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.SettingsFixtures

class ConfirmedSettingsStoreTest {
    private val warnings = mutableListOf<String>()
    private val raw = FakeKeyValueStore()
    private val profileA = "local-a"
    private val profileB = "local-b"

    private fun store(): ConfirmedSettingsStore = ConfirmedSettingsStore(raw) { message -> warnings.add(message) }

    private fun dashboardWith(record: JSONObject?): Dashboard =
        DashboardFixtures.dashboard { put("settings", record ?: JSONObject.NULL) }

    @Test fun aConfirmedRecordSurvivesAReloadAndProfilesStayIsolated() {
        store().record(profileA, SettingsFixtures.parsed(revision = 1, amps = 10))
        store().record(profileB, SettingsFixtures.parsed(revision = 5, amps = 32, requestedKwh = 30.5))

        // A fresh instance over the same bytes is what a process restart sees.
        val reloaded = store()
        assertEquals(1, reloaded.confirmed(profileA)!!.revision)
        assertEquals(10, reloaded.confirmed(profileA)!!.amps)
        assertEquals(5, reloaded.confirmed(profileB)!!.revision)
        assertEquals(30.5, reloaded.confirmed(profileB)!!.requestedKwh, 0.0)
        assertNull(reloaded.confirmed("local-unknown"))
    }

    @Test fun aDashboardsRecordUpdatesTheCacheAndADashboardWithoutOneDoesNot() {
        val store = store()
        store.record(profileA, SettingsFixtures.parsed(revision = 1, amps = 10))

        val stored = store.observeDashboard(profileA, dashboardWith(SettingsFixtures.response(revision = 2, amps = 16)))
        assertEquals(2, store.confirmed(profileA)!!.revision)
        assertTrue(stored is ConfirmedSettingsStore.Merge.Stored)

        // A dashboard that states no record is not a confirmed value: the cache stays as it was.
        assertNull(store.observeDashboard(profileA, dashboardWith(null)))
        assertEquals("no record is not a value", 2, store.confirmed(profileA)!!.revision)
    }

    @Test fun onlyTheFourRecordCarryingOutcomesUpdateTheCache() {
        val store = store()
        val updated = SettingsFixtures.parsed(revision = 2, amps = 16)
        val current = SettingsFixtures.parsed(revision = 1, amps = 10)
        val committed = SettingsFixtures.parsed(revision = 3, amps = 22)

        // Each of the four carries a confirmed record.
        assertEquals(2, (store.recordOutcome(profileA, SettingsUpdate.Outcome.Updated(updated)) as
            ConfirmedSettingsStore.Merge.Stored).settings.revision)
        assertEquals(3, (store.recordOutcome(profileA, SettingsUpdate.Outcome.Conflict(committed)) as
            ConfirmedSettingsStore.Merge.Stored).settings.revision)
        assertEquals(4, (store.recordOutcome(profileA, SettingsUpdate.Outcome.NotCommitted(committed.copy(revision = 4))) as
            ConfirmedSettingsStore.Merge.Stored).settings.revision)
        assertEquals(
            5,
            (store.recordOutcome(
                profileA,
                SettingsUpdate.Outcome.CommittedButReconcileFailed(SettingsFixtures.parsed(revision = 5, amps = 6))
            ) as ConfirmedSettingsStore.Merge.Stored).settings.revision
        )
        // The revision rule outranks the outcome.
        assertTrue(store.recordOutcome(profileA, SettingsUpdate.Outcome.Conflict(current)) is ConfirmedSettingsStore.Merge.Stale)
        assertEquals(5, store.confirmed(profileA)!!.revision)

        // An invalid body's `current` is not the result of this edit, and the two non-outcomes
        // carry no record at all.
        val before = store.confirmed(profileA)
        assertNull(store.recordOutcome(profileA, SettingsUpdate.Outcome.Invalid("invalid_amps", current)))
        assertNull(store.recordOutcome(profileA, SettingsUpdate.Outcome.Malformed))
        assertNull(store.recordOutcome(profileA, SettingsUpdate.Outcome.Unavailable))
        assertEquals(before, store.confirmed(profileA))
    }

    @Test fun aLowerRevisionNeverReplacesAHigherOne() {
        val store = store()
        store.record(profileA, SettingsFixtures.parsed(revision = 5, amps = 32))

        val merge = store.record(profileA, SettingsFixtures.parsed(revision = 3, amps = 6))

        val stale = merge as ConfirmedSettingsStore.Merge.Stale
        assertEquals(5, stale.kept.revision)
        assertEquals(5, store.confirmed(profileA)!!.revision)
        assertEquals(32, store.confirmed(profileA)!!.amps)
    }

    @Test fun aReinstalledInstancesLowerRevisionIsAcceptedOnceTheProfileWasRePaired() {
        val store = store()
        store.record(profileA, SettingsFixtures.parsed(revision = 9, amps = 32))
        store.record(profileB, SettingsFixtures.parsed(revision = 4, amps = 10))

        // Re-pairing forgets the profile's cache (ProfileCaches), so the new installation's record
        // wins.
        store.removeProfile(profileA)
        val merge = store.record(profileA, SettingsFixtures.parsed(revision = 0, amps = 16))

        assertTrue(merge is ConfirmedSettingsStore.Merge.Stored)
        assertEquals(0, store.confirmed(profileA)!!.revision)
        assertEquals(4, store.confirmed(profileB)!!.revision)
    }

    @Test fun anEqualRevisionIsIdempotentWhenIdenticalAndNamedWhenNot() {
        val store = store()
        store.record(profileA, SettingsFixtures.parsed(revision = 3, amps = 16))

        val identical = store.record(profileA, SettingsFixtures.parsed(revision = 3, amps = 16))
        val different = store.record(profileA, SettingsFixtures.parsed(revision = 3, amps = 10))

        assertEquals(ConfirmedSettingsStore.Merge.Unchanged, identical)
        val mismatch = different as ConfirmedSettingsStore.Merge.EqualRevisionMismatch
        assertEquals(16, mismatch.kept.amps)
        // Never last-writer-wins: the kept record is the one that was there.
        assertEquals(16, store.confirmed(profileA)!!.amps)
        assertTrue(warnings.any { message -> message.contains("revision 3") })
        assertNotEquals(
            SettingsFixtures.parsed(revision = 3, amps = 10),
            store.confirmed(profileA)
        )
    }

    @Test fun aLateAnswerForOneProfileCannotLandOnAnother() {
        val store = store()
        store.record(profileB, SettingsFixtures.parsed(revision = 4, amps = 32))
        store.record(profileA, SettingsFixtures.parsed(revision = 2, amps = 10))

        // The late answer names its own profile, and its revision is behind.
        val late = store.recordOutcome(profileA, SettingsUpdate.Outcome.Conflict(SettingsFixtures.parsed(revision = 1, amps = 6)))

        assertTrue(late is ConfirmedSettingsStore.Merge.Stale)
        assertEquals(2, store.confirmed(profileA)!!.revision)
        assertEquals(4, store.confirmed(profileB)!!.revision)
        assertEquals(32, store.confirmed(profileB)!!.amps)
        // And a write for B never appears under A's key.
        store.recordOutcome(profileB, SettingsUpdate.Outcome.Updated(SettingsFixtures.parsed(revision = 5, amps = 6)))
        assertEquals(2, store.confirmed(profileA)!!.revision)
        assertEquals(5, store.confirmed(profileB)!!.revision)
        assertTrue(!raw.getString("confirmed.$profileB").orEmpty().contains(raw.getString("confirmed.$profileA").orEmpty()))
    }

    @Test fun removingOneProfileLeavesTheOtherUntouched() {
        val store = store()
        store.record(profileA, SettingsFixtures.parsed(revision = 1, amps = 10))
        store.record(profileB, SettingsFixtures.parsed(revision = 7, amps = 32))

        store.removeProfile(profileA)

        assertNull(store.confirmed(profileA))
        assertNull(raw.getString("confirmed.$profileA"))
        assertEquals(7, store.confirmed(profileB)!!.revision)
        assertTrue(raw.getString("confirmed.$profileB")!!.contains("\"amps\":32"))
    }

    @Test fun aCorruptDocumentIsAbsentRatherThanAValueOrARepair() {
        val store = store()
        store.record(profileB, SettingsFixtures.parsed(revision = 7, amps = 32))
        raw.setRaw("confirmed.$profileA", "{not json at all")

        assertNull(store.confirmed(profileA))
        // Reported as absent, not repaired by inventing one and not deleted.
        assertTrue(warnings.any { message -> message.contains("could not be read") })
        assertEquals("{not json at all", raw.getString("confirmed.$profileA"))
        assertEquals(7, store.confirmed(profileB)!!.revision)

        // A record a later schema wrote is the same: unreadable here, untouched.
        raw.setRaw("confirmed.$profileA", JSONObject().apply {
            put("schema", 99)
            put("settings", SettingsFixtures.response(revision = 3))
        }.toString())
        assertNull(store.confirmed(profileA))
        assertTrue(warnings.any { message -> message.contains("readable document") })
        assertTrue(raw.getString("confirmed.$profileA")!!.contains("\"schema\":99"))

        // And a document whose settings are malformed is not a settings value either.
        raw.setRaw("confirmed.$profileA", JSONObject().apply {
            put("schema", 1)
            put("settings", JSONObject().apply { put("mode", 5) })
        }.toString())
        assertNull(store.confirmed(profileA))
    }

    @Test fun nothingStoredCarriesConnectionMaterialOrPriceData() {
        val store = store()
        store.record(
            profileA,
            SettingsFixtures.parsed(
                revision = 3,
                overrides = JSONArray().put(SettingsFixtures.override(vat = SettingsFixtures.fiscal(true, 25.0))),
                target = SettingsFixtures.target(vehicleId = "vehicle-1", targetPercent = 80.0)
            )
        )

        val document = raw.getString("confirmed.$profileA")!!
        listOf(
            "http", "webhook", "token", "password", "secret", "bearer",
            "base_url", "sensor.", "switch.", "number.", "entity_id",
            "intervals", "effective_price", "raw_price", "provenance", "soc_percent"
        ).forEach { banned -> assertTrue("stored document leaks $banned", !document.contains(banned)) }
        // What it does hold is the canonical record itself, and nothing else.
        assertEquals(
            SettingsFixtures.parsed(revision = 3, overrides = JSONArray().put(SettingsFixtures.override(vat = SettingsFixtures.fiscal(true, 25.0))), target = SettingsFixtures.target(vehicleId = "vehicle-1", targetPercent = 80.0)),
            store.confirmed(profileA)
        )
        assertEquals(setOf("schema", "settings"), JSONObject(document).keys().asSequence().toSet())
    }

    @Test fun theStoredDocumentSchemaIsReadStrictlyAndNeverRepaired() {
        val store = store()
        val validSettings = SettingsFixtures.response(revision = 3)
        store.record(profileB, SettingsFixtures.parsed(revision = 7, amps = 32))

        // A document this app did not write is not a document it reads.
        val documents = listOf(
            "a schema written as a string" to doc("\"1\"", validSettings),
            "a fractional schema" to doc("1.5", validSettings),
            "a boolean schema" to doc("true", validSettings),
            "a null schema" to doc("null", validSettings),
            "a future schema" to doc("2", validSettings),
            "a future schema written as a string" to doc("\"2\"", validSettings),
            "the schema before this one" to doc("0", validSettings),
            "no schema at all" to JSONObject().apply { put("settings", validSettings) }.toString(),
            "no settings at all" to JSONObject().apply { put("schema", 1) }.toString(),
            "an unknown extra key" to JSONObject().apply {
                put("schema", 1)
                put("settings", validSettings)
                put("saved_at", 1)
            }.toString(),
            "settings that are not an object" to JSONObject().apply {
                put("schema", 1)
                put("settings", "SE4")
            }.toString(),
            "settings that are an array" to JSONObject().apply {
                put("schema", 1)
                put("settings", JSONArray())
            }.toString(),
            // The settings record's own contract version is not part of the document any more.
            "an api_version beside the settings" to JSONObject().apply {
                put("schema", 1)
                put("api_version", 1)
                put("settings", validSettings)
            }.toString(),
            "not json at all" to "{not json"
        )

        documents.forEach { (what, document) ->
            raw.setRaw("confirmed.$profileA", document)
            assertNull("$what was read as a record", store.confirmed(profileA))
            // Read as absent, left exactly as found: never repaired, never deleted.
            assertEquals("$what was not left alone", document, raw.getString("confirmed.$profileA"))
        }
        assertTrue(
            warnings.count { it.contains("readable document") || it.contains("could not be read") } >=
                documents.size
        )
        // And one profile's unreadable document never disturbs another profile's.
        assertEquals(7, store.confirmed(profileB)!!.revision)
    }

    /** A numeric `1.0` *is* the number one, and it is accepted for the documented reason. */
    @Test fun aWhollyNumericalSchemaOfOnePointZeroIsAcceptedByTheSameWholeNumberRule() {
        val store = store()

        raw.setRaw("confirmed.$profileA", doc("1.0", SettingsFixtures.response(revision = 3)))

        assertEquals(3, store.confirmed(profileA)!!.revision)
    }

    @Test fun anOldExternalDocumentIsAbsentAndNeverRelabeledAsAutomatic() {
        val legacy = SettingsFixtures.response(revision = 8).put("mode", "external")
        val bytes = doc("1", legacy)
        raw.setRaw("confirmed.$profileA", bytes)
        assertNull(store().confirmed(profileA))
        assertEquals(bytes, raw.getString("confirmed.$profileA"))
    }

    /**
     * One raw document, with the schema written as JSON text so every case -- a wrong type, a wrong
     * value, a whole-numerical decimal -- is expressible.
     */
    private fun doc(schema: String, settings: JSONObject): String = "{\"schema\":$schema,\"settings\":$settings}"
}
