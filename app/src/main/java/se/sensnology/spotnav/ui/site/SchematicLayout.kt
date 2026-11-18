package se.sensnology.spotnav.ui.site

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point of the schematic, in dp from its top left. */
internal data class Spot(val x: Float, val y: Float)

/** Where a line's kW figure stands: its anchor point and which side of it the text runs to. */
internal data class LabelPlace(val at: Spot, val align: Align) {
    enum class Align { START, MIDDLE, END }
}

/** One line as drawn: from where the power comes to where it goes, its middle, its angle and its figure's place. */
internal data class DrawnLine(val from: Spot, val to: Spot, val mid: Spot, val angleDegrees: Float, val length: Float, val label: LabelPlace)

/**
 * The geometry of the Now schematic, the Home Assistant card's own (`frontend/src/house-now.ts`): the house a little
 * above the middle, the sun and the grid above it, the battery and the car below, the heat straight under it, each in
 * a band between the corners' words. A house with the grid alone is one low row. All in dp.
 */
internal object SchematicLayout {
    const val ROW_HEIGHT = 90f
    const val NODE_R = 16f
    const val HEAT_R = 14f
    const val HOUSE_R = 30f
    const val ROW_HOUSE_R = 22f
    /** A dot every this far along a line. */
    const val DOT_GAP = 9.1f

    /** The schematic's height for its width. */
    fun height(width: Float, alone: Boolean): Float =
        if (alone) ROW_HEIGHT else min(344f, max(296f, width * 0.42f)).roundToInt().toFloat()

    fun houseRadius(alone: Boolean): Float = if (alone) ROW_HOUSE_R else HOUSE_R

    fun radius(role: NodeRole?, alone: Boolean): Float = when (role) {
        null -> houseRadius(alone)
        NodeRole.HEAT -> HEAT_R
        else -> NODE_R
    }

    /** Where each node's disc sits (`null` is the house). */
    fun spots(width: Float, height: Float, alone: Boolean): Map<NodeRole?, Spot> {
        if (alone) {
            return mapOf(NodeRole.GRID to Spot(NODE_R + 2f, height - 26f), null to Spot(width - HOUSE_R + 4f, height - 26f))
        }
        val side = max(48f, min(width * 0.17f, 150f))
        val top = 38f + (height - 76f) * 0.2f
        val bottom = height - 38f - (height - 76f) * 0.2f
        return mapOf(
            null to Spot(width / 2f, height * 0.45f),
            NodeRole.SUN to Spot(side, top),
            NodeRole.GRID to Spot(width - side, top),
            NodeRole.BATTERY to Spot(side, bottom),
            NodeRole.CAR to Spot(width - side, bottom),
            NodeRole.HEAT to Spot(width / 2f, bottom + 16f)
        )
    }

    /**
     * One line between its node and the house, drawn the way the power flows (so the dots run with it), with its
     * kW figure beside the middle: above the line, the heat's short line to its right.
     */
    fun line(role: NodeRole, way: Way, spots: Map<NodeRole?, Spot>, alone: Boolean): DrawnLine {
        val node = spots.getValue(role)
        val house = spots.getValue(null)
        val dx = house.x - node.x
        val dy = house.y - node.y
        val whole = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val fromR = NODE_R + 3f
        val toR = houseRadius(alone) + 3f
        val a = Spot(node.x + dx / whole * fromR, node.y + dy / whole * fromR)
        val b = Spot(house.x - dx / whole * toR, house.y - dy / whole * toR)
        val (from, to) = if (way == Way.OUT) b to a else a to b
        val mid = Spot((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        val length = hypot(to.x - from.x, to.y - from.y)
        var nx = -(to.y - from.y) / (length.takeIf { it > 0f } ?: 1f)
        var ny = (to.x - from.x) / (length.takeIf { it > 0f } ?: 1f)
        when {
            role == NodeRole.HEAT -> { nx = 1f; ny = 0f }
            alone -> { nx = 0f; ny = -1f }
            ny > 0f -> { nx = -nx; ny = -ny }
        }
        val offset = if (role == NodeRole.HEAT) 10f else 13f
        val align = when {
            abs(nx) < 0.35f -> LabelPlace.Align.MIDDLE
            nx > 0f -> LabelPlace.Align.START
            else -> LabelPlace.Align.END
        }
        return DrawnLine(
            from = from, to = to, mid = mid,
            angleDegrees = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat(),
            length = length,
            label = LabelPlace(Spot(mid.x + nx * offset, mid.y + ny * offset), align)
        )
    }

    /**
     * How far along a line each dot is, for a [phase] of 0 to 1 through one gap: the dots run from the line's start
     * to its end, one gap apart.
     */
    fun dots(length: Float, phase: Float): List<Float> {
        val out = ArrayList<Float>()
        var at = (phase - Math.floor(phase.toDouble()).toFloat()) * DOT_GAP
        while (at <= length) {
            out.add(at)
            at += DOT_GAP
        }
        return out
    }
}
