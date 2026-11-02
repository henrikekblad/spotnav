package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

/** The plan's `if_not_ready` (Home Assistant 2.4): read where stated, kept on every full write, never made up. */
class IfNotReadyCodecTest {
    private fun record() = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))

    @Test fun aStatedChoiceIsReadAndSentBackOnAFullWrite() {
        val record = record()
        assertEquals("ask", record.ifNotReady)
        assertEquals("hold", HaSettingsCodec.encodeBody(record.copy(ifNotReady = "hold")).getString("if_not_ready"))
        assertEquals("release", HaSettingsCodec.parseStored(HaSettingsCodec.encode(record.copy(ifNotReady = "release"))).ifNotReady)
    }

    @Test fun anOlderRecordHasNoneAndNoneIsSent() {
        val raw = HaFixtures.json("settings/v1/success.json").getJSONObject("settings").apply { remove("if_not_ready") }
        val record = HaSettingsCodec.parseResponse(raw)
        assertNull(record.ifNotReady)
        assertFalse(HaSettingsCodec.encodeBody(record).has("if_not_ready"))
    }

    @Test(expected = HaSettingsFormatException::class)
    fun anUnknownChoiceIsRefused() {
        HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings").put("if_not_ready", "maybe"))
    }
}
