package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The durable presentation snapshot as storage: one document per charger, read strictly. */
class WidgetPlanSnapshotStoreTest {
    private val charger = "local-a"
    private val otherCharger = "local-b"
    private val documented = mutableListOf<String>()
    private val store = FakeKeyValueStore()
    private val snapshots = WidgetPlanSnapshotStore(store, { message -> documented += message })

    private fun period(from: String, to: String): ChargingPeriod =
        ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    private fun identity(areaId: String = "SE4"): PriceDocumentIdentity = PriceDocumentIdentity(
        areaId = areaId,
        today = listOf(Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-27T00:15:00Z")),
        tomorrow = listOf(Instant.parse("2026-09-28T00:00:00Z"))
    )

    private fun snapshot(
        profileId: String = charger,
        revision: Int = 3,
        areaId: String = "SE4",
        installed: Boolean = true,
        periods: List<ChargingPeriod> = listOf(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")),
        capturedAt: Long = 1_000L
    ): WidgetPlanSnapshot = WidgetPlanSnapshot(
        profileId = profileId,
        revision = revision,
        areaId = areaId,
        zoneId = ZoneId.of("Europe/Stockholm"),
        intervalMinutes = 15,
        vat = FiscalInput(enabled = true, overrideValue = 25.0, effectiveValue = 25.0),
        tax = FiscalInput.OFF,
        transfer = FiscalInput(enabled = false, overrideValue = null, effectiveValue = 30.0),
        installed = installed,
        periods = periods,
        amps = 16,
        chargingEnabled = false,
        priceIdentity = identity(areaId),
        capturedAt = capturedAt
    )

    private fun key(profileId: String) = "plan.$profileId"


    @Test
    fun aStoredSnapshotComesBackWithItsInstantsAndIdentities() {
        val written = snapshot()
        assertTrue(snapshots.put(written) is WidgetPlanSnapshotStore.Merge.Stored)

        val read = snapshots.snapshotFor(charger)
        assertEquals(written.copy(periods = emptyList()), read?.copy(periods = emptyList()))
        assertEquals(
            "the schedule's instants, exactly",
            written.periods.map { it.start.toInstant() to it.end.toInstant() },
            read?.periods?.map { it.start.toInstant() to it.end.toInstant() }
        )
        assertEquals(written.priceIdentity, read?.priceIdentity)
        assertEquals(ZoneId.of("Europe/Stockholm"), read?.zoneId)
        assertEquals(15, read?.intervalMinutes)
        assertNull("nothing was reported as unreadable", documented.lastOrNull())
    }

    @Test
    fun anIdenticalSubjectIsRetainedRatherThanRewritten() {
        val first = snapshot(capturedAt = 1_000L)
        val second = snapshot(capturedAt = 9_999L)

        assertEquals(WidgetPlanSnapshotStore.Merge.Stored(first), snapshots.put(first))
        assertEquals(
            "the same facts, confirmed again, are not new information",
            WidgetPlanSnapshotStore.Merge.Unchanged(first),
            snapshots.put(second)
        )
        assertEquals("so the retained stamp is the original one", 1_000L, snapshots.snapshotFor(charger)?.capturedAt)
    }

    @Test
    fun aChangedSubjectIsStoredAndSupersedesTheOldOne() {
        val installed = snapshot()
        snapshots.put(installed)

        // The charger's own explicit "no installed schedule" is a confirmed answer like any other.
        val cleared = snapshot(installed = false, periods = emptyList(), revision = 4, capturedAt = 2_000L)
        assertEquals(WidgetPlanSnapshotStore.Merge.Stored(cleared), snapshots.put(cleared))
        assertEquals(cleared, snapshots.snapshotFor(charger))
        assertTrue("and it draws no bands", snapshots.snapshotFor(charger)!!.periods.isEmpty())
    }

    @Test
    fun anOffsetSpellingIsNotADifferentSchedule() {
        // A value read back spells its offsets in UTC; a fresh capture may carry the wire's own offset.
        snapshots.put(snapshot(periods = listOf(period("2026-09-27T12:00:00+02:00", "2026-09-27T15:45:00+02:00"))))
        val retained = snapshots.snapshotFor(charger)!!
        val recaptured = snapshot(periods = listOf(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")), capturedAt = 5_000L)

        assertEquals(
            "one instant pair, two spellings, one subject",
            WidgetPlanSnapshotStore.Merge.Unchanged(retained),
            snapshots.put(recaptured)
        )
    }


    @Test
    fun anObsoleteCandidateCannotResurrectASupersededSnapshot() {
        // The charger's own "no installed schedule" is confirmed later...
        val cleared = snapshot(installed = false, periods = emptyList(), revision = 5, capturedAt = 2_000L)
        assertEquals(WidgetPlanSnapshotStore.Merge.Stored(cleared), snapshots.put(cleared))

        // ... and a pass captured earlier, still carrying the former installed plan, arrives afterwards.
        val obsolete = snapshot(revision = 4, capturedAt = 1_000L)
        assertEquals(
            "an older answer describes a moment already left behind",
            WidgetPlanSnapshotStore.Merge.Unchanged(cleared),
            snapshots.put(obsolete)
        )
        assertEquals("so the cleared snapshot stands", cleared, snapshots.snapshotFor(charger))
        assertTrue("and no installed periods came back", snapshots.snapshotFor(charger)!!.periods.isEmpty())
    }

    @Test
    fun anIdentityAboutAnotherMarketIsNotThisSnapshotsIdentity() {
        snapshots.put(snapshot())
        val raw = store.rawOrNull(key(charger))!!
        val pricesAt = raw.indexOf("\"prices\"")
        val tampered = raw.substring(0, pricesAt) +
            raw.substring(pricesAt).replaceFirst("\"area\":\"SE4\"", "\"area\":\"NO1\"")
        store.setRaw(key(charger), tampered)

        assertNull(snapshots.snapshotFor(charger))
        assertEquals("still there, byte for byte", tampered, store.rawOrNull(key(charger)))
    }

    @Test
    fun aCorruptDocumentIsAbsentAndLeftAsItWasFound() {
        store.setRaw(key(charger), "{not json at all")

        assertNull("no crash, and no snapshot", snapshots.snapshotFor(charger))
        assertEquals("never repaired", "{not json at all", store.rawOrNull(key(charger)))
        assertTrue("and the refusal is reported", documented.isNotEmpty())
    }

    @Test
    fun anUnknownSchemaVersionIsAbsentRatherThanInterpreted() {
        val written = snapshot()
        snapshots.put(written)
        val raw = store.rawOrNull(key(charger))!!
        store.setRaw(key(charger), raw.replace("\"schema\":1", "\"schema\":2"))

        assertNull("a newer or older document is not this app's to read", snapshots.snapshotFor(charger))
        assertEquals("still there, byte for byte", raw.replace("\"schema\":1", "\"schema\":2"), store.rawOrNull(key(charger)))
    }

    @Test
    fun aMissingOrMalformedPartMakesTheWholeDocumentAbsent() {
        val written = snapshot()
        snapshots.put(written)
        val raw = store.rawOrNull(key(charger))!!

        // One period whose instant cannot be read: nothing is drawn from the parts that did parse.
        store.setRaw(key(charger), raw.replace("2026-09-27T10:00:00Z", "not-an-instant"))
        assertNull(snapshots.snapshotFor(charger))

        // The empty-but-valid document a guess would produce is refused too: every field must be stated.
        store.setRaw(key(charger), """{"schema":1,"snapshot":{}}""")
        assertNull(snapshots.snapshotFor(charger))
    }

    @Test
    fun aDocumentUnderAnotherChargersKeyIsNotThisChargersAnswer() {
        val other = snapshot(profileId = otherCharger)
        snapshots.put(other)
        // The same *key*, but a document that names another charger: the identity is the document's own.
        store.setRaw(key(charger), store.rawOrNull(key(otherCharger))!!)

        assertNull(snapshots.snapshotFor(charger))
        assertEquals(other, snapshots.snapshotFor(otherCharger))
    }

    @Test
    fun twoChargersNeverShareASnapshot() {
        val a = snapshot(profileId = charger, periods = listOf(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")))
        val b = snapshot(profileId = otherCharger, areaId = "NO1", periods = listOf(period("2026-09-27T05:00:00Z", "2026-09-27T06:00:00Z")))

        snapshots.put(a)
        snapshots.put(b)

        assertEquals(a, snapshots.snapshotFor(charger))
        assertEquals(b, snapshots.snapshotFor(otherCharger))
        assertNotEquals(snapshots.snapshotFor(charger), snapshots.snapshotFor(otherCharger))
    }

    @Test
    fun clearingOneChargersSnapshotLeavesTheOthersAlone() {
        val a = snapshot(profileId = charger)
        val b = snapshot(profileId = otherCharger, areaId = "NO1")
        snapshots.put(a)
        snapshots.put(b)

        snapshots.clear(charger)

        assertNull(snapshots.snapshotFor(charger))
        assertEquals(b, snapshots.snapshotFor(otherCharger))
    }


    @Test
    fun anOlderPassCannotBeWrittenAfterANewerOneHasBeenConfirmed() {
        val storage = RecordingStore()
        val insideTheGap = CountDownLatch(1)
        val newerConfirmed = CountDownLatch(1)
        val older = snapshot(capturedAt = 1_000L)
        val newer = snapshot(periods = listOf(period("2026-09-27T18:00:00Z", "2026-09-27T19:00:00Z")), capturedAt = 2_000L)
        val olderPass = WidgetPlanSnapshotStore(storage, {}) {
            insideTheGap.countDown()
            newerConfirmed.await(2, TimeUnit.SECONDS)
        }
        val newerPass = WidgetPlanSnapshotStore(storage, {})

        val olderWriter = thread(name = "older-pass") { olderPass.put(older) }
        assertTrue("the older pass is inside its read-compare-write", insideTheGap.await(2, TimeUnit.SECONDS))
        val newerWriter = thread(name = "newer-pass") {
            newerPass.put(newer)
            newerConfirmed.countDown()
        }
        olderWriter.join(5_000)
        newerWriter.join(5_000)

        assertEquals(
            "an older answer may not land after a newer one: one document, in capture order",
            listOf(1_000L, 2_000L),
            storage.writtenCaptures()
        )
        assertEquals("so the newer confirmation is the one that stands", 2_000L, newerPass.snapshotFor(charger)?.capturedAt)
    }

    /** A [KeyValueStore] that survives two threads and remembers the order the documents arrived in. */
    private class RecordingStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        private val written = mutableListOf<String>()

        @Synchronized override fun getString(key: String): String? = values[key]

        @Synchronized override fun putString(key: String, value: String) {
            values[key] = value
            written += value
        }

        @Synchronized override fun remove(key: String) {
            values.remove(key)
        }

        /** The `captured` stamp of every document written, in the order the storage received them. */
        @Synchronized fun writtenCaptures(): List<Long> = written.map { raw ->
            val document = raw.filterNot { it.isWhitespace() }
            captured.find(document)?.groupValues?.get(1)?.toLong()
                ?: error("a written document without a captured stamp: $document")
        }

        private companion object {
            private val captured = Regex("\"captured\":(\\d+)")
        }
    }

    @Test
    fun anUnknownOrUnboundChargerReadsNothing() {
        snapshots.put(snapshot())
        assertNull(snapshots.snapshotFor(null))
        assertNull(snapshots.snapshotFor(""))
        assertNull(snapshots.snapshotFor("local-nothing"))
    }
}
