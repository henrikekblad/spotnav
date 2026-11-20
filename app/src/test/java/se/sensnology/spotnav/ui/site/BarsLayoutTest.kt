package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bars' arithmetic, the card's `barChart` rules: lanes reach the baseline, shares not values, signed lanes, hidden parts. */
class BarsLayoutTest {
    private fun bar(key: String, vararg lanes: List<BarPart>) = Bar(key, lanes.toList(), key, key)

    private fun part(series: String, value: Double) = BarPart(series, value)

    @Test fun aLaneReachesTheBaselineWhateverItsPartsAreWorth() {
        // An hour of 0.7 kWh alone, next to one of 7: the small lane is a tenth of the plot, from the baseline up.
        val bars = listOf(bar("a", listOf(part("x", 0.7))), bar("b", listOf(part("x", 7.0))))
        val rects = BarsLayout.rects(bars, emptySet(), 100f, 100f, 0f)
        val a = rects.first { it.bar == 0 }
        assertEquals(100f, a.bottom, 0.01f)
        assertEquals(10f, a.bottom - a.top, 0.01f)
        assertEquals(0f, rects.first { it.bar == 1 }.top, 0.01f)
    }

    @Test fun eachPartIsItsShareOfTheLaneAndTheStackFillsIt() {
        val bars = listOf(bar("a", listOf(part("house", 3.0), part("heat", 1.0))))
        val rects = BarsLayout.rects(bars, emptySet(), 50f, 80f, 0f)
        assertEquals(60f, rects.first { it.series == "house" }.let { it.bottom - it.top }, 0.01f)
        assertEquals(20f, rects.first { it.series == "heat" }.let { it.bottom - it.top }, 0.01f)
        // The stack reads bottom up, and together it spans the whole lane.
        assertEquals(80f, rects.maxOf { it.bottom }, 0.01f)
        assertEquals(0f, rects.minOf { it.top }, 0.01f)
    }

    @Test fun aNegativeLaneHangsFromTheZeroLineWhichIsInTheMiddleOfTheSpan() {
        val bars = listOf(bar("up", listOf(part("earned", 6.0))), bar("down", listOf(part("cost", -2.0))))
        val extent = BarsLayout.extent(bars, emptySet())
        assertEquals(8.0, extent.span, 0.0)
        assertEquals(0.25, extent.zero, 1e-9)
        val rects = BarsLayout.rects(bars, emptySet(), 100f, 80f, 0f)
        // The zero line is 25 % up from the bottom: y = 60.
        assertEquals(60f, rects.first { it.series == "earned" }.bottom, 0.01f)
        val down = rects.first { it.series == "cost" }
        assertEquals(60f, down.top, 0.01f)
        assertEquals(80f, down.bottom, 0.01f)
    }

    @Test fun twoLanesShareABarInTheirOwnPlaces() {
        val b = Bar("a", listOf(listOf(part("x", 1.0)), listOf(part("y", -1.0))), "a", "a", slots = listOf(0, 0), slotCount = 1)
        val rects = BarsLayout.rects(listOf(b), emptySet(), 40f, 40f, 0f)
        assertEquals(setOf(0f), rects.map { it.left }.toSet())
        val two = Bar("a", listOf(listOf(part("x", 1.0)), listOf(part("y", 1.0))), "a", "a")
        val sides = BarsLayout.rects(listOf(two), emptySet(), 40f, 40f, 0f)
        assertEquals(listOf(0f, 20f), sides.map { it.left })
    }

    @Test fun aHiddenPartLeavesTheBarAndTheScale() {
        val bars = listOf(bar("a", listOf(part("house", 2.0), part("car", 8.0))))
        assertEquals(10.0, BarsLayout.extent(bars, emptySet()).top, 0.0)
        assertEquals(2.0, BarsLayout.extent(bars, setOf("car")).top, 0.0)
        val rects = BarsLayout.rects(bars, setOf("car"), 10f, 10f, 0f)
        assertEquals(listOf("house"), rects.map { it.series })
        assertEquals(0f, rects.single().top, 0.01f)
        // Everything hidden: nothing to draw, no division by zero.
        assertTrue(BarsLayout.rects(bars, setOf("car", "house"), 10f, 10f, 0f).isEmpty())
    }

    @Test fun aLineOverTheBarsRaisesTheScale() {
        val bars = listOf(bar("a", listOf(part("house", 2.0))))
        assertEquals(6.0, BarsLayout.extent(bars, emptySet(), listOf(6.0, null)).top, 0.0)
    }

    @Test fun aTouchIsOnTheBarUnderItsXAndNowhereOutside() {
        assertEquals(0, BarsLayout.indexAt(0f, 100f, 4))
        assertEquals(2, BarsLayout.indexAt(60f, 100f, 4))
        assertEquals(3, BarsLayout.indexAt(100f, 100f, 4))
        assertNull(BarsLayout.indexAt(-1f, 100f, 4))
        assertNull(BarsLayout.indexAt(101f, 100f, 4))
        assertNull(BarsLayout.indexAt(10f, 100f, 0))
    }

    @Test fun theKeysMoveBetweenBarsAndEscapeClears() {
        val m = BarsLayout.Move.values()
        assertEquals(2, BarsLayout.step(1, 5, BarsLayout.Move.RIGHT))
        assertEquals(4, BarsLayout.step(4, 5, BarsLayout.Move.RIGHT))
        assertEquals(0, BarsLayout.step(0, 5, BarsLayout.Move.LEFT))
        assertEquals(4, BarsLayout.step(null, 5, BarsLayout.Move.LEFT))
        assertEquals(0, BarsLayout.step(3, 5, BarsLayout.Move.HOME))
        assertEquals(4, BarsLayout.step(3, 5, BarsLayout.Move.END))
        assertNull(BarsLayout.step(3, 5, BarsLayout.Move.CLEAR))
        assertNull(BarsLayout.step(null, 0, BarsLayout.Move.RIGHT))
        assertEquals(5, m.size)
    }
}
