package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.WebhookHttpStatusException
import java.net.ConnectException
import java.net.SocketTimeoutException

class ReadFailureLineTest {
    @Test fun noContactIsLeftToTheBanner() {
        assertFalse(ReadFailureLine.shown(ConnectException("Failed to connect to /192.168.1.2:8123")))
        assertFalse(ReadFailureLine.shown(SocketTimeoutException("timeout")))
        for (status in listOf(502, 503, 504)) assertFalse(ReadFailureLine.shown(WebhookHttpStatusException(status, "")))
        assertFalse(ReadFailureLine.shown(null))
    }

    @Test fun anAnswerHomeAssistantGaveIsShown() {
        assertTrue(ReadFailureLine.shown(WebhookHttpStatusException(404, "")))
        assertTrue(ReadFailureLine.shown(WebhookHttpStatusException(500, "")))
        assertTrue(ReadFailureLine.shown(IllegalStateException("not api_version 1")))
    }
}
