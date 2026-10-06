package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject

/** One car the open question offers: its id, its name, and whether its own plug sensor says it was plugged in. */
internal data class IdentificationCandidate(val vehicleId: String, val name: String, val likely: Boolean)

/** What one car was judged by at the last look: only its verdict is read (`plugged_in`, `away`, ...). */
internal data class IdentificationEvidence(val vehicleId: String, val verdict: String?)

/**
 * The dashboard's additive `identification` block (`docs/api.md`, "Vehicle identification"): present
 * only while a plug-in at a charger more than one car can charge at is being identified, or was.
 */
internal data class DashboardIdentification(
    val state: State,
    /** How the car was decided so far; `null` for a method this app does not know. */
    val method: Method?,
    /** The car the charger plans for now. */
    val vehicleId: String?,
    val since: String?,
    /** The cars to choose between, in the order a client shows them. */
    val candidates: List<IdentificationCandidate>,
    val evidence: List<IdentificationEvidence>
) {
    enum class State(val wire: String) { WAITING("waiting"), ASKING("asking"), DECIDED("decided") }

    enum class Method(val wire: String) {
        PLUG_SENSOR("plug_sensor"),
        LOCATION("location"),
        ANSWERED("answered"),
        MANUAL("manual"),
        ASSUMED("assumed"),

        /** The charger's camera recognised the car. */
        CAMERA("camera")
    }

    /** The verdict the last look reached for [vehicleId], or `null` when it states none. */
    fun verdictFor(vehicleId: String): String? = evidence.firstOrNull { it.vehicleId == vehicleId }?.verdict

    companion object {
        /**
         * The block, read leniently: absent, `null` or a block without a known `state` is no block (today's
         * behaviour); a candidate without an id is left out, and evidence this app cannot read is none.
         */
        fun parse(raw: Any?): DashboardIdentification? {
            val block = raw as? JSONObject ?: return null
            val state = State.entries.firstOrNull { it.wire == block.opt("state") } ?: return null
            val candidates = when (val list = block.opt("candidates")) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> objects(list).mapNotNull { row ->
                    val id = text(row.opt("vehicle_id")) ?: return@mapNotNull null
                    IdentificationCandidate(id, text(row.opt("name")) ?: id, row.opt("likely") == true)
                }
                else -> return null
            }
            val evidence = (block.opt("evidence") as? JSONArray)?.let { list ->
                objects(list).mapNotNull { row ->
                    val id = text(row.opt("vehicle_id")) ?: return@mapNotNull null
                    IdentificationEvidence(id, text(row.opt("verdict")))
                }
            }.orEmpty()
            return DashboardIdentification(
                state = state,
                method = Method.entries.firstOrNull { it.wire == block.opt("method") },
                vehicleId = text(block.opt("vehicle_id")),
                since = text(block.opt("since")),
                candidates = candidates.distinctBy { it.vehicleId },
                evidence = evidence
            )
        }

        private fun objects(list: JSONArray): List<JSONObject> =
            (0 until list.length()).mapNotNull { list.opt(it) as? JSONObject }

        private fun text(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotEmpty() }
    }
}

/** An entity a car's source could be read from, as Home Assistant names it. */
internal data class IdentificationEntity(val entityId: String, val name: String)

/**
 * One of a car's identification sources (its plug sensor, or its position): the entity read
 * ([entityId], `null` when none is), whether a person chose it ([chosen]; with no entity that is the
 * person's "none"), and the entities there are to choose from.
 */
internal data class IdentificationSource(
    val entityId: String?,
    val name: String?,
    val chosen: Boolean,
    val candidates: List<IdentificationEntity>
) {
    companion object {
        fun parse(raw: Any?): IdentificationSource? {
            val block = raw as? JSONObject ?: return null
            val entityId = (block.opt("entity_id") as? String)?.takeIf { it.isNotEmpty() }
            return IdentificationSource(
                entityId = entityId,
                name = (block.opt("name") as? String)?.takeIf { it.isNotBlank() },
                chosen = block.opt("chosen") == true,
                candidates = (block.opt("candidates") as? JSONArray)?.let { list ->
                    (0 until list.length()).mapNotNull { index ->
                        val row = list.opt(index) as? JSONObject ?: return@mapNotNull null
                        val id = (row.opt("entity_id") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        IdentificationEntity(id, (row.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: id)
                    }
                }.orEmpty()
            )
        }
    }
}

/** A vehicle row's additive `identification`: its plug sensor and its position, each `null` when unreadable. */
internal data class VehicleIdentificationSources(val plug: IdentificationSource?, val location: IdentificationSource?) {
    companion object {
        fun parse(raw: Any?): VehicleIdentificationSources? {
            val block = raw as? JSONObject ?: return null
            val sources = VehicleIdentificationSources(
                IdentificationSource.parse(block.opt("plug")),
                IdentificationSource.parse(block.opt("location"))
            )
            return sources.takeIf { it.plug != null || it.location != null }
        }
    }
}
