package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.CameraEntity
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.ha.settings.HaCameraSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Settings shows of the charger's camera for identification, decided from one dashboard and the
 * record: the camera, and with one chosen its frame (the AI task is the Home Assistant card's alone);
 * each of the charger's cars its reference pictures. A Home Assistant that offers no camera (no
 * `camera_identification` block) or does not state `identify_camera` shows none of it.
 */
internal object CameraSetup {
    /**
     * The charger's camera rows: the cameras there are, the one chosen (`null`: none) and its name, and
     * whether the picture is cropped (else the whole picture is compared).
     */
    data class Section(
        val cameras: List<CameraEntity>,
        val chosen: HaCameraSettings?,
        val cameraName: String?,
        val frameDrawn: Boolean
    )

    fun section(dashboard: Dashboard, record: HaPlanningSettings?): Section? {
        val block = dashboard.cameraIdentification ?: return null
        val stated = record?.camera ?: return null
        val chosen = stated.camera
        return Section(
            cameras = block.cameras,
            chosen = chosen,
            cameraName = chosen?.let { camera -> block.cameras.firstOrNull { it.entityId == camera.cameraEntityId }?.name ?: camera.cameraEntityId },
            frameDrawn = chosen != null && !CameraFrames.isWhole(chosen.frame)
        )
    }

    /**
     * A car's reference pictures, day first, when a camera is chosen and the car is one of this
     * charger's; `null` (no row) otherwise.
     */
    fun references(dashboard: Dashboard, record: HaPlanningSettings?, vehicleId: String): List<ReferencePicture>? {
        val block = dashboard.cameraIdentification ?: return null
        record?.camera?.camera ?: return null
        return block.references[vehicleId]
    }

    /** When a picture was taken, as its caption says it ("Wed 7 Oct 14:12"), in [zone]; as sent when unreadable. */
    fun takenAt(iso: String, zone: ZoneId, locale: Locale): String = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale))
    } catch (failure: Exception) {
        iso
    }
}
