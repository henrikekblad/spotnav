package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile

class WidgetChargerSelectionControllerTest {
    private val a = ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
    private val b = ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b")

    @Test fun aStaleBindingRemainsUnchangedWhenConfigurationIsOpened() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "deleted-id")

        assertTrue(controller.selection is WidgetChargerSelectionController.Selection.MissingProfile)
        assertEquals("deleted-id", controller.chargerProfileIdToSave)
        assertTrue(controller.requiresExplicitChoice)
    }

    @Test fun aGenericSaveWithoutAChargerChoiceCannotClearAStaleBinding() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "deleted-id")

        val savedChargerProfileId = controller.chargerProfileIdToSave

        assertEquals("deleted-id", savedChargerProfileId)
    }

    @Test fun explicitlySelectingNoChargerClearsAStaleBinding() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "deleted-id")

        controller.selectNoCharger()

        assertEquals(WidgetChargerSelectionController.Selection.ExplicitlyNoCharger, controller.selection)
        assertNull(controller.chargerProfileIdToSave)
        assertFalse(controller.requiresExplicitChoice)
    }

    @Test fun selectingAnAvailableProfileReplacesTheStaleId() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "deleted-id")

        controller.selectProfile("b")

        assertEquals(WidgetChargerSelectionController.Selection.ValidProfile("b"), controller.selection)
        assertEquals("b", controller.chargerProfileIdToSave)
        assertFalse(controller.requiresExplicitChoice)
    }

    @Test fun selectingAnUnavailableProfileIsRejected() {
        val controller = WidgetChargerSelectionController(listOf(a), storedChargerProfileId = null)

        val exception = runCatching { controller.selectProfile("not-in-the-list") }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
    }

    @Test fun aStaleBindingWithZeroAvailableProfilesCanStillBeExplicitlyCleared() {
        val controller = WidgetChargerSelectionController(emptyList(), storedChargerProfileId = "deleted-id")

        assertTrue(controller.isRelevant) // the section must still be shown
        assertTrue(controller.startedAsMissingProfile)

        controller.selectNoCharger()

        assertNull(controller.chargerProfileIdToSave)
    }

    @Test fun zeroProfilesAndNoStoredBindingIsNotRelevant() {
        val controller = WidgetChargerSelectionController(emptyList(), storedChargerProfileId = null)

        assertFalse(controller.isRelevant)
    }

    @Test fun profilesAvailableButNoStoredBindingIsStillRelevant() {
        val controller = WidgetChargerSelectionController(listOf(a), storedChargerProfileId = null)

        assertTrue(controller.isRelevant)
        assertFalse(controller.startedAsMissingProfile)
    }

    @Test fun aPreviouslyStoredExplicitNoChargerIsNeverTreatedAsMissing() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = null)

        assertEquals(WidgetChargerSelectionController.Selection.ExplicitlyNoCharger, controller.selection)
        assertFalse(controller.startedAsMissingProfile)
        assertFalse(controller.requiresExplicitChoice)
        assertNull(controller.chargerProfileIdToSave)
    }

    @Test fun aStoredIdThatIsNotAmongAvailableProfilesIsTreatedAsMissingNeverAsAGuess() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "not-a-or-b")

        assertTrue(controller.selection is WidgetChargerSelectionController.Selection.MissingProfile)
        assertEquals("not-a-or-b", controller.chargerProfileIdToSave)
    }

    @Test fun toStringNeverExposesAnyFieldAtAll() {
        val controller = WidgetChargerSelectionController(listOf(a, b), storedChargerProfileId = "b")

        val text = controller.toString()

        assertFalse(text.contains("hook-a"))
        assertFalse(text.contains("hook-b"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("Charger A"))
        assertFalse(text.contains("Charger B"))
    }
}
