package se.sensnology.spotnav.prices.entsoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.RelayUpstream

/** The relay's acknowledgement classification (`entsoe_http_test.go`), on the parsed acknowledgement. */
class EntsoeAckTest {
    private val noDataText = "No matching data found for the requested document and interval."
    private val authText = "The securitytoken is not valid. Please check your credentials."
    private val rateText = "Too many requests for this token; slow down."
    private val badText = "Parameter out_Domain is not a valid EIC code."

    private fun ack(vararg reasons: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<Acknowledgement_MarketDocument xmlns=\"urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3\">\n")
        append("  <mRID>ack</mRID>\n  <createdDateTime>2026-09-19T13:45:12Z</createdDateTime>")
        for ((code, text) in reasons) append("\n  <Reason><code>$code</code><text>$text</text></Reason>")
        append("\n</Acknowledgement_MarketDocument>\n")
    }

    private fun kind(body: String, status: Int): AckKind = EntsoeAck.parse(body)!!.kind(status)

    @Test
    fun theFixturesAreClassifiedByTheirText() {
        val noData = RelayUpstream.read("synthetic_ack_999_no_data.xml")
        val auth = RelayUpstream.read("synthetic_ack_999_auth.xml")
        val unknown = RelayUpstream.read("synthetic_ack_999_unknown.xml")
        val rateLimited = noData.replaceFirst(noDataText, rateText)
        val badRequest = noData.replaceFirst(noDataText, badText)
        val otherCode = noData.replaceFirst("<code>999</code>", "<code>1000</code>")

        assertEquals(AckKind.NO_DATA, kind(noData, 200))
        assertEquals(AckKind.NO_DATA, kind(noData, 400))
        assertEquals("no data only arrives with 200 or 400", AckKind.UNKNOWN, kind(noData, 500))
        assertEquals(AckKind.AUTH, kind(auth, 200))
        assertEquals(AckKind.AUTH, kind(auth, 400))
        assertEquals(AckKind.RATE_LIMIT, kind(rateLimited, 400))
        assertEquals(AckKind.BAD_REQUEST, kind(badRequest, 400))
        assertEquals(AckKind.UNKNOWN, kind(unknown, 400))
        assertEquals(AckKind.UNKNOWN, kind(otherCode, 400))
        assertEquals(AckKind.NO_DATA, kind(RelayUpstream.read("entsoe_SE4_2026-09-24_no_data.xml"), 200))
    }

    @Test
    fun aMixedAcknowledgementIsNeverNoData() {
        val cases = listOf(
            ack("999" to authText, "999" to noDataText) to AckKind.AUTH,
            ack("999" to noDataText, "999" to authText) to AckKind.AUTH,
            ack("999" to rateText, "999" to noDataText) to AckKind.RATE_LIMIT,
            ack("999" to noDataText, "999" to rateText) to AckKind.RATE_LIMIT,
            ack("999" to badText, "999" to noDataText) to AckKind.BAD_REQUEST,
            ack("999" to noDataText, "999" to badText) to AckKind.BAD_REQUEST,
            ack("999" to noDataText, "999" to "Something else entirely.") to AckKind.UNKNOWN,
            ack("999" to noDataText, "999" to "No matching data found.") to AckKind.NO_DATA,
            ack("999" to "") to AckKind.UNKNOWN,
            ack("999" to noDataText, "1000" to authText) to AckKind.AUTH,
            ack("1000" to noDataText) to AckKind.UNKNOWN
        )
        for ((body, want) in cases) assertEquals(body, want, kind(body, 400))
        assertEquals(AckKind.AUTH, kind(ack("999" to authText, "999" to noDataText), 200))
    }

    @Test
    fun theReasonCodeIsTheFirstStatedOne() {
        assertEquals("999", EntsoeAck.parse(ack("" to "x", "999" to noDataText))!!.reasonCode())
        assertEquals("none", EntsoeAck.parse(ack())!!.reasonCode())
    }

    @Test
    fun onlyAnAcknowledgementIsOne() {
        assertNull(EntsoeAck.parse(RelayUpstream.read("synthetic_a44_60m.xml")))
        assertNull(EntsoeAck.parse(""))
        assertNull(EntsoeAck.parse("<html>maintenance</html>"))
    }
}
