package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.DayRollover
import se.sensnology.spotnav.ui.site.PagerSwipe.Touched

/** The pages' rules apart from the views: which pages there are, where a swipe may begin and settle, and what is read when. */
class SitePagesTest {
    @Test fun aPageExistsOnlyWhenHomeAssistantServesIt() {
        // Any Home Assistant 2.x: the charger's page alone, so no row of icons and nothing to swipe.
        assertEquals(listOf(PageKind.CHARGER), PageOffer.kinds(house = false, heat = false))
        assertEquals(listOf(PageKind.CHARGER, PageKind.HOUSE), PageOffer.kinds(house = true, heat = false))
        assertEquals(listOf(PageKind.CHARGER, PageKind.HOUSE, PageKind.HEAT), PageOffer.kinds(house = true, heat = true))
        assertEquals(listOf(PageKind.CHARGER, PageKind.HEAT), PageOffer.kinds(house = false, heat = true))
        // The page in view is kept while it is offered; else the charger's.
        val both = PageOffer.kinds(house = true, heat = false)
        assertEquals(PageKind.HOUSE, PageOffer.opening(PageKind.HOUSE, both))
        assertEquals(PageKind.CHARGER, PageOffer.opening(PageKind.HEAT, both))
        assertEquals(PageKind.CHARGER, PageOffer.opening(PageKind.HOUSE, PageOffer.kinds(house = false, heat = false)))
    }

    @Test fun aSwipeBeginsOnlyOnAHeaderOrEmptySpace() {
        assertTrue(PagerSwipe.allowedOn(listOf(Touched.PLAIN, Touched.PLAIN, Touched.PLAIN)))
        assertTrue(PagerSwipe.allowedOn(emptyList()))
        // Never on a slider or a chart, wherever in the card it sits, nor on something that is pressed.
        assertFalse(PagerSwipe.allowedOn(listOf(Touched.PLAIN, Touched.PLAIN, Touched.DRAGGED)))
        assertFalse(PagerSwipe.allowedOn(listOf(Touched.PLAIN, Touched.DRAGGED, Touched.PLAIN)))
        assertFalse(PagerSwipe.allowedOn(listOf(Touched.PLAIN, Touched.PRESSED)))
    }

    @Test fun aDragBeginsASwipeOnlyWhenItIsClearlySideways() {
        assertFalse(PagerSwipe.begins(dx = 10f, dy = 0f, slop = 16))
        assertTrue(PagerSwipe.begins(dx = 30f, dy = 5f, slop = 16))
        assertTrue(PagerSwipe.begins(dx = -30f, dy = -10f, slop = 16))
        // A scroll down the page with some drift is the page's own.
        assertFalse(PagerSwipe.begins(dx = 30f, dy = 40f, slop = 16))
        assertFalse(PagerSwipe.begins(dx = 30f, dy = 16f, slop = 16))
    }

    @Test fun aReleasedDragSettlesOnThePageMostlyInViewOrTheNextOneOfAFling() {
        val stride = 1000
        // Less than half way: back where it was; more: the next page.
        assertEquals(0, PagerSwipe.settle(scrollX = 400, stride, velocity = 0f, minFling = 200, from = 0, count = 3))
        assertEquals(1, PagerSwipe.settle(scrollX = 600, stride, velocity = 0f, minFling = 200, from = 0, count = 3))
        // A quick flick to the left shows the next page, to the right the one before.
        assertEquals(1, PagerSwipe.settle(scrollX = 100, stride, velocity = -900f, minFling = 200, from = 0, count = 3))
        assertEquals(0, PagerSwipe.settle(scrollX = 900, stride, velocity = 900f, minFling = 200, from = 1, count = 3))
        // Never past the ends, and one page at a time.
        assertEquals(0, PagerSwipe.settle(scrollX = 0, stride, velocity = 900f, minFling = 200, from = 0, count = 3))
        assertEquals(2, PagerSwipe.settle(scrollX = 2000, stride, velocity = -900f, minFling = 200, from = 2, count = 3))
        assertEquals(1, PagerSwipe.settle(scrollX = 1900, stride, velocity = 0f, minFling = 200, from = 0, count = 3))
        assertEquals(0, PagerSwipe.settle(scrollX = 0, stride, velocity = -900f, minFling = 200, from = 0, count = 1))
    }

    /** A timer the test fires by hand. */
    private class HandTimer : DayRollover.Timer {
        var delay: Long? = null
        private var task: (() -> Unit)? = null
        override fun after(delayMs: Long, task: () -> Unit) { delay = delayMs; this.task = task }
        override fun cancel() { delay = null; task = null }
        fun fire() { val run = task; task = null; delay = null; run?.invoke() }
    }

    @Test fun theLiveFlowsAreReadEveryTenSecondsAndTheHouseEveryMinuteOnlyWhileInView() {
        val timer = HandTimer()
        var clock = 0L
        val read = ArrayList<String>()
        val polling = SitePolling(timer, { clock }, live = { true }, readLive = { read.add("live") }, readSlow = { read.add("house") })
        // Out of view nothing is read and nothing waits.
        assertNull(timer.delay)
        assertEquals(emptyList<String>(), read)
        polling.start()
        assertEquals(listOf("house", "live"), read)
        assertEquals(10_000L, timer.delay)
        repeat(5) { clock += 10_000; timer.fire() }
        assertEquals(listOf("house", "live") + List(5) { "live" }, read)
        clock += 10_000
        timer.fire()
        assertEquals(listOf("live", "house", "live"), read.takeLast(3))
        // The page leaves the view (or the app the foreground): nothing waits, a late timer reads nothing.
        polling.stop()
        assertNull(timer.delay)
        val before = read.size
        timer.fire()
        assertEquals(before, read.size)
        // Back within the minute: the live flows at once, the house when its minute is up.
        clock += 20_000
        polling.start()
        assertEquals("live", read.last())
        assertEquals(before + 1, read.size)
        clock += 40_000
        timer.fire()
        assertEquals(listOf("house", "live"), read.takeLast(2))
        // Starting twice reads once.
        val once = read.size
        polling.start()
        assertEquals(once, read.size)
    }

    @Test fun onATabWithoutLiveFlowsOnlyThePagesDocumentIsReadOnceAMinute() {
        val timer = HandTimer()
        var clock = 0L
        var live = false
        val read = ArrayList<String>()
        val polling = SitePolling(timer, { clock }, live = { live }, readLive = { read.add("live") }, readSlow = { read.add("house") })
        polling.start()
        assertEquals(listOf("house"), read)
        assertEquals(60_000L, timer.delay)
        clock += 60_000
        timer.fire()
        assertEquals(listOf("house", "house"), read)
        // The Now tab is chosen: from the next tick the live flows too, at their own pace.
        live = true
        clock += 60_000
        timer.fire()
        assertEquals(listOf("house", "live"), read.takeLast(2))
        assertEquals(10_000L, timer.delay)
        // Another subject: its document is read at the next tick whatever its age.
        polling.forget()
        clock += 10_000
        timer.fire()
        assertEquals(listOf("house", "live"), read.takeLast(2))
    }
}
