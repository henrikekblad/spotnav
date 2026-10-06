package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject

/** A camera or an AI Task entity that can be chosen, as Home Assistant names it. */
internal data class CameraEntity(val entityId: String, val name: String)

/** A reference picture's kind: in daylight, or in the dark (the camera's infrared). */
internal enum class PictureKind(val wire: String) {
    DAY("day"),
    NIGHT("night");

    companion object {
        fun of(wire: Any?): PictureKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One of a car's reference pictures, as listed (never the picture itself): its kind, when it was taken,
 * whether it has a colour signature, and whether it was taken with another frame than the one now
 * ([stale]: it should be taken again).
 */
internal data class ReferencePicture(val kind: PictureKind, val takenAt: String, val colour: Boolean, val stale: Boolean = false) {
    companion object {
        /** One listed picture, or `null` when it is not one this app can read. */
        fun parse(raw: Any?): ReferencePicture? {
            val row = raw as? JSONObject ?: return null
            val kind = PictureKind.of(row.opt("kind")) ?: return null
            val takenAt = (row.opt("taken_at") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            return ReferencePicture(kind, takenAt, row.opt("colour") == true, row.opt("stale") == true)
        }

        /** A list of pictures, the ones this app cannot read left out; `null` when it is not a list. */
        fun list(raw: Any?): List<ReferencePicture>? {
            val list = raw as? JSONArray ?: return null
            return (0 until list.length()).mapNotNull { parse(list.opt(it)) }
        }
    }
}

/**
 * The dashboard's additive `camera_identification` block (`docs/api.md`, "The camera"): the cameras and
 * AI Task entities to choose from, and per car at this charger its reference pictures from the chosen
 * camera, day first. Absent where Home Assistant offers no camera, and from an older one.
 */
internal data class DashboardCamera(
    val cameras: List<CameraEntity>,
    val aiTasks: List<CameraEntity>,
    val references: Map<String, List<ReferencePicture>>
) {
    companion object {
        /** The block, or `null` when it is absent or not the block's shape; an entry it cannot read is left out. */
        fun parse(raw: Any?): DashboardCamera? {
            val block = raw as? JSONObject ?: return null
            val cameras = entities(block.opt("cameras")) ?: return null
            val aiTasks = entities(block.opt("ai_tasks")) ?: return null
            val byCar = block.opt("references") as? JSONObject ?: return null
            val references = byCar.keys().asSequence().associateWith { id -> ReferencePicture.list(byCar.opt(id)) ?: return null }
            return DashboardCamera(cameras, aiTasks, references)
        }

        /** A list of `{entity_id, name}`, or `null` when it is not a list. */
        private fun entities(raw: Any?): List<CameraEntity>? {
            val list = raw as? JSONArray ?: return null
            return objects(list).mapNotNull { row ->
                val id = (row.opt("entity_id") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                CameraEntity(id, (row.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: id)
            }.distinctBy { it.entityId }
        }

        private fun objects(list: JSONArray): List<JSONObject> =
            (0 until list.length()).mapNotNull { list.opt(it) as? JSONObject }
    }
}
