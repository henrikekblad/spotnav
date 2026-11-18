package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The energy flow's Sankey diagram, placed: the sources on the left, the uses on the right, each a block as tall as
 * its share of the energy, and a curved band from each source to each use as thick as the energy between them. The
 * Home Assistant card's own rules (`frontend/src/house-flow.ts`, whose header states them), ported as they are; every
 * figure is the backend's, this only places them. All sizes are density-independent pixels. Pure: [SankeyView] draws it.
 *
 * - A block is never shorter than [MIN_NODE] (its words must fit); the rest of the height is shared by energy. Every
 *   band has one scale (the fullest block's), so a band is as thick at both ends.
 * - A block below [MIN_SHOWN_KWH] is left out with its bands, and so is a block left with no band: no block stands
 *   alone. A band is never thinner than [MIN_BAND].
 * - A column grows as wide as its widest pill (the name and the share) needs, taking the width from the bands between
 *   the columns before any word is squeezed.
 */
internal object SankeyLayout {
    /** The shortest block (px): the name's pill and one line. */
    const val MIN_NODE = 44.0

    /** From this height a block has the kWh big under its pill (and the battery's level at its bottom). */
    const val TALL_NODE = 64.0
    const val PAD = 6.0
    const val PILL_H = 17.0

    /** The room each side of the pill's words. */
    const val PILL_PAD = 8.0
    const val MIN_SHOWN_KWH = 0.05
    const val MIN_BAND = 1.5
    const val GAP = 6.0

    /** The bands between the columns keep at least this share of the width, and this many px. */
    private const val BAND_SHARE = 0.22
    private const val BAND_MIN = 56.0

    /** A pill's words where they cannot be measured (px per character of the 12 px semi-bold name). */
    private const val NAME_CHAR_PX = 6.3

    /** The height each block of the fuller side asks of the diagram. */
    private const val BLOCK_ROOM = 80.0

    /** The tab's own diagram is not taller than this. */
    const val TAB_FLOW_PX = 320.0

    class Block(val node: House.FlowNode, val x: Double, val y: Double, val w: Double, val h: Double)

    /** [a0]-[a1] is the band's extent at its source block, [b0]-[b1] at its use. */
    class Band(val link: House.FlowLink, val from: Block, val to: Block, val a0: Double, val a1: Double, val b0: Double, val b1: Double)

    class Layout(
        val width: Double,
        val height: Double,
        val sources: List<Block>,
        val uses: List<Block>,
        val bands: List<Band>,
        /** Where the bands start and end (the inner edges of the columns). */
        val x0: Double,
        val x1: Double,
        /** The pill's width each block needs for its words, by node id. */
        val pillWidths: Map<String, Double>
    ) {
        val blocks: List<Block> get() = sources + uses
    }

    /** The diagram's height: by the width, and tall enough that each block of the fuller side may get the tall form; bounded. */
    fun flowHeight(width: Double, blocks: Int = 3): Int {
        val bounded = if (width.isFinite() && width > 0) width else 340.0
        val byWidth = min(340.0, max(250.0, bounded * 0.72))
        return min(440.0, max(byWidth, blocks * BLOCK_ROOM)).roundToInt()
    }

    private fun nodeWidth(width: Double): Double = min(120.0, max(84.0, width * 0.27)).roundToInt().toDouble()

    /**
     * The two columns' widths: each at least the usual one and wide enough for its widest pill with its padding, taken
     * from the bands between them; where the bands would get too narrow both give way in proportion.
     */
    fun columnWidths(width: Double, widestLeft: Double, widestRight: Double): Pair<Double, Double> {
        val usual = nodeWidth(width)
        fun want(widest: Double) = max(usual, ceil(widest + 2 * PILL_PAD + 2 * PAD))
        val left = want(widestLeft)
        val right = want(widestRight)
        val room = width - max(BAND_MIN, width * BAND_SHARE)
        if (left + right <= room) return left to right
        val over = left + right - room
        val spare = left + right - 2 * usual
        if (spare <= 0) return usual to usual
        return max(usual, floor(left - over * (left - usual) / spare)) to max(usual, floor(right - over * (right - usual) / spare))
    }

    /** Each block's height: at least [MIN_NODE], the rest by energy. */
    fun blockHeights(values: List<Double>, height: Double): List<Double> {
        val room = height - GAP * max(0, values.size - 1)
        val fixed = HashSet<Int>()
        while (true) {
            val free = values.indices.filter { it !in fixed }.sumOf { values[it] }
            val left = room - fixed.size * MIN_NODE
            var changed = false
            values.forEachIndexed { index, value ->
                if (index !in fixed && (free <= 0 || value / free * left < MIN_NODE)) {
                    fixed.add(index)
                    changed = true
                }
            }
            if (!changed) return values.mapIndexed { index, value -> if (index in fixed) MIN_NODE else value / free * left }
        }
    }

    /**
     * The blocks the diagram draws: those with energy ([MIN_SHOWN_KWH]) that a band with energy runs to or from, in
     * the answer's order. Empty on a side means nothing can be drawn.
     */
    fun shown(flow: House.EnergyFlow): List<House.FlowNode> {
        val big = flow.nodes.filter { it.kwh >= MIN_SHOWN_KWH }.map { it.id }.toSet()
        val linked = flow.links.filter { it.kwh > 0 && it.from in big && it.to in big }.flatMap { listOf(it.from, it.to) }.toSet()
        return flow.nodes.filter { it.id in linked }
    }

    /** Whether [flow] has anything to draw as a diagram. */
    fun drawable(flow: House.EnergyFlow): Boolean = shown(flow).let { nodes -> nodes.any { it.source } && nodes.any { !it.source } }

    /**
     * The diagram [width] wide; `null` when nothing is left to draw. [fitHeight] a height of its own (never below what
     * the blocks' words need), else by the width. [pillWords] is what a block's pill says; [measure] its width as drawn
     * (`null`: not measurable, estimated).
     */
    fun layout(
        flow: House.EnergyFlow,
        width: Double,
        fitHeight: Double?,
        pillWords: (House.FlowNode) -> String,
        measure: (String) -> Double?
    ): Layout? {
        val nodes = shown(flow)
        val sources = nodes.filter { it.source }
        val uses = nodes.filter { !it.source }
        if (sources.isEmpty() || uses.isEmpty()) return null
        val blocks = max(sources.size, uses.size)
        val height = if (fitHeight == null) flowHeight(width, blocks).toDouble()
        else max(fitHeight, blocks * (MIN_NODE + GAP) + GAP).roundToInt().toDouble()
        val pillWidths = nodes.associate { node ->
            val words = pillWords(node)
            node.id to (measure(words)?.takeIf { it > 0 } ?: (words.length * NAME_CHAR_PX))
        }
        fun widest(list: List<House.FlowNode>) = list.maxOfOrNull { pillWidths.getValue(it.id) } ?: 0.0
        val (leftW, rightW) = columnWidths(width, widest(sources), widest(uses))
        val x1 = width - rightW
        fun place(list: List<House.FlowNode>, x: Double, w: Double): List<Block> {
            val heights = blockHeights(list.map { it.kwh }, height)
            var y = 0.0
            return list.mapIndexed { i, node -> Block(node, x, y, w, heights[i]).also { y += heights[i] + GAP } }
        }
        val left = place(sources, 0.0, leftW)
        val right = place(uses, x1, rightW)
        val leftById = left.associateBy { it.node.id }
        val rightById = right.associateBy { it.node.id }
        val links = flow.links.filter { it.from in leftById && it.to in rightById && it.kwh > 0 }
        // One scale for every band (px per kWh), the most the fullest block allows, so a band is as thick at both
        // ends; a block made taller for its words holds its bands centred.
        val scale = (left + right).filter { it.node.kwh > 0 }.minOf { it.h / it.node.kwh }
        val outAt = HashMap<House.FlowLink, Pair<Double, Double>>()
        val inAt = HashMap<House.FlowLink, Pair<Double, Double>>()
        fun stack(blocksOf: List<Block>, ends: (House.FlowLink) -> String, other: List<Block>, otherEnd: (House.FlowLink) -> String, into: HashMap<House.FlowLink, Pair<Double, Double>>) {
            val otherOrder = other.map { it.node.id }
            for (block in blocksOf) {
                val mine = links.filter { ends(it) == block.node.id }.sortedBy { otherOrder.indexOf(otherEnd(it)) }
                val used = mine.sumOf { max(MIN_BAND, it.kwh * scale) }
                var y = block.y + max(0.0, (block.h - used) / 2)
                for (link in mine) {
                    val thick = max(MIN_BAND, link.kwh * scale)
                    into[link] = y to y + thick
                    y += thick
                }
            }
        }
        stack(left, { it.from }, right, { it.to }, outAt)
        stack(right, { it.to }, left, { it.from }, inAt)
        val bands = links.map { link ->
            val (a0, a1) = outAt.getValue(link)
            val (b0, b1) = inAt.getValue(link)
            Band(link, leftById.getValue(link.from), rightById.getValue(link.to), a0, a1, b0, b1)
        }
        return Layout(width, height, left, right, bands, leftW, x1, pillWidths)
    }
}
