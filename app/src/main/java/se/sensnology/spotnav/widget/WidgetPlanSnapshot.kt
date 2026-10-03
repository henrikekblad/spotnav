package se.sensnology.spotnav.widget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.prices.PriceResult
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Which price documents a snapshot's shading was clipped against, as an identity rather than prices.
 *
 * Band geometry is normalized over the dates a document set represents, so a band only fits the set it was
 * cut for. The identity is the represented instants per day, never a fetch stamp, which moves on every
 * forced refresh even for identical documents.
 */
internal data class PriceDocumentIdentity(
    val areaId: String,
    val today: List<Instant>,
    val tomorrow: List<Instant>
) {
    /** Whether [result] is the document set this identity was taken from. */
    fun matches(result: PriceResult): Boolean =
        today == result.today.map { it.start.toInstant() } &&
            tomorrow == result.tomorrow.map { it.start.toInstant() }

    companion object {
        fun of(areaId: String, result: PriceResult): PriceDocumentIdentity = PriceDocumentIdentity(
            areaId = areaId,
            today = result.today.map { it.start.toInstant() },
            tomorrow = result.tomorrow.map { it.start.toInstant() }
        )
    }
}

/**
 * One confirmed Home Assistant pass, as a display cache a widget can render after the confirming screen is
 * gone. It holds what a picture needs (charger, market clock and fiscals, installed schedule, price
 * identity) and no planning input, so it cannot feed a local planner. It is never the in-app screen's
 * source of facts.
 *
 * [profileId] is a `ChargerProfile.localId`, never rendered or logged; no credential, URL or device id is
 * stored.
 */
internal data class WidgetPlanSnapshot(
    /** Which charger's plan this is (`ChargerProfile.localId`); the store's key, never shown. */
    val profileId: String,
    /** The Home Assistant settings revision the record was read at. */
    val revision: Int,
    /** The effective area the record named, and the market these prices are. */
    val areaId: String,
    /** The market's own clock, captured rather than re-derived from the catalogue. */
    val zoneId: ZoneId,
    /** The presentation resolution drawn at (15 or 60 minutes; a 30-minute source is drawn on quarters). */
    val intervalMinutes: Int,
    val vat: FiscalInput,
    val tax: FiscalInput,
    val transfer: FiscalInput,
    /** Whether Home Assistant stated a schedule as installed at capture time. */
    val installed: Boolean,
    /** The installed schedule's periods, instants preserved. */
    val periods: List<ChargingPeriod>,
    /** The installed schedule's current limit, when the status reported one. */
    val amps: Int?,
    /** Whether the charger was charging, as the same status reported. */
    val chargingEnabled: Boolean,
    /** The price documents the shading was cut against. */
    val priceIdentity: PriceDocumentIdentity,
    /** When the facts were first confirmed; provenance only, excluded from [sameSubjectAs]. */
    val capturedAt: Long
) {
    init {
        require(profileId.isNotBlank()) { "a widget plan snapshot names its charger" }
        require(areaId.isNotBlank()) { "a widget plan snapshot names its market" }
        require(intervalMinutes == 15 || intervalMinutes == 60) { "intervalMinutes must be 15 or 60" }
    }

    /** The market this snapshot's graph is drawn in: the captured area, resolution and fiscals. */
    val market: ChartMarket
        get() = ChartMarket(
            areaId = areaId,
            intervalMinutes = intervalMinutes,
            vat = vat,
            tax = tax,
            transfer = transfer
        )

    val remotePlan: RemotePlan
        get() = RemotePlan(
            installed = installed,
            periods = periods,
            amps = amps,
            chargingEnabled = chargingEnabled
        )

    /**
     * Whether [other] states the same subject: everything but [capturedAt], with the schedule compared as
     * instants (an offset is a spelling, and storage reads back UTC). A later identical pass leaves the
     * retained snapshot and its [capturedAt] untouched.
     */
    fun sameSubjectAs(other: WidgetPlanSnapshot): Boolean =
        copy(capturedAt = other.capturedAt, periods = emptyList()) ==
            other.copy(periods = emptyList()) &&
            periodInstants() == other.periodInstants()

    private fun periodInstants(): List<Pair<Instant, Instant>> =
        periods.map { period -> period.start.toInstant() to period.end.toInstant() }
}


/**
 * The durable presentation snapshot per charger, so a widget can draw the confirmed plan after the
 * confirming process is gone. Keyed by `ChargerProfile.localId`, never by widget id.
 *
 * `ConfirmedSettingsStore` keeps the confirmed record; this keeps the drawn answer (installed schedule,
 * market clock, price identity), which cannot be rebuilt from a record without planning again.
 *
 * Storage: one key per charger (`plan.<localId>`) holding an atomic JSON document
 * `{"schema":1,"snapshot":{...}}`. A partial, corrupt or unknown-version document reads as absent: logged,
 * never repaired.
 *
 * Lifetime: a snapshot is superseded, never inferred. Only a newer-or-equal-capture confirmed pass for the
 * same charger replaces it (including the charger's own confirmed "no installed schedule"). A binding
 * change, removed charger or failed read does not rewrite it; [clear] is the one explicit removal.
 *
 * Concurrency: [put]'s read-compare-write is one step under a lock per storage and charger
 * ([documentLock]), shared by all instances over the same preference file; [clear] takes it too. Reads
 * stay lock-free since one key holds one complete document. The lock is in-process, which suffices
 * because the app declares no `android:process`.
 */
internal class WidgetPlanSnapshotStore(
    private val store: KeyValueStore,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) },
    /** Which storage the records live in, as the [documentLock] key; unnamed storages share [UNNAMED_STORAGE]. */
    private val storageKey: String = (store as? SharedPreferencesKeyValueStore)?.prefsName ?: UNNAMED_STORAGE,
    /** Test seam: runs inside the document lock after the retained read and before the write. */
    private val afterRetainedRead: (() -> Unit)? = null
) {
    /** What one `put` did. */
    sealed interface Merge {
        /** The snapshot that now stands; for [Unchanged] it is the retained one, not the candidate. */
        val snapshot: WidgetPlanSnapshot

        /** Something new stands: the widget owes one redraw. */
        data class Stored(override val snapshot: WidgetPlanSnapshot) : Merge

        /** The same subject (or a newer capture) was already stored and stands untouched; no redraw is owed. */
        data class Unchanged(override val snapshot: WidgetPlanSnapshot) : Merge
    }

    /** The snapshot stored for [profileId], or `null` when there is none this app can read. */
    fun snapshotFor(profileId: String?): WidgetPlanSnapshot? {
        val id = profileId?.takeIf { it.isNotBlank() } ?: return null
        return read(id)
    }

    /**
     * Stores [snapshot] for its charger, unless the same subject is already there or the pass is older
     * than the retained capture; both answer [Merge.Unchanged] and keep the retained document.
     *
     * `capturedAt` is a wall clock, so a backwards clock jump can make a newer pass look obsolete for a
     * while; the accepted cost is a briefly lagging plan, never a superseded one returning.
     */
    fun put(snapshot: WidgetPlanSnapshot): Merge = synchronized(documentLock(snapshot.profileId)) {
        val retained = read(snapshot.profileId)
        if (retained != null) {
            if (retained.sameSubjectAs(snapshot)) return@synchronized Merge.Unchanged(retained)
            if (snapshot.capturedAt < retained.capturedAt) return@synchronized Merge.Unchanged(retained)
        }
        afterRetainedRead?.invoke()
        store.putString(key(snapshot.profileId), document(snapshot).toString())
        Merge.Stored(snapshot)
    }

    /** Forgets one charger's snapshot, under the same [documentLock] as [put] so a removal cannot be undone by an in-flight write. */
    fun clear(profileId: String) {
        synchronized(documentLock(profileId)) {
            store.remove(key(profileId))
        }
    }

    /** One lock per (storage, charger); the map is process-wide and never swept, as chargers are few. */
    private fun documentLock(profileId: String): Any =
        locks.computeIfAbsent("$storageKey\u0000$profileId") { Any() }

    private fun read(profileId: String): WidgetPlanSnapshot? {
        val raw = store.getString(key(profileId)) ?: return null
        return try {
            val document = JSONObject(raw)
            if (!isReadableDocument(document)) {
                logWarning("A widget plan snapshot for one charger is not a readable document")
                null
            } else {
                snapshotOf(document.getJSONObject(SNAPSHOT), profileId)
            }
        } catch (failure: Exception) {
            logWarning("A widget plan snapshot for one charger could not be read: ${failure.javaClass.simpleName}")
            null
        }
    }

    /** The storage document; instants are written as UTC, so reads are instant-identical but may differ in offset spelling. */
    private fun document(snapshot: WidgetPlanSnapshot): JSONObject = JSONObject().apply {
        put(SCHEMA, SCHEMA_VERSION)
        put(SNAPSHOT, JSONObject().apply {
            put(CHARGER, snapshot.profileId)
            put(REVISION, snapshot.revision)
            put(AREA, snapshot.areaId)
            put(ZONE, snapshot.zoneId.id)
            put(INTERVAL, snapshot.intervalMinutes)
            put(VAT, fiscal(snapshot.vat))
            put(TAX, fiscal(snapshot.tax))
            put(TRANSFER, fiscal(snapshot.transfer))
            put(INSTALLED, snapshot.installed)
            put(PERIODS, JSONArray().apply {
                snapshot.periods.forEach { period ->
                    put(JSONObject().apply {
                        put(START, period.start.toInstant().toString())
                        put(END, period.end.toInstant().toString())
                    })
                }
            })
            put(AMPS, snapshot.amps ?: JSONObject.NULL)
            put(CHARGING, snapshot.chargingEnabled)
            put(PRICES, JSONObject().apply {
                put(AREA, snapshot.priceIdentity.areaId)
                put(TODAY, JSONArray().apply { snapshot.priceIdentity.today.forEach { put(it.toString()) } })
                put(TOMORROW, JSONArray().apply { snapshot.priceIdentity.tomorrow.forEach { put(it.toString()) } })
            })
            put(CAPTURED, snapshot.capturedAt)
        })
    }

    private fun fiscal(input: FiscalInput): JSONObject = JSONObject().apply {
        put("enabled", input.enabled)
        put("override", input.overrideValue ?: JSONObject.NULL)
        put("effective", input.effectiveValue ?: JSONObject.NULL)
    }

    /**
     * One stored snapshot, or `null` unless every key is present with the right kind of value, the
     * document's charger is the key it was found under, and the price identity is this market's. Never
     * partly read: an empty-but-valid snapshot would shade nothing while looking like an answer.
     */
    private fun snapshotOf(json: JSONObject, requested: String): WidgetPlanSnapshot? {
        if (json.keys().asSequence().toSet() != SNAPSHOT_KEYS) return refuse()
        val charger = (json.opt(CHARGER) as? String)?.takeIf { it.isNotBlank() } ?: return refuse()
        if (charger != requested) return refuse()
        val revision = whole(json.opt(REVISION)) ?: return refuse()
        val area = (json.opt(AREA) as? String)?.takeIf { it.isNotBlank() } ?: return refuse()
        val zone = (json.opt(ZONE) as? String)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return refuse()
        val interval = whole(json.opt(INTERVAL)) ?: return refuse()
        if (interval != 15 && interval != 60) return refuse()
        val vat = fiscalOf(json.opt(VAT)) ?: return refuse()
        val tax = fiscalOf(json.opt(TAX)) ?: return refuse()
        val transfer = fiscalOf(json.opt(TRANSFER)) ?: return refuse()
        val installed = json.opt(INSTALLED) as? Boolean ?: return refuse()
        val periods = periodsOf(json.opt(PERIODS)) ?: return refuse()
        val ampsRaw = json.opt(AMPS)
        val amps = if (ampsRaw === JSONObject.NULL) null else whole(ampsRaw) ?: return refuse()
        val charging = json.opt(CHARGING) as? Boolean ?: return refuse()
        val identity = identityOf(json.opt(PRICES), area) ?: return refuse()
        val captured = long(json.opt(CAPTURED)) ?: return refuse()
        return WidgetPlanSnapshot(
            profileId = charger,
            revision = revision,
            areaId = area,
            zoneId = zone,
            intervalMinutes = interval,
            vat = vat,
            tax = tax,
            transfer = transfer,
            installed = installed,
            periods = periods,
            amps = amps,
            chargingEnabled = charging,
            priceIdentity = identity,
            capturedAt = captured
        )
    }

    private fun fiscalOf(raw: Any?): FiscalInput? {
        val json = raw as? JSONObject ?: return null
        if (json.keys().asSequence().toSet() != FISCAL_KEYS) return null
        val enabled = json.opt(ENABLED) as? Boolean ?: return null
        val override = figureOf(json.opt(OVERRIDE)) ?: return null
        val effective = figureOf(json.opt(EFFECTIVE)) ?: return null
        return runCatching { FiscalInput(enabled, override.value, effective.value) }.getOrNull()
    }

    /** A stated-or-absent fiscal figure; a `null` [Figure] means the value was malformed. */
    private class Figure(val value: Double?)

    private fun figureOf(raw: Any?): Figure? = when {
        raw === JSONObject.NULL -> Figure(null)
        raw is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 0.0 }?.let(::Figure)
        else -> null
    }

    private fun periodsOf(raw: Any?): List<ChargingPeriod>? {
        val array = raw as? JSONArray ?: return null
        val periods = mutableListOf<ChargingPeriod>()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: return null
            if (item.keys().asSequence().toSet() != PERIOD_KEYS) return null
            val start = instantOf(item.opt(START)) ?: return null
            val end = instantOf(item.opt(END)) ?: return null
            periods += ChargingPeriod(
                OffsetDateTime.ofInstant(start, java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC)
            )
        }
        return periods
    }

    /** The price identity, refused unless it is about [area]. */
    private fun identityOf(raw: Any?, area: String): PriceDocumentIdentity? {
        val json = raw as? JSONObject ?: return null
        if (json.keys().asSequence().toSet() != PRICES_KEYS) return null
        val identityArea = (json.opt(AREA) as? String)?.takeIf { it.isNotBlank() } ?: return null
        if (identityArea != area) return null
        val today = instantsOf(json.opt(TODAY)) ?: return null
        val tomorrow = instantsOf(json.opt(TOMORROW)) ?: return null
        return PriceDocumentIdentity(identityArea, today, tomorrow)
    }

    private fun instantsOf(raw: Any?): List<Instant>? {
        val array = raw as? JSONArray ?: return null
        val instants = mutableListOf<Instant>()
        for (index in 0 until array.length()) {
            instants += instantOf(array.opt(index)) ?: return null
        }
        return instants
    }

    private fun instantOf(raw: Any?): Instant? =
        (raw as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun whole(raw: Any?): Int? = (raw as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it == Math.floor(it) && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble() }
        ?.toInt()

    private fun long(raw: Any?): Long? = (raw as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it == Math.floor(it) }
        ?.toLong()

    /** One logged, wholesale refusal. */
    private fun <T> refuse(): T? {
        logWarning("A widget plan snapshot for one charger is not exactly this schema")
        return null
    }

    private fun key(profileId: String): String = "$KEY_PREFIX$profileId"

    /** Whether the document is exactly this schema version's shape; JSON is read strictly, not coerced (`"1"` is not `1`). */
    private fun isReadableDocument(document: JSONObject): Boolean {
        if (document.keys().asSequence().toSet() != DOCUMENT_KEYS) return false
        val schema = (document.opt(SCHEMA) as? Number)?.toDouble() ?: return false
        if (!schema.isFinite() || schema != Math.floor(schema)) return false
        if (schema != SCHEMA_VERSION.toDouble()) return false
        return document.opt(SNAPSHOT) is JSONObject
    }

    companion object {
        private const val TAG = "SpotNavWidgetPlan"

        /** The document locks; process-wide, since a per-instance lock would exclude nothing. */
        private val locks = ConcurrentHashMap<String, Any>()

        private const val UNNAMED_STORAGE = "key-value-store"

        /** The preference file of this cache; internal so `TestModeSnapshot` can name it. */
        internal const val PREFS = "spotnav_widget_plan"

        private const val SCHEMA_VERSION = 1
        private const val KEY_PREFIX = "plan."

        private const val SCHEMA = "schema"
        private const val SNAPSHOT = "snapshot"
        private const val CHARGER = "charger"
        private const val REVISION = "revision"
        private const val AREA = "area"
        private const val ZONE = "zone"
        private const val INTERVAL = "interval"
        private const val VAT = "vat"
        private const val TAX = "tax"
        private const val TRANSFER = "transfer"
        private const val INSTALLED = "installed"
        private const val PERIODS = "periods"
        private const val START = "start"
        private const val END = "end"
        private const val AMPS = "amps"
        private const val CHARGING = "charging"
        private const val PRICES = "prices"
        private const val TODAY = "today"
        private const val TOMORROW = "tomorrow"
        private const val CAPTURED = "captured"
        private const val ENABLED = "enabled"
        private const val OVERRIDE = "override"
        private const val EFFECTIVE = "effective"

        private val SNAPSHOT_KEYS = setOf(
            CHARGER, REVISION, AREA, ZONE, INTERVAL, VAT, TAX, TRANSFER,
            INSTALLED, PERIODS, AMPS, CHARGING, PRICES, CAPTURED
        )
        private val FISCAL_KEYS = setOf(ENABLED, OVERRIDE, EFFECTIVE)
        private val PERIOD_KEYS = setOf(START, END)
        private val PRICES_KEYS = setOf(AREA, TODAY, TOMORROW)
        private val DOCUMENT_KEYS = setOf(SCHEMA, SNAPSHOT)

        fun forContext(context: Context): WidgetPlanSnapshotStore =
            WidgetPlanSnapshotStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
