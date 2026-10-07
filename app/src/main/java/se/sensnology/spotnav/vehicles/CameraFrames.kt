package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.ha.settings.HaCameraRules
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The frame editor's geometry, as the Home Assistant card has it: the frame (fractions of the picture)
 * moved or resized by a finger, kept inside the picture and at least [MIN_SIZE] each way, rounded as
 * Home Assistant keeps it; the part of the picture the preview shows; and how far a picture is scaled
 * down when it is decoded.
 */
internal object CameraFrames {
    const val MIN_SIZE = HaCameraRules.FRAME_MIN_SIZE
    val WHOLE = CameraFrame(0.0, 0.0, 1.0, 1.0)

    /** What a touch holds: the whole frame, or one of its corners (the opposite one stays). */
    enum class Grip { MOVE, NW, NE, SW, SE }

    /** A rectangle of pixels, [right] and [bottom] exclusive. */
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun round(value: Double): Double = Math.round(value * 10000) / 10000.0

    /** The frame inside the picture and at least [MIN_SIZE] each way, rounded to four decimals. */
    fun clamp(frame: CameraFrame): CameraFrame {
        val w = frame.w.coerceIn(MIN_SIZE, 1.0)
        val h = frame.h.coerceIn(MIN_SIZE, 1.0)
        val x = frame.x.coerceAtLeast(0.0).coerceAtMost(1.0 - w)
        val y = frame.y.coerceAtLeast(0.0).coerceAtMost(1.0 - h)
        return CameraFrame(round(x), round(y), round(w), round(h))
    }

    /** The frame moved by [dx], [dy] (fractions of the picture), its size kept, never past an edge. */
    fun move(frame: CameraFrame, dx: Double, dy: Double): CameraFrame = clamp(frame.copy(x = frame.x + dx, y = frame.y + dy))

    /** [start] as a drag of [grip] by [dx], [dy] leaves it. */
    fun drag(start: CameraFrame, grip: Grip, dx: Double, dy: Double): CameraFrame {
        if (grip == Grip.MOVE) return move(start, dx, dy)
        var left = start.x
        var top = start.y
        var right = start.x + start.w
        var bottom = start.y + start.h
        if (grip == Grip.NW || grip == Grip.SW) {
            left = (left + dx).coerceAtLeast(0.0).coerceAtMost(right - MIN_SIZE)
        } else {
            right = (right + dx).coerceAtMost(1.0).coerceAtLeast(left + MIN_SIZE)
        }
        if (grip == Grip.NW || grip == Grip.NE) {
            top = (top + dy).coerceAtLeast(0.0).coerceAtMost(bottom - MIN_SIZE)
        } else {
            bottom = (bottom + dy).coerceAtMost(1.0).coerceAtLeast(top + MIN_SIZE)
        }
        return clamp(CameraFrame(left, top, right - left, bottom - top))
    }

    /** Whether a frame is the whole picture (as good as); none is. */
    fun isWhole(frame: CameraFrame?): Boolean =
        frame == null || (frame.x <= 0.0001 && frame.y <= 0.0001 && frame.w >= 0.9999 && frame.h >= 0.9999)

    /** What Save sends: `null` for the whole picture, else the frame as Home Assistant keeps it. */
    fun forSave(frame: CameraFrame): CameraFrame? = if (isWhole(frame)) null else clamp(frame)

    /**
     * What a touch at ([x], [y]) holds: the corner nearest it within reach ([reachX], [reachY], fractions
     * of the picture), else the frame when it is inside, else nothing.
     */
    fun grip(frame: CameraFrame, x: Double, y: Double, reachX: Double, reachY: Double): Grip? {
        val corners = listOf(
            Grip.NW to (frame.x to frame.y),
            Grip.NE to (frame.x + frame.w to frame.y),
            Grip.SW to (frame.x to frame.y + frame.h),
            Grip.SE to (frame.x + frame.w to frame.y + frame.h)
        )
        val near = corners
            .filter { (_, at) -> abs(x - at.first) <= reachX && abs(y - at.second) <= reachY }
            .minByOrNull { (_, at) -> hypot((x - at.first) / reachX, (y - at.second) / reachY) }
        if (near != null) return near.first
        val inside = x >= frame.x && x <= frame.x + frame.w && y >= frame.y && y <= frame.y + frame.h
        return if (inside) Grip.MOVE else null
    }

    /** The frame's part of a [width] by [height] picture: never empty, never outside it. */
    fun crop(frame: CameraFrame, width: Int, height: Int): PixelRect {
        val left = (frame.x * width).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val top = (frame.y * height).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val right = ((frame.x + frame.w) * width).roundToInt().coerceAtMost(width).coerceAtLeast(left + 1)
        val bottom = ((frame.y + frame.h) * height).roundToInt().coerceAtMost(height).coerceAtLeast(top + 1)
        return PixelRect(left, top, right, bottom)
    }

    /** The power of two a [width] by [height] picture is divided by so its longer side is at most [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        val longer = maxOf(width, height)
        var size = 1
        while (maxEdge > 0 && longer / size > maxEdge) size *= 2
        return size
    }
}
