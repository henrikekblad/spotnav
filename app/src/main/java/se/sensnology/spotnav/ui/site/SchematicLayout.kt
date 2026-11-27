package se.sensnology.spotnav.ui.site

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point of the drawing, in dp from its top left. */
internal data class Spot(val x: Float, val y: Float)

/** Where a line's kW figure stands: its anchor point, which side of it the text runs to, and whether it is above a
 * horizontal line or on the outer side of a diagonal. */
internal data class LabelPlace(val at: Spot, val align: Align, val above: Boolean) {
    enum class Align { START, MIDDLE, END }
}

/** A line between a node and the house: from where the power comes to where it goes, its direction and its length. */
internal data class LineEnds(val a: Spot, val b: Spot, val ux: Float, val uy: Float, val length: Float)

/** Where the nodes stand: [ROW] for one or two outer nodes beside the house, [CORNERS] for three or four. */
internal enum class LayoutKind { ROW, CORNERS }

/** The drawing's layout for a width: its kind, its scale [k], the panel's height and where each node stands (`null`
 * is the house). The drawing reaches 20 dp (scaled) above the panel and 30 dp below it for the captions. */
internal data class FlowLayout(val kind: LayoutKind, val width: Float, val k: Float, val height: Float, val spots: Map<NodeRole?, Spot>) {
    /** The whole drawing's height, the captions above and below the panel included. */
    val drawn: Float get() = (height + 50f * k).roundToInt().toFloat()

    /** How far the panel's top stands below the drawing's top. */
    val top: Float get() = (20f * k).roundToInt().toFloat()
}

/**
 * The geometry of the Now drawing, the Home Assistant card's own (`frontend/src/house-now.ts`): one or two outer nodes
 * in a row beside the house, three or four at fixed corners (the sun top left, the grid top right, the battery bottom
 * left, the car bottom right); each line as wide as the square root of its power and ending at its nodes' outer
 * edges; the chevrons faster with more power; the kW beside its line; the captions anchored inward near an edge. All
 * in dp, scaled with the width from 1 at 360 dp to 1.6.
 */
internal object SchematicLayout {
    /** The room between a corner node's drawing and the drawing's edge. */
    const val NODE_EDGE = 6f
    /** The captions' room from the drawing's edge. */
    const val CAPTION_EDGE = 4f
    /** A caption's width estimate per character, in em (generous, so the words never meet). */
    const val CAPTION_EM = 0.55f
    const val NODE_R = 22f
    const val RING_R = 44f
    const val RING_W = 11f
    const val INNER_R = 37f
    const val SOC_R = 27f

    /** How far out from its centre each node's drawing reaches (`null` the house's ring): a line ends there. */
    fun outer(role: NodeRole?): Float = when (role) {
        null -> 51f
        NodeRole.BATTERY -> 31f
        else -> 25f
    }

    /** The drawing's width for the box's: between 300 and 640 dp (a narrower box draws it smaller). */
    fun drawingWidth(width: Float): Float = max(300f, min(640f, width)).roundToInt().toFloat()

    /** The drawing's scale for its width: 1 at 360 dp, up to 1.6. */
    fun scale(width: Float): Float = max(1f, min(1.6f, width / 360f))

    /** The words' sizes (whole dp): captions, kW labels and the house's figure. */
    fun fontSizes(k: Float): Triple<Int, Int, Int> = Triple((11.5f * k).roundToInt(), (13f * k).roundToInt(), (16f * k).roundToInt())

    /** Where the nodes stand: one row for one or two outer nodes, fixed corners for three or four. */
    fun layout(roles: List<NodeRole>, width: Float): FlowLayout {
        val outer = NodeRole.entries.filter { it in roles }
        val k = scale(width)
        val side = (outer.map(::outer) + outer(NodeRole.GRID)).max() * k + NODE_EDGE
        if (outer.size <= 2) {
            val spots = HashMap<NodeRole?, Spot>()
            spots[null] = Spot(width / 2f, 75f * k)
            outer.forEachIndexed { index, role -> spots[role] = Spot(if (index == 0) side else width - side, 75f * k) }
            return FlowLayout(LayoutKind.ROW, width, k, 150f * k, spots)
        }
        // The corners' rise to the house about 0.45 of their run, so the diagonals stay flat and the drawing low, never
        // less than 60 dp (scaled) so the nodes and their captions keep clear of each other and the ring.
        val top = 52f * k
        val rise = max(60f * k, 0.45f * (width / 2f - side))
        val bottom = top + 2 * rise
        val corners = mapOf(
            NodeRole.SUN to Spot(side, top), NodeRole.GRID to Spot(width - side, top),
            NodeRole.BATTERY to Spot(side, bottom), NodeRole.CAR to Spot(width - side, bottom)
        )
        val spots = HashMap<NodeRole?, Spot>()
        spots[null] = Spot(width / 2f, top + rise)
        for (role in outer) spots[role] = corners.getValue(role)
        return FlowLayout(LayoutKind.CORNERS, width, k, bottom + 36f * k, spots)
    }

    /** A line's width for its power: `(2 + √kW × 3.4) × k`; a thin idle line below the still power. */
    fun lineWidth(kw: Double?, k: Float = 1f): Float =
        (if (kw == null || abs(kw) < HouseNowModel.STILL_KW) 1.5f else 2f + sqrt(abs(kw)).toFloat() * 3.4f) * k

    /** The chevrons' scale on a line (their height at most 70 % of its width), `null` on a line too thin for them. */
    fun chevronScale(kw: Double, k: Float = 1f): Float? {
        val scale = min(1.25f * k, lineWidth(kw, k) * 0.7f / 9f)
        return if (scale < 0.3f * k) null else scale
    }

    /** The chevrons' speed (dp/s along the line): faster with more power. */
    fun chevronSpeed(kw: Double, k: Float = 1f): Float = (0.12f + sqrt(abs(kw)).toFloat() * 0.06f) * 60f * k

    /** How many chevrons a line carries: about one per 14 dp, 2 to 7. */
    fun chevronCount(length: Float, k: Float): Int = max(2, min(7, (length / (14f * k)).roundToInt()))

    /** A line between two nodes, ending at each one's outer edge. */
    fun lineEnds(from: Spot, to: Spot, fromOuter: Float, toOuter: Float): LineEnds {
        val d = hypot(to.x - from.x, to.y - from.y).takeIf { it > 0f } ?: 1f
        val ux = (to.x - from.x) / d
        val uy = (to.y - from.y) / d
        val a = Spot(from.x + ux * fromOuter, from.y + uy * fromOuter)
        val b = Spot(to.x - ux * toOuter, to.y - uy * toOuter)
        return LineEnds(a, b, ux, uy, hypot(b.x - a.x, b.y - a.y))
    }

    /** Where a line's kW stands: half its width and 10 dp off its middle, above a horizontal line and on the outer side
     * of a diagonal (away from the drawing's middle). */
    fun labelPlace(a: Spot, b: Spot, kw: Double, centreX: Float, k: Float = 1f): LabelPlace {
        val d = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: 1f
        val ux = (b.x - a.x) / d
        val uy = (b.y - a.y) / d
        var nx = -uy
        var ny = ux
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val horizontal = abs(uy) < 0.2f
        if (horizontal) {
            if (ny > 0) { nx = -nx; ny = -ny }
        } else if ((mx < centreX && nx > 0) || (mx > centreX && nx < 0)) {
            nx = -nx; ny = -ny
        }
        val off = lineWidth(kw, k) / 2f + 10f * k
        val align = if (nx < -0.2f) LabelPlace.Align.END else if (nx > 0.2f) LabelPlace.Align.START else LabelPlace.Align.MIDDLE
        return LabelPlace(Spot(mx + nx * off, my + ny * off + 4f * k), align, horizontal)
    }

    /** Where a node's captions start: anchored inward near an edge, centred in the middle. */
    fun captionAnchor(x: Float, width: Float): Pair<Float, LabelPlace.Align> = when {
        x < width / 3f -> CAPTION_EDGE to LabelPlace.Align.START
        x > width * 2f / 3f -> width - CAPTION_EDGE to LabelPlace.Align.END
        else -> x to LabelPlace.Align.MIDDLE
    }

    /** The captions' size: [wanted], or smaller (not below 9) where the captions on one line (the top corners, the
     * bottom corners, or a row's) would not fit the width beside each other. */
    fun captionSize(wanted: Int, layout: FlowLayout, words: Map<NodeRole, Captions>): Int {
        val house = layout.spots.getValue(null)
        val groups = if (layout.kind == LayoutKind.ROW) listOf(words.keys.toList()) else listOf(
            words.keys.filter { layout.spots.getValue(it).y < house.y }, words.keys.filter { layout.spots.getValue(it).y >= house.y }
        )
        var size = wanted
        for (group in groups) {
            for (kind in listOf<(Captions) -> String>({ it.state }, { it.today })) {
                val chars = group.sumOf { kind(words.getValue(it)).length }
                if (chars == 0) continue
                val room = layout.width - 2 * CAPTION_EDGE - if (group.size > 1) 16f else 0f
                size = min(size, floor(room / (CAPTION_EM * chars)).toInt())
            }
        }
        return max(9, size)
    }
}
