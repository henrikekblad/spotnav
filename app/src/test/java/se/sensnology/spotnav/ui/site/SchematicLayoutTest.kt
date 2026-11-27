package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The Now drawing's geometry against the card's own (`frontend/test/house-tabs.test.ts`, `now-scale.test.ts`). */
class SchematicLayoutTest {
    private val all = listOf(NodeRole.SUN, NodeRole.GRID, NodeRole.BATTERY, NodeRole.CAR)

    @Test fun oneOrTwoOuterNodesStandInARowAndThreeOrFourAtFixedCorners() {
        // Each outer node as far out as its drawing allows: its reach and 6 dp off the edge.
        val alone = SchematicLayout.layout(listOf(NodeRole.GRID), 360f)
        assertEquals(LayoutKind.ROW, alone.kind)
        assertEquals(150f, alone.height)
        assertEquals(Spot(31f, 75f), alone.spots[NodeRole.GRID])
        assertEquals(Spot(180f, 75f), alone.spots[null])
        assertEquals(200f, alone.drawn)
        assertEquals(20f, alone.top)
        val two = SchematicLayout.layout(listOf(NodeRole.CAR, NodeRole.GRID), 360f)
        assertEquals(LayoutKind.ROW, two.kind)
        assertEquals(Spot(31f, 75f), two.spots[NodeRole.GRID])
        assertEquals(Spot(329f, 75f), two.spots[NodeRole.CAR])
        val three = SchematicLayout.layout(listOf(NodeRole.SUN, NodeRole.GRID, NodeRole.CAR), 360f)
        assertEquals(LayoutKind.CORNERS, three.kind)
        assertEquals(Spot(31f, 52f), three.spots[NodeRole.SUN])
        assertEquals(Spot(329f, 52f), three.spots[NodeRole.GRID])
        assertEquals(329f, three.spots.getValue(NodeRole.CAR).x)
        // The diagonals flat: the rise to the house 0.45 of the run, the same down to the bottom corners.
        val house = three.spots.getValue(null)
        val sun = three.spots.getValue(NodeRole.SUN)
        assertEquals(0.45f, (house.y - sun.y) / (house.x - sun.x), 0.01f)
        assertEquals(house.y - sun.y, three.spots.getValue(NodeRole.CAR).y - house.y, 0.01f)
        // A missing corner stays empty; with the battery the corners make room for its level ring.
        assertNull(three.spots[NodeRole.BATTERY])
        assertEquals(37f, SchematicLayout.layout(all, 360f).spots.getValue(NodeRole.BATTERY).x)
    }

    @Test fun aWiderBoxDrawsEverythingLargerUpTo1point6AndPushesTheCornersOut() {
        assertEquals(1f, SchematicLayout.scale(300f))
        assertEquals(1f, SchematicLayout.scale(360f))
        assertEquals(1.111f, SchematicLayout.scale(400f), 0.001f)
        assertEquals(1.6f, SchematicLayout.scale(640f))
        assertEquals(1.6f, SchematicLayout.scale(900f))
        assertEquals(13, SchematicLayout.fontSizes(SchematicLayout.scale(400f)).first)
        assertEquals(8.8f * 1.2f, SchematicLayout.lineWidth(4.0, 1.2f), 1e-4f)
        val narrow = SchematicLayout.layout(all, 360f)
        val wide = SchematicLayout.layout(all, 400f)
        assertTrue(wide.spots.getValue(NodeRole.GRID).x - wide.spots.getValue(NodeRole.SUN).x > narrow.spots.getValue(NodeRole.GRID).x - narrow.spots.getValue(NodeRole.SUN).x)
        assertTrue(wide.height > narrow.height)
        // The drawing is between 300 and 640 dp wide whatever the box.
        assertEquals(300f, SchematicLayout.drawingWidth(280f))
        assertEquals(332f, SchematicLayout.drawingWidth(332f))
        assertEquals(640f, SchematicLayout.drawingWidth(800f))
    }

    @Test fun aLinesWidthIsTheSquareRootOfItsPowerAndItEndsAtEachNodesOuterEdge() {
        assertEquals(1.5f, SchematicLayout.lineWidth(0.0))
        assertEquals(1.5f, SchematicLayout.lineWidth(0.04))
        assertEquals(1.5f, SchematicLayout.lineWidth(null))
        assertEquals(5.4f, SchematicLayout.lineWidth(1.0), 1e-4f)
        assertEquals(8.8f, SchematicLayout.lineWidth(4.0), 1e-4f)
        assertEquals(8.8f, SchematicLayout.lineWidth(-4.0), 1e-4f)
        // Never under the battery's ring or the house's: from 31 dp off the battery's centre to 51 dp off the house's.
        val ends = SchematicLayout.lineEnds(Spot(46f, 248f), Spot(180f, 150f), SchematicLayout.outer(NodeRole.BATTERY), SchematicLayout.outer(null))
        assertEquals(31f, hypot(ends.a.x - 46f, ends.a.y - 248f), 1e-3f)
        assertEquals(51f, hypot(ends.b.x - 180f, ends.b.y - 150f), 1e-3f)
    }

    @Test fun theChevronsRunFasterWithMorePowerAndAThinLineHasNone() {
        assertNull(SchematicLayout.chevronScale(0.1))
        assertTrue(SchematicLayout.chevronScale(1.0)!! * 9 <= SchematicLayout.lineWidth(1.0) * 0.7f + 1e-6f)
        assertEquals(1.25f, SchematicLayout.chevronScale(30.0))
        assertTrue(SchematicLayout.chevronSpeed(6.0) > SchematicLayout.chevronSpeed(1.0))
        assertEquals(2, SchematicLayout.chevronCount(10f, 1f))
        assertEquals(7, SchematicLayout.chevronCount(500f, 1f))
        assertEquals(5, SchematicLayout.chevronCount(70f, 1f))
    }

    @Test fun theKwStandsAboveAHorizontalLineAndOnTheOuterSideOfADiagonal() {
        val flat = SchematicLayout.labelPlace(Spot(69f, 75f), Spot(129f, 75f), 1.0, 180f)
        assertTrue(flat.above)
        assertEquals(75f - (SchematicLayout.lineWidth(1.0) / 2 + 10) + 4, flat.at.y, 1e-3f)
        assertEquals(LabelPlace.Align.MIDDLE, flat.align)
        // The sun's line (top left to the house): its label up and to the left, anchored at its end.
        val sun = SchematicLayout.labelPlace(Spot(70f, 70f), Spot(140f, 120f), 1.2, 180f)
        assertTrue(!sun.above)
        assertTrue(sun.at.x < 105f)
        assertEquals(LabelPlace.Align.END, sun.align)
        // The car's line (house to bottom right): on its outer side, to the right.
        val car = SchematicLayout.labelPlace(Spot(220f, 180f), Spot(290f, 230f), 7.4, 180f)
        assertTrue(car.at.x > 255f)
        assertEquals(LabelPlace.Align.START, car.align)
    }

    @Test fun aCaptionNearAnEdgeIsAnchoredInwardAndLongCaptionsAreWrittenSmaller() {
        assertEquals(4f to LabelPlace.Align.START, SchematicLayout.captionAnchor(37f, 360f))
        assertEquals(356f to LabelPlace.Align.END, SchematicLayout.captionAnchor(323f, 360f))
        assertEquals(180f to LabelPlace.Align.MIDDLE, SchematicLayout.captionAnchor(180f, 360f))
        val layout = SchematicLayout.layout(all, 360f)
        val short = all.associateWith { Captions("Sol", "7,5 kWh i dag") }
        assertEquals(12, SchematicLayout.captionSize(12, layout, short))
        val long = all.associateWith { Captions("Netz, im Leerlauf, ganz lange Worte", "gekauft 76,9 · verkauft 0,1 kWh") }
        val size = SchematicLayout.captionSize(12, layout, long)
        assertTrue("$size", size in 9 until 12)
        assertEquals(9, SchematicLayout.captionSize(12, layout, all.associateWith { Captions("x".repeat(200), "") }))
    }
}
