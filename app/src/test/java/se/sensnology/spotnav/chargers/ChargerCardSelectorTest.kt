package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.widget.WidgetChargerSelectionController

/**
 * What the charger card's header does with a widget's stored charger binding: whether the title
 * stays or gives way to a selector, which rows that selector has and which one is selected, and
 * what picking a row means.
 */
class ChargerCardSelectorTest {
    private val a = ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
    private val b = ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b")

    private fun content(profiles: List<ChargerProfile>, storedChargerProfileId: String?) =
        ChargerCardSelector.content(WidgetChargerSelectionController(profiles, storedChargerProfileId))

    private fun kinds(content: ChargerCardSelector.Content) = content.entries.map { it.kind }

    // one charger: the title, and nothing to decide

    // "Several chargers become a selector on the charger card" — several, so one charger is not
    // several, and its card says which charger it is.
    @Test fun theOnlyChargerBoundLeavesTheTitleAndShowsNoControl() {
        val content = content(listOf(a), storedChargerProfileId = "a")

        assertFalse(content.showsControl)
        assertEquals("a", content.bound?.localId)
        assertEquals(listOf(ChargerCardSelector.Kind.NO_CHARGER, ChargerCardSelector.Kind.PROFILE), kinds(content))
        assertEquals(1, content.selectedIndex)
        assertFalse(content.stale)
    }

    // With the Settings section gone, this row is the only way back to the one charger a widget has
    // decided against.
    @Test fun theOnlyChargerIsStillOfferedWhenTheBindingSaysNoCharger() {
        val content = content(listOf(a), storedChargerProfileId = null)

        assertTrue(content.showsControl)
        assertNull(content.bound)
        assertEquals(0, content.selectedIndex)
        assertEquals(ChargerCardSelector.Target.Profile("a"), content.targetAt(1))
    }

    @Test fun noChargerAndNoProfilesAtAllShowsNothingToChoose() {
        val content = content(emptyList(), storedChargerProfileId = null)

        assertFalse(content.showsControl)
        assertNull(content.bound)
        assertFalse(content.stale)
    }

    // several chargers: the selector

    @Test fun severalChargersBoundSelectsTheBoundOne() {
        val content = content(listOf(a, b), storedChargerProfileId = "b")

        assertTrue(content.showsControl)
        assertEquals("b", content.bound?.localId)
        assertEquals(
            listOf(ChargerCardSelector.Kind.NO_CHARGER, ChargerCardSelector.Kind.PROFILE, ChargerCardSelector.Kind.PROFILE),
            kinds(content)
        )
        assertEquals(2, content.selectedIndex)
    }

    // a stale binding: the part with teeth

    @Test fun aStaleBindingGetsADisabledPlaceholderAndTheWarning() {
        val content = content(listOf(a, b), storedChargerProfileId = "deleted-id")

        assertTrue(content.showsControl)
        assertTrue(content.stale)
        assertEquals(ChargerCardSelector.Kind.PLACEHOLDER, content.entries[0].kind)
        assertEquals(0, content.selectedIndex)
        // Nothing is shown for the id that is gone -- there is nothing meaningful to show, and it
        // must never be matched to a live charger.
        assertNull(content.bound)
    }

    @Test fun theStaleRowIsNotAChoiceButTheRowsBelowItAre() {
        val content = content(listOf(a, b), storedChargerProfileId = "deleted-id")

        assertEquals(ChargerCardSelector.Target.Ignore, content.targetAt(0))
        assertEquals(ChargerCardSelector.Target.NoCharger, content.targetAt(1))
        assertEquals(ChargerCardSelector.Target.Profile("a"), content.targetAt(2))
        assertEquals(ChargerCardSelector.Target.Profile("b"), content.targetAt(3))
    }

    // "Missing binding with zero available profiles can still be explicitly cleared." The
    // placeholder needs a row even then, and "No charger" is the only real one -- so the control
    // must not be hidden.
    @Test fun aStaleBindingIsResolvableWithNoProfilesAtAll() {
        val content = content(emptyList(), storedChargerProfileId = "deleted-id")

        assertTrue(content.showsControl)
        assertTrue(content.stale)
        assertEquals(listOf(ChargerCardSelector.Kind.PLACEHOLDER, ChargerCardSelector.Kind.NO_CHARGER), kinds(content))
        assertEquals(0, content.selectedIndex)
        assertEquals(ChargerCardSelector.Target.NoCharger, content.targetAt(1))
    }

    // the invariants a spinner actually relies on

    // A callback can arrive for a row that is gone (a rebuild dispatches one), and that is not a
    // choice: nothing may be written for it.
    @Test fun aTapOutsideTheRowsIsNotAChoice() {
        val content = content(listOf(a, b), storedChargerProfileId = "b")

        assertEquals(ChargerCardSelector.Target.Ignore, content.targetAt(-1))
        assertEquals(ChargerCardSelector.Target.Ignore, content.targetAt(content.entries.size))
        assertEquals(ChargerCardSelector.Target.Ignore, content.targetAt(99))
    }

    @Test fun theSelectedRowAlwaysDescribesTheStoredBinding() {
        val profiles = listOf(a, b)
        val expected = mapOf<String?, ChargerCardSelector.Target>(
            "a" to ChargerCardSelector.Target.Profile("a"),
            "b" to ChargerCardSelector.Target.Profile("b"),
            null to ChargerCardSelector.Target.NoCharger,
            "deleted-id" to ChargerCardSelector.Target.Ignore
        )

        for ((stored, target) in expected) {
            val content = content(profiles, stored)

            assertEquals(target, content.targetAt(content.selectedIndex))
        }
    }

    // The controller decides which of the three states a binding is in; the placeholder row and the
    // warning are two questions about that one state, and on this card they can never disagree (a
    // fresh controller per render).
    @Test fun thePlaceholderAndTheWarningAlwaysAgreeOnThisCard() {
        for (stored in listOf<String?>(null, "a", "deleted-id")) {
            val content = content(listOf(a, b), stored)

            assertEquals(content.stale, content.entries.first().kind == ChargerCardSelector.Kind.PLACEHOLDER)
        }
    }

    // No row may ever point at a charger that is not on offer: a stale id is never resolved to a
    // live-looking row to have something to show (the same rule WidgetChargerResolver and the
    // binding store already hold to).
    @Test fun noRowEverNamesAProfileThatIsNotAvailable() {
        for (stored in listOf<String?>(null, "a", "deleted-id")) {
            for (entry in content(listOf(a, b), stored).entries) {
                if (entry.kind == ChargerCardSelector.Kind.PROFILE) {
                    assertTrue(entry.profile?.localId == "a" || entry.profile?.localId == "b")
                    assertTrue(entry.target is ChargerCardSelector.Target.Profile)
                } else {
                    assertNull(entry.profile)
                }
            }
        }
    }
}
