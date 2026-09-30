package se.sensnology.spotnav.chart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a tap that begins somewhere else puts the chart's readout away. */
class ChartDismissalTest {
    private val chart = ViewBounds(left = 100f, top = 200f, right = 500f, bottom = 400f)
    private val callout = ViewBounds(left = 100f, top = 410f, right = 500f, bottom = 460f)

    private fun clears(selectionActive: Boolean = true, calloutBounds: ViewBounds? = callout, x: Float, y: Float) =
        ChartDismissal.clearsSelection(selectionActive, chart, calloutBounds, x, y)

    @Test
    fun aTapInsideTheChartIsStillTheChartsOwnBusiness() {
        assertFalse(clears(x = 300f, y = 300f))
        assertFalse(clears(x = chart.left, y = chart.top))
        assertFalse(clears(x = 100f, y = 201f))
        assertFalse(clears(x = 499f, y = 399f))
    }

    @Test
    fun aTapOnTheReadoutIsTheReadoutsOwnBusiness() {
        assertFalse(clears(x = 300f, y = 430f))
        assertFalse(clears(x = callout.left, y = callout.top))
    }

    @Test
    fun aTapAnywhereElsePutsTheReadoutAway() {
        assertTrue("the gap between the two is outside both", clears(x = 300f, y = 405f))
        assertTrue(clears(x = 300f, y = 100f))
        assertTrue(clears(x = 300f, y = 900f))
        assertTrue(clears(x = 10f, y = 300f))
        assertTrue(clears(x = 1000f, y = 300f))
        // And the edges just outside each box: the framework's own half-open rule.
        assertTrue(clears(x = chart.left - 1f, y = 300f))
        assertTrue(clears(x = chart.right, y = 300f))
        assertTrue(clears(x = 300f, y = callout.bottom))
    }

    @Test
    fun aHiddenReadoutLeavesNothingToSpareSoTheSameTapStillClears() {
        assertTrue(clears(calloutBounds = null, x = 300f, y = 430f))
        // And the graph keeps its own exemption either way.
        assertFalse(clears(calloutBounds = null, x = 300f, y = 300f))
    }

    @Test
    fun withNothingSelectedThereIsNothingToDismiss() {
        assertFalse(clears(selectionActive = false, x = 300f, y = 900f))
        assertFalse(clears(selectionActive = false, x = 300f, y = 300f))
        assertFalse(clears(selectionActive = false, calloutBounds = null, x = 300f, y = 430f))
    }

    @Test
    fun aBoxOfNoSizeHoldsNothing() {
        val empty = ViewBounds(0f, 0f, 0f, 0f)
        assertFalse(empty.contains(0f, 0f))
        assertTrue(clears(calloutBounds = empty, x = 0f, y = 0f))
        assertTrue(ChartDismissal.clearsSelection(true, ViewBounds(0f, 0f, 10f, 10f), empty, 20f, 20f))
    }


    @Test
    fun aGestureThatBeganOutsidePutsTheReadoutAwayWhenItEnds() {
        assertTrue(ChartDismissal.clearsOnUp(beganOnGeneration = 7, generation = 7))
    }

    @Test
    fun aGestureThatBeganOnTheChartOrItsReadoutClearsNothingWhenItEnds() {
        // Inside neither is remembered as such: `null` is "this gesture was never armed".
        assertFalse(ChartDismissal.clearsOnUp(beganOnGeneration = null, generation = 7))
    }

    @Test
    fun aCancelledGestureEndsWithNoClearAtAll() {
        val armed = 7
        assertFalse(ChartDismissal.clearsOnUp(beganOnGeneration = null, generation = armed))
        assertTrue(ChartDismissal.clearsOnUp(beganOnGeneration = armed, generation = armed))
    }

    @Test
    fun aGestureThatOutlivedItsScreenClearsNothingOnTheNextOne() {
        assertFalse(ChartDismissal.clearsOnUp(beganOnGeneration = 7, generation = 8))
        assertFalse(ChartDismissal.clearsOnUp(beganOnGeneration = 8, generation = 7))
    }
}
