package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a value row reads, and what a tap on it does -- one decision. */
class ValueCueTest {
    @Test
    fun aRowWithAControlBehindItIsEditable() {
        assertTrue(ValueCue.of(editable = true, available = true).actionable)
        assertEquals(ValueCue.EDITABLE, ValueCue.of(editable = true, available = true))
        // `available` defaults to true because most editable rows are always usable:
        assertEquals(ValueCue.EDITABLE, ValueCue.of(editable = true))
    }

    @Test
    fun aRowWithoutOneIsAReadOnlyFact() {
        assertEquals(ValueCue.READ_ONLY, ValueCue.of(editable = false))
        assertFalse(ValueCue.READ_ONLY.actionable)
        // Whatever else is said about a row that has no control:
        assertEquals(ValueCue.READ_ONLY, ValueCue.of(editable = false, available = false))
        assertEquals(ValueCue.READ_ONLY, ValueCue.of(editable = false, available = true))
    }

    @Test
    fun aRowThatCannotActRightNowIsNeitherEditableNorAFact() {
        assertEquals(ValueCue.UNAVAILABLE, ValueCue.of(editable = true, available = false))
        assertFalse(ValueCue.UNAVAILABLE.actionable)
    }

    @Test
    fun onlyTheEditableCueIsActionable() {
        // The invariant the view is written against:
        assertEquals(listOf(true, false, false), ValueCue.entries.map { it.actionable })
    }

    @Test
    fun aRowsStateCanChangeEitherWayWithoutTheDecisionDrifting() {
        // The charge limit's own transitions:
        assertEquals(ValueCue.EDITABLE, ValueCue.of(editable = true, available = true))
        assertEquals(ValueCue.UNAVAILABLE, ValueCue.of(editable = true, available = false))
        assertEquals(ValueCue.UNAVAILABLE, ValueCue.of(editable = true, available = false))
        assertEquals(ValueCue.EDITABLE, ValueCue.of(editable = true, available = true))
        assertEquals(ValueCue.READ_ONLY, ValueCue.of(editable = false, available = true))
    }
}
