package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The process-local publication event, and one screen's guarded reaction to it. */
class PricePublicationsTest {
    private var reloads = 0

    @Before
    fun resetReloads() {
        reloads = 0
    }

    /** One widget's outcome, as the publication operation reports it. */
    private fun outcome(areaId: String, accepted: Boolean) = PublicationOutcome(areaId, accepted)

    // what one publication operation announces

    @Test
    fun twoWidgetsForOneAreaAreOneEventAndTwoAreasAreTwo() {
        val registry = PricePublicationRegistry()
        val told = mutableListOf<String>()
        registry.register { told.add(it) }

        val announced = PricePublicationBatch.announce(
            listOf(outcome("SE4", true), outcome("SE4", true), outcome("NO1", true)),
            registry
        )

        assertEquals(listOf("SE4", "NO1"), announced)
        assertEquals(listOf("SE4", "NO1"), told)
    }

    @Test
    fun aPublicationThatAcceptedNothingNewAnnouncesNothingAtAll() {
        val registry = PricePublicationRegistry()
        val told = mutableListOf<String>()
        registry.register { told.add(it) }

        val announced = PricePublicationBatch.announce(
            listOf(outcome("SE4", false), outcome("NO1", false)),
            registry
        )

        assertTrue(announced.isEmpty())
        assertTrue(told.isEmpty())
    }

    @Test
    fun theOrderIsTheOrderTheWidgetsWereVisited() {
        assertEquals(
            listOf("NO1", "SE4"),
            PricePublicationBatch.areas(listOf(outcome("NO1", true), outcome("SE4", true)))
        )
    }

    // the registry, and its symmetry

    @Test
    fun aListenerIsToldUntilItClosesAndClosingTwiceIsHarmless() {
        val registry = PricePublicationRegistry()
        val told = mutableListOf<String>()
        val handle = registry.register { told.add(it) }

        registry.publish("SE4")
        assertEquals(listOf("SE4"), told)
        assertEquals(1, registry.size())

        handle.close()
        registry.publish("SE4")
        assertEquals(listOf("SE4"), told)
        assertEquals(0, registry.size())

        handle.close()
        assertEquals(0, registry.size())
    }

    @Test
    fun withNoListenerRegisteredNothingIsKept() {
        val registry = PricePublicationRegistry()
        registry.publish("SE4")
        assertEquals(0, registry.size())
    }

    // one screen's guarded reaction

    @Test
    fun aScreenReloadsForItsOwnAreaAndIgnoresAnothersEvent() {
        var area: String? = "SE4"
        val refresh = PriceScreenRefresh(
            reload = { reloads += 1 },
            currentArea = { area },
            built = { true }
        )

        refresh.published("SE4")
        assertEquals(1, reloads)
        refresh.loadFinished()
        refresh.published("NO1")
        assertEquals(1, reloads)

        area = "NO1"
        refresh.published("SE4")
        assertEquals(1, reloads)
        refresh.published("NO1")
        assertEquals(2, reloads)
    }

    @Test
    fun aScreenWithNoSubjectAtAllIsInert() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { null }, built = { true })
        refresh.published("SE4")
        assertEquals(0, reloads)
    }

    @Test
    fun aScreenThatIsNotBuiltIsInert() {
        var built = false
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { built })
        refresh.published("SE4")
        refresh.resumed()
        refresh.resumed()
        assertEquals(0, reloads)

        built = true
        refresh.published("SE4")
        assertEquals(1, reloads)
    }

    @Test
    fun theFirstResumeFollowsCreationAndEveryLaterOneChecksTheHeldPrices() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })

        // The first one follows the screen's own creation, whose load already ran.
        refresh.resumed()
        assertEquals(0, reloads)
        refresh.resumed()
        assertEquals(1, reloads)
        refresh.loadFinished()
        refresh.resumed()
        assertEquals(2, reloads)
    }

    // one load at a time, one follow-up owed

    @Test
    fun aPublicationDuringAnOlderReadIsQueuedAsOneFollowUp() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })
        refresh.resumed() // the first resume: creation's own load, not a reload
        refresh.loadStarted() // the screen's read of the *old* held result is in flight

        // The widget stores new documents and announces them while that read is still answering.
        refresh.published("SE4")
        assertEquals(0, reloads)

        refresh.loadFinished()
        assertEquals(1, reloads)
    }

    @Test
    fun everyTriggerDuringThatReadCoalescesIntoTheSameSingleFollowUp() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })
        refresh.resumed()
        refresh.loadStarted()

        refresh.published("SE4")
        refresh.resumed()
        refresh.published("SE4")
        refresh.published("SE4")
        assertEquals(0, reloads)

        refresh.loadFinished()
        assertEquals(1, reloads)
    }

    @Test
    fun withNothingOwedFinishingAReadStartsNothingAndALaterEventStillLoads() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })
        refresh.loadStarted()
        refresh.loadFinished()
        assertEquals(0, reloads)

        refresh.published("SE4")
        assertEquals(1, reloads)
    }

    @Test
    fun anotherAreasEventDuringTheReadOwesNothing() {
        var area = "SE4"
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { area }, built = { true })
        refresh.loadStarted()
        refresh.published("NO1")
        area = "NO1"
        refresh.loadFinished()
        assertEquals(0, reloads)
    }

    @Test
    fun theFollowUpsOwnBalancedFinishLeavesTheScreenIdle() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })
        refresh.loadStarted()
        refresh.published("SE4")
        refresh.loadFinished()
        assertEquals(1, reloads)

        // The follow-up is in flight of its own accord; finishing it owes nothing more.
        refresh.loadFinished()
        assertEquals(1, reloads)
        refresh.published("SE4")
        assertEquals(2, reloads)
    }

    @Test
    fun theFirstResumeOwesNothingEvenWhileAReadRuns() {
        val refresh = PriceScreenRefresh(reload = { reloads += 1 }, currentArea = { "SE4" }, built = { true })
        refresh.loadStarted()
        refresh.resumed()
        refresh.loadFinished()
        assertEquals(0, reloads)
    }

    // the batch boundary

    @Test
    fun everyWidgetIsVisitedAndTheAcceptedOutcomesAreAnnouncedOnceBeforeTheFinish() {
        val events = mutableListOf<String>()

        val announced = runPublicationBatch(
            widgets = listOf(1, 2, 3),
            visit = { id, outcomes ->
                events.add("visit$id")
                if (id != 2) outcomes.add(outcome("SE4", true))
            },
            finish = { events.add("finish") },
            announce = { outcomes -> PricePublicationBatch.areas(outcomes).also { events.add("announce") } }
        )

        assertEquals(listOf("visit1", "visit2", "visit3", "announce", "finish"), events)
        assertEquals(listOf("SE4"), announced)
    }

    @Test
    fun aLaterWidgetsFailureStillAnnouncesWhatEarlierOnesAcceptedAndIsNotDisguised() {
        val operation = IllegalStateException("the third widget could not render")
        val announced = mutableListOf<List<String>>()
        var finishes = 0

        val thrown = try {
            runPublicationBatch(
                widgets = listOf(1, 2, 3),
                visit = { id, outcomes ->
                    outcomes.add(outcome("SE4", true))
                    if (id == 3) throw operation
                },
                finish = { finishes += 1 },
                announce = { outcomes -> PricePublicationBatch.areas(outcomes).also { announced.add(it) } }
            )
            null
        } catch (error: Throwable) {
            error
        }

        // The first two widgets' documents really are stored, so they are announced anyway -- and
        // the third widget's failure is re-thrown as itself, with nothing appended to it.
        assertEquals(listOf(listOf("SE4")), announced)
        assertEquals(1, finishes)
        assertSame(operation, thrown)
        assertTrue(thrown!!.suppressed.isEmpty())
    }

    @Test
    fun aFailingWidgetDoesNotStopTheWidgetsAfterIt() {
        val visits = mutableListOf<String>()
        val announced = mutableListOf<List<String>>()
        val failure = IllegalStateException("the second widget could not render")
        var finishes = 0

        val thrown = try {
            runPublicationBatch(
                widgets = listOf("SE4-a", "SE4-b", "NO1-a"),
                visit = { widget, outcomes ->
                    visits.add(widget)
                    when (widget) {
                        "SE4-a" -> outcomes.add(outcome("SE4", true))
                        "NO1-a" -> outcomes.add(outcome("NO1", true))
                        else -> throw failure
                    }
                },
                finish = { finishes += 1 },
                announce = { outcomes -> PricePublicationBatch.areas(outcomes).also { announced.add(it) } }
            )
            null
        } catch (error: Throwable) {
            error
        }

        // The third widget was still visited, and the market it moved is announced: one screen's
        // broken render is not a reason to lose another screen's new prices.
        assertEquals(listOf("SE4-a", "SE4-b", "NO1-a"), visits)
        assertEquals(listOf(listOf("SE4", "NO1")), announced)
        assertEquals(1, finishes)
        assertSame(failure, thrown)
        assertTrue(thrown!!.suppressed.isEmpty())
    }

    @Test
    fun theFirstWidgetFailureIsTheMainOneAndLaterOnesAreSuppressedInOrder() {
        val first = IllegalStateException("the second widget could not render")
        val second = IllegalArgumentException("the third widget could not render")
        val third = UnsupportedOperationException("the fourth widget could not render")
        val announced = mutableListOf<List<String>>()
        var visited = 0
        var finishes = 0

        val thrown = try {
            runPublicationBatch(
                widgets = listOf(1, 2, 3, 4, 5),
                visit = { id, outcomes ->
                    visited += 1
                    if (id == 5) outcomes.add(outcome("NO1", true))
                    when (id) {
                        2 -> throw first
                        3 -> throw second
                        4 -> throw third
                    }
                },
                finish = { finishes += 1 },
                announce = { outcomes -> PricePublicationBatch.areas(outcomes).also { announced.add(it) } }
            )
            null
        } catch (error: Throwable) {
            error
        }

        // Every widget was visited, the one that succeeded is announced, and the failures are
        // reported in the order they happened: the first as the operation's own, the rest
        // suppressed.
        assertEquals(5, visited)
        assertEquals(listOf(listOf("NO1")), announced)
        assertEquals(1, finishes)
        assertSame(first, thrown)
        assertEquals(listOf(second, third), thrown!!.suppressed.toList())
    }

    @Test
    fun theOperationIsFinishedExactlyOnceForZeroOneAndManyWidgets() {
        for (count in listOf(0, 1, 5)) {
            var finishes = 0
            val announced = runPublicationBatch(
                widgets = (1..count).toList(),
                visit = { _, outcomes -> outcomes.add(outcome("SE4", true)) },
                finish = { finishes += 1 },
                announce = { PricePublicationBatch.areas(it) }
            )

            assertEquals("widgets=$count", 1, finishes)
            assertEquals(
                "widgets=$count",
                if (count == 0) emptyList<String>() else listOf("SE4"),
                announced
            )
        }
    }

    @Test
    fun aListenerFailureNeverReplacesTheOperationsOwnFailure() {
        val operation = IllegalStateException("the widget could not render")
        val listener = IllegalArgumentException("a screen could not be told")
        var finishes = 0

        val thrown = try {
            runPublicationBatch(
                widgets = listOf(1),
                visit = { _, _ -> throw operation },
                finish = { finishes += 1 },
                announce = { throw listener }
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertSame(operation, thrown)
        assertEquals(listOf(listener), thrown!!.suppressed.toList())
        assertEquals(1, finishes)
    }

    @Test
    fun aListenerFailureOnItsOwnIsReportedRatherThanCalledSuccess() {
        val listener = IllegalArgumentException("a screen could not be told")
        var finishes = 0

        val thrown = try {
            runPublicationBatch(
                widgets = listOf(1),
                visit = { _, _ -> },
                finish = { finishes += 1 },
                announce = { throw listener }
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertSame(listener, thrown)
        assertEquals(1, finishes)
    }

    @Test
    fun aFaultyListenerDoesNotSilenceTheOthers() {
        val registry = PricePublicationRegistry()
        val told = mutableListOf<String>()
        registry.register { throw IllegalStateException("this screen is broken") }
        registry.register { told.add(it) }

        val thrown = try {
            registry.publish("SE4")
            null
        } catch (error: Throwable) {
            error
        }

        assertEquals(listOf("SE4"), told)
        assertTrue(thrown is IllegalStateException)
    }
}
