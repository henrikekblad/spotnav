package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.planning.ChargeNeed
import se.sensnology.spotnav.vehicles.VehicleFacts
import se.sensnology.spotnav.vehicles.VehicleStatus
import kotlin.math.roundToInt

/**
 * What the target slider's track is shaded with, and where its two end labels go — the decisions
 * the drawing cannot make for itself.
 */
object TargetShading {
    /**
     * The track's shading, as fractions of its width, and the two numbers its ends are labelled
     * with.
     */
    data class Shading(
        /** How much of the track is shaded from the left: the car's own charge. */
        val below: Float,
        /** Where the shading above the limit begins, or `null` when none is known. */
        val above: Float?,
        /** The lower end's own number, or `null` when there is no floor to state. */
        val floorPercent: Int?,
        /** The upper end's own number, or `null` when no limit is known. */
        val ceilingPercent: Int?
    )

    /** The shading for [vehicle], or no shading at all when there is none. */
    fun of(vehicle: VehicleStatus?): Shading {
        if (vehicle == null) return Shading(below = 0f, above = null, floorPercent = null, ceilingPercent = null)
        val band = ChargeNeed.targetRange(vehicle.socPercent, vehicle.targetSocPercentMax)
        val limit = VehicleFacts.chargeLimit(vehicle)
        return Shading(
            below = band.currentPercent / 100f,
            above = if (limit == null) null else band.limitPercent / 100f,
            floorPercent = band.currentPercent,
            ceilingPercent = if (limit == null) null else band.limitPercent
        )
    }

    /**
     * Where the two end labels go, in pixels, for a track [trackWidth] wide, and which of them
     * exists at all: `null` is "do not draw this one", because it would collide with the other, or
     * because nothing is known to label.
     */
    fun labels(
        trackWidth: Int,
        floorWidth: Int,
        ceilingWidth: Int,
        shading: Shading,
        gap: Int
    ): Labels {
        if (trackWidth <= 0) return Labels(floorX = null, ceilingX = null)
        val floorPercent = shading.floorPercent
        if (floorPercent == null) return Labels(floorX = null, ceilingX = null)
        val ceilingX = ((shading.above ?: 1f) * trackWidth).roundToInt()
            .coerceIn(0, (trackWidth - ceilingWidth).coerceAtLeast(0))
        val ceilingPercent = shading.ceilingPercent
        val floorX = when {
            ceilingPercent == null -> floorAt(floorPercent, trackWidth, floorWidth, ceilingX, gap)
            floorPercent >= ceilingPercent -> null
            else -> floorAt(floorPercent, trackWidth, floorWidth, ceilingX, gap)
        }
        return Labels(floorX = floorX, ceilingX = ceilingX)
    }

    /**
     * The floor label's own left edge: at its fraction, unless that would reach into the ceiling's
     * label, in which case it stops [gap] short of it. `null` when even that is off the track's
     * left edge.
     */
    private fun floorAt(percent: Int, trackWidth: Int, floorWidth: Int, ceilingX: Int, gap: Int): Int? {
        val wanted = (percent / 100f * trackWidth).roundToInt() - floorWidth
        val clear = ceilingX - gap - floorWidth
        val at = minOf(wanted, clear)
        return if (at < 0) null else at
    }

    /** Where the two end labels are drawn, in pixels; `null` means "not at all". */
    data class Labels(val floorX: Int?, val ceilingX: Int?)
}
