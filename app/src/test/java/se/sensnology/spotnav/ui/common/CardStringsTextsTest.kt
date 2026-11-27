package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.CardStringsXml

/** The card's words the app shows: every mapped key in every language, and the texts the 3.0 card changed since b2. */
class CardStringsTextsTest {
    @Test fun everyMappedKeyIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val table = CardStringsXml.strings(language)
            for (key in CardStrings.IDS.keys) assertTrue("$language has no $key", CardStrings.resourceName(key) in table)
        }
    }

    @Test fun theTextsTheCardChangedSinceB2() {
        val sv = CardStringsXml.words("sv")
        val en = CardStringsXml.words("en")
        assertEquals("Extra varmvatten", sv.say("heat.settings.hotWater"))
        assertEquals("Hot water boost", en.say("heat.settings.hotWater"))
        assertTrue(en.say("heat.settings.hotWaterHelp").startsWith("The heat pump's own input for one extra hot-water run"))
        assertTrue(en.say("heat.settings.waterHeaterHelp").contains("Hot water boost"))
        assertEquals("Anläggningens egen importgräns skyddar säkringen vid laddning från nätet.", sv.say("battery.cap.importLimit"))
        assertEquals("No lowering planned for the next 24 hours", en.say("heat.ahead.none"))
        assertFalse("heat.settings.offsetHelp" in CardStrings.IDS)
    }

    @Test fun theNowTabsNewWordsAreMappedAndTheCornersWordsAreGone() {
        val gone = listOf("sun", "grid", "sold", "battery", "car", "heat", "house", "carToday", "today", "houseNoCar", "line.heat.in", "line.heat.out")
        for (key in gone) assertFalse(key, "house.nowTab.$key" in CardStrings.IDS)
        val nowKeys = CardStrings.IDS.keys.filter { it.startsWith("house.nowTab.") }
        assertEquals(16, nowKeys.count { it.startsWith("house.nowTab.why.") })
        assertEquals(5, nowKeys.count { it.startsWith("house.nowTab.today.") })
        val sv = CardStringsXml.words("sv")
        val en = CardStringsXml.words("en")
        assertEquals("87 % egen el", sv.say("house.nowTab.own", "pct" to "87"))
        assertEquals("Expensive hour · 1.23 kr", en.say("house.nowTab.price.dear", "price" to "1.23 kr"))
        assertEquals("Pool, spa, bastu", sv.say("house.nowTab.part.pool"))
    }
}
