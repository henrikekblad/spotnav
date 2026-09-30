package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.ViewBounds

/** The rectangle that answers a tap around a control too small to be one. */
class ActionTargetTest {
    private val body = ViewBounds(left = 0f, top = 0f, right = 1000f, bottom = 600f)

    /** The room a card header's action may use: the card's top strip, down to the graph. */
    private val aboveGraph = ViewBounds(left = 0f, top = 0f, right = 1000f, bottom = 12f + 32f + 10f)

    /** The action's own box: 32 dp wide, at the header's end, 14 dp in from the card's edge. */
    private val action = ViewBounds(
        left = 1000f - 14f - 32f, top = 12f, right = 1000f - 14f, bottom = 12f + 32f
    )

    private fun assertSame(message: String, expected: Float, actual: Float) =
        assertEquals(message, expected.toDouble(), actual.toDouble(), 0.0001)

    @Test
    fun aFingerWideTargetIsCentredOnTheBoxItBelongsTo() {
        val hit = ActionTarget.hitRect(action, 48f, aboveGraph)

        assertSame("48 dp wide", 48f, hit.right - hit.left)
        assertSame("48 dp tall", 48f, hit.bottom - hit.top)
        assertSame("centred horizontally on the box", (action.left + action.right) / 2f, (hit.left + hit.right) / 2f)
        assertSame("centred vertically on the box", (action.top + action.bottom) / 2f, (hit.top + hit.bottom) / 2f)
    }

    @Test
    fun theTargetIsTheSpaceBetweenTheHeaderAndTheGraph() {
        // The whole rule, in one rectangle:
        assertEquals(ViewBounds(left = 946f, top = 4f, right = 994f, bottom = 52f), ActionTarget.hitRect(action, 48f, aboveGraph))

        val hit = ActionTarget.hitRect(action, 48f, aboveGraph)
        assertTrue("inside the card, horizontally", hit.left >= body.left && hit.right <= body.right)
        assertTrue("inside the card, vertically", hit.top >= body.top)
        assertTrue("and never over the graph", hit.bottom <= aboveGraph.bottom)
    }

    @Test
    fun aTargetThatWouldNotFitIsShiftedRatherThanGrownThroughTheEdge() {
        // An action at the very bottom of its room:
        val low = ViewBounds(left = 0f, top = 40f, right = 32f, bottom = 60f)
        val hit = ActionTarget.hitRect(low, 48f, ViewBounds(0f, 0f, 1000f, 60f))

        assertSame("still a finger tall", 48f, hit.bottom - hit.top)
        assertSame("flush with the room's own edge", 60f, hit.bottom)
        assertTrue("inside it", hit.top >= 0f)
    }

    @Test
    fun whenThereIsLessRoomThanAFingerTheTargetGivesWayAndNotTheGraph() {
        // 20 dp of space is 20 dp of target: short of the minimum, and never a pixel of the plot.
        val hit = ActionTarget.hitRect(ViewBounds(0f, 0f, 32f, 20f), 48f, ViewBounds(0f, 0f, 1000f, 20f))

        assertSame("as wide as its room", 48f, hit.right - hit.left)
        assertSame("no taller than its room", 20f, hit.bottom - hit.top)
        assertSame("inside it", 20f, hit.bottom)
    }

    @Test
    fun noRoomAtAllIsAnEmptyTargetRatherThanAnOutsideOne() {
        val hit = ActionTarget.hitRect(action, 48f, ViewBounds(0f, 0f, 1000f, 0f))

        assertSame("nothing tall", 0f, hit.bottom - hit.top)
        assertTrue("and it is gone rather than hanging below the graph", hit.bottom <= 0f)
    }

    @Test
    fun aBoxThatIsAlreadyBiggerThanAFingerIsKeptAsItIs() {
        // A control drawn at its own 60 dp is not moved:
        val big = ViewBounds(left = 100f, top = 100f, right = 160f, bottom = 160f)
        assertEquals(big, ActionTarget.hitRect(big, 48f, ViewBounds(0f, 0f, 1000f, 600f)))
    }
}
