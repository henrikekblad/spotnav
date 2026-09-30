package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The rules a batch of widget updates reads a charger's dashboard by: one read per charger. */
class WidgetDashboardSourceTest {
    private val start = 1_000_000L
    private var clock = start
    private val store = WidgetDashboardStore(FakeKeyValueStore()) {}
    private val reads = mutableListOf<Pair<String, WidgetNetworkBudget.Timeouts>>()
    private var failing = false

    private val answer = DashboardFixtures.json().let { FetchedDashboard(DashboardFixtures.parse(it), it.toString()) }

    private fun source(network: Boolean = true, force: Boolean = false, budgetMs: Long = WidgetNetworkBudget.BATCH_BUDGET_MS) =
        WidgetDashboardSource(
            store = store,
            fetch = { profileId, timeouts ->
                reads += profileId to timeouts
                clock += 100
                if (failing) throw java.io.IOException("unreachable")
                if (profileId == "gone") null else answer
            },
            budget = WidgetNetworkBudget(start, { clock }, budgetMs),
            now = { clock },
            network = network,
            force = force
        )

    @Test fun twoWidgetsOnOneChargerShareOneRead() {
        val batch = source()
        val first = batch.latest("c1")
        val second = batch.latest("c1")
        assertEquals(1, reads.size)
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test fun eachChargerIsReadOnce() {
        val batch = source()
        batch.latest("c1"); batch.latest("c2"); batch.latest("c1"); batch.latest("c2")
        assertEquals(listOf("c1", "c2"), reads.map { it.first })
    }

    @Test fun aForcedBatchStillReadsEachChargerOnlyOnce() {
        val batch = source(force = true)
        repeat(4) { batch.latest("c1") }
        assertEquals(1, reads.size)
    }

    @Test fun aReadIsStoredForEveryOtherPassToDrawFrom() {
        source().latest("c1")
        assertEquals(clock, store.dashboardFor("c1")!!.capturedAt)
    }

    @Test fun aRedrawBatchReadsTheStoreAndNothingElse() {
        store.put("c1", answer.body, start - 60 * 60_000)
        val batch = source(network = false, force = true)
        val held = batch.latest("c1")
        assertEquals(start - 60 * 60_000, held!!.capturedAt)
        assertNull(batch.latest("nothing held"))
        assertTrue(reads.isEmpty())
    }

    @Test fun aFreshDashboardIsDrawnAsItIsUnlessTheBatchIsForced() {
        store.put("c1", answer.body, start - 60_000)
        assertNull(reads.firstOrNull())
        source().latest("c1")
        assertTrue(reads.isEmpty())
        source(force = true).latest("c1")
        assertEquals(1, reads.size)
    }

    @Test fun aStaleDashboardIsReadAgain() {
        store.put("c1", answer.body, start - WidgetStatusLine.REFRESH_AFTER_MS - 1)
        source().latest("c1")
        assertEquals(1, reads.size)
    }

    @Test fun aFailedReadKeepsWhatWasHeldAndIsNotRetriedByTheNextWidget() {
        store.put("c1", answer.body, start - 20 * 60_000)
        failing = true
        val batch = source()
        val kept = batch.latest("c1")
        assertEquals(start - 20 * 60_000, kept!!.capturedAt)
        batch.latest("c1")
        assertEquals(1, reads.size)
    }

    @Test fun aChargerThatCannotBeReadHasNoDashboard() {
        assertNull(source().latest("gone"))
        assertNull(source().latest("gone"))
    }

    @Test fun aSpentBudgetStartsNoRead() {
        val batch = source(budgetMs = 500)
        assertNull(batch.latest("c1"))
        assertTrue(reads.isEmpty())
    }

    @Test fun theBudgetIsSharedAcrossChargersAndShrinksTheTimeouts() {
        val batch = source(budgetMs = 3_000)
        batch.latest("c1")
        clock += 1_500
        batch.latest("c2")
        assertEquals(2, reads.size)
        val first = reads[0].second
        val second = reads[1].second
        assertTrue(first.connectMs + first.readMs <= 3_000)
        assertTrue("later reads get less: $second", second.connectMs + second.readMs <= 1_400)
    }
}

class WidgetNetworkBudgetTest {
    @Test fun aFullBudgetGivesTheShortTimeouts() {
        val budget = WidgetNetworkBudget(0, { 0 })
        val timeouts = budget.dashboardTimeouts()!!
        assertEquals(2_000, timeouts.connectMs)
        assertEquals(3_000, timeouts.readMs)
    }

    @Test fun aReadNeverOutlastsWhatIsLeft() {
        for (left in listOf(1_000L, 1_500L, 2_999L, 4_000L, 8_000L)) {
            val budget = WidgetNetworkBudget(0, { 8_000 - left })
            val timeouts = budget.dashboardTimeouts()!!
            assertTrue("$left: $timeouts", timeouts.connectMs + timeouts.readMs <= left)
        }
    }

    @Test fun lessThanAUsefulReadIsNotStarted() {
        assertNull(WidgetNetworkBudget(0, { 7_500 }).dashboardTimeouts())
        assertNull(WidgetNetworkBudget(0, { 20_000 }).dashboardTimeouts())
        assertEquals(0L, WidgetNetworkBudget(0, { 20_000 }).remainingMs())
    }
}
