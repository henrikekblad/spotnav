package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The Now schematic's geometry, as the Home Assistant card draws it (`frontend/src/house-now.ts`). */
class SchematicLayoutTest {
    @Test fun theHeightFollowsTheWidthWithinItsBoundsAndAHouseAloneIsOneLowRow() {
        assertEquals(296f, SchematicLayout.height(340f, alone = false))
        assertEquals(315f, SchematicLayout.height(750f, alone = false))
        assertEquals(344f, SchematicLayout.height(1200f, alone = false))
        assertEquals(90f, SchematicLayout.height(340f, alone = true))
    }

    @Test fun theHouseIsInTheMiddleTheOthersAroundItAndTheHeatWellBelow() {
        val height = SchematicLayout.height(340f, alone = false)
        val spots = SchematicLayout.spots(340f, height, alone = false)
        val house = spots.getValue(null)
        assertEquals(170f, house.x)
        assertTrue(spots.getValue(NodeRole.SUN).x < house.x && spots.getValue(NodeRole.SUN).y < house.y)
        assertTrue(spots.getValue(NodeRole.GRID).x > house.x && spots.getValue(NodeRole.GRID).y < house.y)
        assertTrue(spots.getValue(NodeRole.BATTERY).x < house.x && spots.getValue(NodeRole.BATTERY).y > house.y)
        assertTrue(spots.getValue(NodeRole.CAR).x > house.x && spots.getValue(NodeRole.CAR).y > house.y)
        // The heat's line straight down is long enough to show its flow.
        assertEquals(house.x, spots.getValue(NodeRole.HEAT).x)
        assertTrue(spots.getValue(NodeRole.HEAT).y - house.y >= 90f)
        // Every disc is inside the picture, clear of the corners' words above and below.
        for ((role, spot) in spots) {
            val r = SchematicLayout.radius(role, alone = false)
            assertTrue("$role", spot.x - r >= 0f && spot.x + r <= 340f && spot.y - r >= 43f && spot.y + r <= height)
        }
    }

    @Test fun aHouseAloneHasTheGridAtTheLeftAndTheHouseAtTheRight() {
        val spots = SchematicLayout.spots(340f, 90f, alone = true)
        assertEquals(setOf(null, NodeRole.GRID), spots.keys)
        assertTrue(spots.getValue(NodeRole.GRID).x < spots.getValue(null).x)
        assertEquals(spots.getValue(NodeRole.GRID).y, spots.getValue(null).y)
        val line = SchematicLayout.line(NodeRole.GRID, Way.IN, spots, alone = true)
        // The figure stands above the line's middle.
        assertEquals(LabelPlace.Align.MIDDLE, line.label.align)
        assertTrue(line.label.at.y < line.mid.y)
    }

    @Test fun aLineIsDrawnTheWayThePowerFlowsBetweenTheDiscsEdges() {
        val spots = SchematicLayout.spots(340f, 296f, alone = false)
        val house = spots.getValue(null)
        val grid = spots.getValue(NodeRole.GRID)
        val buying = SchematicLayout.line(NodeRole.GRID, Way.IN, spots, alone = false)
        val selling = SchematicLayout.line(NodeRole.GRID, Way.OUT, spots, alone = false)
        // Towards the house when buying, away from it when selling: the same two ends, swapped.
        assertEquals(buying.from, selling.to)
        assertEquals(buying.to, selling.from)
        assertEquals(SchematicLayout.NODE_R + 3f, hypot(buying.from.x - grid.x, buying.from.y - grid.y), 0.01f)
        assertEquals(SchematicLayout.HOUSE_R + 3f, hypot(buying.to.x - house.x, buying.to.y - house.y), 0.01f)
        assertEquals(buying.length, selling.length, 0.001f)
        // The arrowhead turns with the way.
        assertEquals(180f, Math.abs(buying.angleDegrees - selling.angleDegrees), 0.01f)
        // The figure is above its line whichever way the power runs, never on it.
        assertTrue(buying.label.at.y < buying.mid.y)
        assertEquals(buying.label, selling.label)
        // A still line is drawn like one towards the house.
        assertEquals(buying, SchematicLayout.line(NodeRole.GRID, Way.STILL, spots, alone = false))
    }

    @Test fun theHeatsFigureStandsToTheRightOfItsShortLine() {
        val spots = SchematicLayout.spots(340f, 296f, alone = false)
        val heat = SchematicLayout.line(NodeRole.HEAT, Way.OUT, spots, alone = false)
        assertEquals(LabelPlace.Align.START, heat.label.align)
        assertEquals(heat.mid.y, heat.label.at.y)
        assertTrue(heat.label.at.x > heat.mid.x)
        // The lower lines' figures run away from the middle, clear of it.
        assertEquals(LabelPlace.Align.END, SchematicLayout.line(NodeRole.BATTERY, Way.IN, spots, alone = false).label.align)
        assertEquals(LabelPlace.Align.START, SchematicLayout.line(NodeRole.CAR, Way.OUT, spots, alone = false).label.align)
    }

    @Test fun theDotsStandOneGapApartAndRunFromTheLinesStart() {
        val still = SchematicLayout.dots(30f, 0f)
        assertEquals(listOf(0f, 9.1f, 18.2f, 27.3f), still.map { Math.round(it * 10) / 10f })
        val half = SchematicLayout.dots(30f, 0.5f)
        assertEquals(4.55f, half.first(), 0.001f)
        assertTrue(half.all { it in 0f..30f })
        // A whole lap is where it began: the dots never jump.
        assertEquals(still, SchematicLayout.dots(30f, 1f))
        assertEquals(half.size, SchematicLayout.dots(30f, 2.5f).size)
    }
}
