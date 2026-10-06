package se.sensnology.spotnav.ha.settings

/** The parking spot in the camera's picture: its top left corner and its size, as fractions (0-1) of the picture. */
data class CameraFrame(val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * A charger's camera for vehicle identification: the camera, the AI Task entity that compares the
 * pictures (`null`: Home Assistant's default) and the frame (`null`: the whole picture).
 */
data class HaCameraSettings(val cameraEntityId: String, val aiTaskEntityId: String?, val frame: CameraFrame?)

/** The settings record's `identify_camera` as stated: [camera] `null` is no camera. */
data class HaCameraChoice(val camera: HaCameraSettings?)

/** The contract's rules for `identify_camera` (`docs/api.md`): what a camera, an AI task and a frame may be. */
internal object HaCameraRules {
    const val INVALID_CAMERA = "invalid_camera"

    /** A frame narrower or lower than this (of the picture) is refused. */
    const val FRAME_MIN_SIZE = 0.05

    /** How much a frame may overshoot an edge through rounding. */
    private const val SLACK = 0.000001

    fun validCamera(entityId: String?): Boolean = entityId != null && entityId.startsWith("camera.") && entityId.length > 7

    fun validAiTask(entityId: String?): Boolean = entityId == null || (entityId.startsWith("ai_task.") && entityId.length > 8)

    fun validFrame(frame: CameraFrame): Boolean {
        val values = listOf(frame.x, frame.y, frame.w, frame.h)
        return values.all { it.isFinite() && it in 0.0..1.0 } &&
            frame.w >= FRAME_MIN_SIZE - SLACK && frame.h >= FRAME_MIN_SIZE - SLACK &&
            frame.x + frame.w <= 1.0 + SLACK && frame.y + frame.h <= 1.0 + SLACK
    }

    fun valid(camera: HaCameraSettings): Boolean =
        validCamera(camera.cameraEntityId) && validAiTask(camera.aiTaskEntityId) && (camera.frame?.let(::validFrame) ?: true)
}
