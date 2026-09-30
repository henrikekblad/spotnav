package se.sensnology.spotnav.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which screen is open, where the user can go from it, and how it survives a recreation. */
class ScreenTest {
    @Test
    fun everyScreenRoundTripsThroughItsStoredKey() {
        for (screen in Screen.entries) {
            assertEquals(screen, Screen.fromStoredKey(screen.storedKey))
            assertEquals(screen, Screen.restored(screen.storedKey, existingWidget = true))
            assertEquals(screen, Screen.restored(screen.storedKey, existingWidget = false))
        }
    }

    @Test
    fun theStoredKeysAreExplicitAndNotOrdinals() {
        // Nothing must read its meaning out of the enum's ordering:
        val keys = Screen.entries.map { it.storedKey }
        assertEquals(keys.size, keys.toSet().size)
        Screen.entries.forEachIndexed { index, screen ->
            assertNotEquals(screen.name, index.toString(), screen.storedKey)
        }
        assertNull(Screen.fromStoredKey("0"))
        assertNull(Screen.fromStoredKey("1"))
        assertNull(Screen.fromStoredKey("2"))
    }

    @Test
    fun anExistingWidgetStartsOnTheMainScreen() {
        // A tap on a placed widget, or the app from the launcher: what the app is *for*.
        assertEquals(Screen.MAIN, Screen.freshLaunch(existingWidget = true))
        assertEquals(Screen.MAIN, Screen.restored(null, existingWidget = true))
    }

    @Test
    fun aNewWidgetStartsInSettings() {
        // The widget host configuring a brand-new widget has to start -- and end -- at the save
        // button in settings.
        assertEquals(Screen.SETTINGS, Screen.freshLaunch(existingWidget = false))
        assertEquals(Screen.SETTINGS, Screen.restored(null, existingWidget = false))
    }

    @Test
    fun settingsSurvivesARecreation() {
        assertEquals(Screen.SETTINGS, Screen.restored(Screen.SETTINGS.storedKey, existingWidget = true))
        assertNotEquals(Screen.freshLaunch(existingWidget = true), Screen.SETTINGS)
    }

    @Test
    fun thePriceTableSurvivesARecreationAsItsOwnCase() {
        // Neither of the two defaults, which is what makes this the case that proves restoration
        // instead of coincidence.
        assertEquals(Screen.PRICE_TABLE, Screen.restored(Screen.PRICE_TABLE.storedKey, existingWidget = true))
        assertEquals(Screen.PRICE_TABLE, Screen.restored(Screen.PRICE_TABLE.storedKey, existingWidget = false))
    }

    @Test
    fun anUnknownOrMissingStoredValueFallsBackToTheSameSafeDefault() {
        val garbage = listOf("", " ", "PLAN", "settings ", "Charging", "charging\n", "plan", "table")
        for (key in garbage) {
            // Same fallback rule as a fresh launch, per entry point:
            assertEquals(key, Screen.MAIN, Screen.restored(key, existingWidget = true))
            assertEquals(key, Screen.SETTINGS, Screen.restored(key, existingWidget = false))
        }
        for (screen in Screen.entries) assertNull(Screen.fromStoredKey(screen.storedKey.reversed()))
    }

    // navigation: two ways in, one way back

    @Test
    fun theMainScreenOpensExactlyTheTwoPanelsAndNothingElse() {
        // MAIN itself in this list would be a "navigation" that goes nowhere; a third entry would
        // be an action the main screen does not have.
        assertEquals(listOf(Screen.SETTINGS, Screen.PRICE_TABLE), Screen.panels)
    }

    @Test
    fun backFromSettingsReturnsToTheMainScreenForAConfiguredWidget() {
        assertEquals(Screen.MAIN, Screen.SETTINGS.back(existingWidget = true))
    }

    @Test
    fun backFromThePriceTableReturnsToTheMainScreen() {
        assertEquals(Screen.MAIN, Screen.PRICE_TABLE.back(existingWidget = true))
        // The table has no second parent: whoever opened it, Back leads out of it.
        assertEquals(Screen.MAIN, Screen.PRICE_TABLE.back(existingWidget = false))
    }

    @Test
    fun backFromTheMainScreenLeavesTheActivity() {
        // The root has nowhere to go, which is the platform's own normal finish.
        assertNull(Screen.MAIN.back(existingWidget = true))
        assertNull(Screen.MAIN.back(existingWidget = false))
    }

    @Test
    fun backFromAnUnconfiguredWidgetsSettingsKeepsTheWidgetHostCancelPath() {
        // A brand-new widget starts in Settings and must NOT be routed to the main screen:
        assertNull(Screen.SETTINGS.back(existingWidget = false))
    }

    @Test
    fun everyPanelLeadsBackToTheMainScreenWhichIsTheRoot() {
        // The policy as a whole:
        for (panel in Screen.panels) {
            val back = panel.back(existingWidget = true)
            assertEquals("${panel.name} must return to the main screen", Screen.MAIN, back)
            assertNull("the main screen is the root: nothing loops back out of it", Screen.MAIN.back(existingWidget = true))
        }
    }
}
