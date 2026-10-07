package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.PictureKind
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.ha.settings.HaCameraRules
import se.sensnology.spotnav.ha.settings.HaCameraSettings
import java.util.Base64

/** A picture as Home Assistant sent it: the JPEG and its size in pixels. */
internal class CameraPicture(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * The camera's webhook actions at `api_version` 1 (`docs/api.md`, "The camera"): the camera's picture
 * now to draw the frame on, the frame, and a car's reference pictures (take, delete, and a thumbnail).
 * Home Assistant crops and scales; the app only shows what it is sent.
 */
internal object CameraCommands {
    const val API_VERSION = 1
    const val NO_CAMERA = "spotnav_no_camera"
    const val NO_PICTURE = "spotnav_no_picture"

    /** One action and its own fields. */
    sealed class Request(val action: String) {
        protected open fun fields(body: JSONObject) = Unit

        fun payload(): JSONObject = JSONObject().apply {
            put("version", 1)
            WebhookReads.put(this)
            put("action", action)
            put("api_version", API_VERSION)
            fields(this)
        }
    }

    /** The chosen camera's whole picture now (only the charger's own camera: the webhook names no other). */
    data object Snapshot : Request("camera_snapshot")

    /** The frame (`null`: the whole picture). */
    data class SaveFrame(val frame: CameraFrame?) : Request("save_camera_frame") {
        override fun fields(body: JSONObject) {
            body.put("frame", frame?.let { JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h) } ?: JSONObject.NULL)
        }
    }

    /** Take one of the car's reference pictures now, as the camera sees it, cropped with the frame. */
    data class TakeReference(val vehicleId: String, val kind: PictureKind) : Request("take_reference_picture") {
        override fun fields(body: JSONObject) {
            body.put("vehicle_id", vehicleId).put("kind", kind.wire)
        }
    }

    /** Delete one of the car's pictures, or every one of them ([kind] `null`). */
    data class DeleteReference(val vehicleId: String, val kind: PictureKind?) : Request("delete_reference_picture") {
        override fun fields(body: JSONObject) {
            body.put("vehicle_id", vehicleId).put("kind", kind?.wire ?: JSONObject.NULL)
        }
    }

    /** A thumbnail of one of the car's pictures. */
    data class Reference(val vehicleId: String, val kind: PictureKind) : Request("reference_picture") {
        override fun fields(body: JSONObject) {
            body.put("vehicle_id", vehicleId).put("kind", kind.wire)
        }
    }

    sealed interface Outcome {
        /** A picture: the camera's now, or a reference thumbnail. */
        class Picture(val picture: CameraPicture) : Outcome

        /** The settings' camera after the frame was saved (`null`: the camera was taken away meanwhile). */
        data class Framed(val camera: HaCameraSettings?) : Outcome

        /** The car's reference pictures after a picture was taken or deleted. */
        data class References(val vehicleId: String, val pictures: List<ReferencePicture>) : Outcome

        /** Refused ([code]: [NO_CAMERA], [NO_PICTURE], ...), or no answer this app can read (`null`). */
        data class Failed(val code: String?) : Outcome
    }

    /** What [request]'s answer means; never throws. */
    fun answer(request: Request, status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (!head.ok || head.error != null) return Outcome.Failed(head.error)
        val json = head.json
        return when (request) {
            Snapshot, is Reference -> picture(json.opt("picture"))?.let { Outcome.Picture(it) }
            is SaveFrame -> framed(json)
            is TakeReference, is DeleteReference -> references(json, request)
        } ?: Outcome.Failed(null)
    }

    private fun picture(raw: Any?): CameraPicture? {
        val json = raw as? JSONObject ?: return null
        if (json.opt("content_type") != "image/jpeg") return null
        val data = json.opt("data") as? String ?: return null
        val width = (json.opt("width") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
        val height = (json.opt("height") as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
        val jpeg = runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return CameraPicture(jpeg, width, height)
    }

    private fun framed(json: JSONObject): Outcome? {
        if (!json.has("identify_camera")) return null
        val raw = json.opt("identify_camera")
        if (raw == null || raw === JSONObject.NULL) return Outcome.Framed(null)
        val camera = raw as? JSONObject ?: return null
        val entity = camera.opt("camera_entity_id") as? String ?: return null
        val aiTask = when (val value = camera.opt("ai_task_entity_id")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return null
        }
        val frame = when (val value = camera.opt("frame")) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val parts = listOf("x", "y", "w", "h").map { (value.opt(it) as? Number)?.toDouble() ?: return null }
                CameraFrame(parts[0], parts[1], parts[2], parts[3])
            }
            else -> return null
        }
        return HaCameraSettings(entity, aiTask, frame).takeIf { HaCameraRules.valid(it) }?.let { Outcome.Framed(it) }
    }

    private fun references(json: JSONObject, request: Request): Outcome? {
        val vehicleId = json.opt("vehicle_id") as? String ?: when (request) {
            is TakeReference -> request.vehicleId
            is DeleteReference -> request.vehicleId
            else -> return null
        }
        return ReferencePicture.list(json.opt("references"))?.let { Outcome.References(vehicleId, it) }
    }
}
