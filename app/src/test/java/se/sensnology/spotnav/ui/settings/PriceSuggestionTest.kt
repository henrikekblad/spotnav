package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.RelayFixtures

/** The figure a fee's editor suggests: the area's own published suggestion, as the old form filled in. */
class PriceSuggestionTest {
    @Test fun eachFeeSuggestsTheAreasOwnFigure() {
        assertEquals(36.0, PriceRows.suggestion(RelayFixtures.se4, PriceRows.Fee.TAX)!!, 0.0)
        assertEquals(30.0, PriceRows.suggestion(RelayFixtures.se4, PriceRows.Fee.GRID)!!, 0.0)
        assertEquals(7.13, PriceRows.suggestion(RelayFixtures.no1, PriceRows.Fee.TAX)!!, 0.0)
        // No figure published, or no area: nothing to suggest.
        assertNull(PriceRows.suggestion(RelayFixtures.no1, PriceRows.Fee.GRID))
        assertNull(PriceRows.suggestion(null, PriceRows.Fee.TAX))
    }
}
