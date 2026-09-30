package se.sensnology.spotnav.testmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantSettings

/** The mock server's catalogue as untrusted input. */
class MockCatalogueTest {
    // The same rule a real charger's address has to pass, taken from the one place that defines it.
    private val allowed: (String) -> Boolean = { value -> HomeAssistantSettings.isAllowedBaseUrl(value) }

    private fun reply(
        service: String = "spotnav-mock",
        baseUrl: String = "http://192.168.1.50:8099",
        scenarios: String = """[{"webhook_id":"full","name":"Everything","why":"the ordinary screen"}]"""
    ) = """{"service":"$service","version":1,"base_url":"$baseUrl","scenarios":$scenarios}"""

    // the discovery reply

    @Test fun aGoodReplyBecomesAServerWithItsScenarios() {
        val server = MockCatalogue.serverFromReply(reply(), allowed)!!

        assertEquals("http://192.168.1.50:8099", server.baseUrl)
        assertEquals(1, server.scenarios.size)
        assertEquals("full", server.scenarios[0].webhookId)
        assertEquals("Everything", server.scenarios[0].name)
        assertEquals("the ordinary screen", server.scenarios[0].why)
    }

    @Test fun anotherServicesReplyIsIgnored() {
        // Anything on the network can answer a broadcast, including something that is not ours at
        // all.
        assertNull(MockCatalogue.serverFromReply(reply(service = "someone-else"), allowed))
        assertNull(MockCatalogue.serverFromReply("""{"version":1,"base_url":"http://192.168.1.50:8099"}""", allowed))
    }

    @Test fun aReplyWithoutAnAddressIsIgnored() {
        assertNull(MockCatalogue.serverFromReply("""{"service":"spotnav-mock","version":1}""", allowed))
        assertNull(MockCatalogue.serverFromReply(reply(baseUrl = ""), allowed))
    }

    @Test fun aReplyAddressingSomewhereWeMayNotTalkToIsIgnored() {
        // The whole point of validating: a broadcast reply is chosen by whoever sends it, and the
        // app only ever talks to HTTPS or a private HTTP host.
        assertNull(MockCatalogue.serverFromReply(reply(baseUrl = "http://example.com:8099"), allowed))
        assertNull(MockCatalogue.serverFromReply(reply(baseUrl = "ftp://192.168.1.50:8099"), allowed))
        assertNull(MockCatalogue.serverFromReply(reply(baseUrl = "not a url"), allowed))
    }

    @Test fun repliesThatAreNotOurKindOfJsonAreIgnored() {
        for (payload in listOf("", "not json", "[]", "42", "\"spotnav-mock\"", "null", "<html></html>")) {
            assertNull(payload, MockCatalogue.serverFromReply(payload, allowed))
        }
    }

    @Test fun aServerThatOffersNothingIsStillAServer() {
        // It answered, and it is ours: the picker is the right place to say it has nothing to
        // offer.
        val server = MockCatalogue.serverFromReply(reply(scenarios = "[]"), allowed)!!

        assertTrue(server.scenarios.isEmpty())
    }

    // the scenarios inside a reply

    @Test fun aScenarioWithoutAnAddressIsDropped() {
        val payload = reply(scenarios = """[{"name":"No id","why":"x"},{"webhook_id":"full","name":"ok"}]""")
        val server = MockCatalogue.serverFromReply(payload, allowed)!!

        assertEquals(listOf("full"), server.scenarios.map { it.webhookId })
    }

    @Test fun wrongTypesAreDroppedRatherThanCrashing() {
        val payload = reply(
            scenarios = """[
                {"webhook_id":{"nested":"object"},"name":"x"},
                {"webhook_id":["array"],"name":"x"},
                {"webhook_id":null,"name":"x"},
                {"webhook_id":"full","name":{"nested":"object"},"why":7},
                "not an object",
                42
            ]"""
        )
        val server = MockCatalogue.serverFromReply(payload, allowed)!!

        // Only the one with a usable address survives, and its missing name and unreadable why fall
        // back rather than failing the whole reply.
        assertEquals(1, server.scenarios.size)
        assertEquals("full", server.scenarios[0].webhookId)
        assertEquals("full", server.scenarios[0].name)
        assertEquals("", server.scenarios[0].why)
    }

    @Test fun scenariosThatAreNotAListAreDropped() {
        for (scenarios in listOf("""{"webhook_id":"full"}""", "\"full\"", "null")) {
            assertEquals(scenarios, 0, MockCatalogue.serverFromReply(reply(scenarios = scenarios), allowed)!!.scenarios.size)
        }
    }

    @Test fun unknownFieldsAreIgnored() {
        val payload = """{"service":"spotnav-mock","version":99,"base_url":"http://10.0.0.5:8099",
            "scenarios":[{"webhook_id":"full","name":"Everything","why":"why","extra":"field"}]}"""

        val server = MockCatalogue.serverFromReply(payload, allowed)!!

        assertEquals("http://10.0.0.5:8099", server.baseUrl)
        assertEquals(1, server.scenarios.size)
    }

    // the HTTP fallback

    @Test fun theCatalogueAddressIsTheTypedAddressPlusThePath() {
        assertEquals("http://192.168.1.50:8099/spotnav-mock/scenarios", MockCatalogue.catalogueUrl("http://192.168.1.50:8099"))
        assertEquals("http://192.168.1.50:8099/spotnav-mock/scenarios", MockCatalogue.catalogueUrl("http://192.168.1.50:8099/"))
    }

    @Test fun theTypedAddressIsTheOneTheScenariosAreReadAgainst() {
        // Whatever `base_url` the body claims, the address the user typed is what the profiles are
        // built on: a reply's own copy comes from a Host header and is worth nothing.
        val payload = """{"service":"spotnav-mock","base_url":"http://evil.example.com","scenarios":[{"webhook_id":"full"}]}"""

        val scenarios = MockCatalogue.scenariosFromCatalogue(payload, "http://192.168.1.50:8099", allowed)

        assertEquals(listOf("full"), scenarios!!.map { it.webhookId })
    }

    @Test fun aTypedAddressWeMayNotTalkToIsRefusedBeforeAnyRequest() {
        assertNull(MockCatalogue.scenariosFromCatalogue("""{"service":"spotnav-mock"}""", "http://example.com", allowed))
    }

    @Test fun theCatalogueEndpointIsRefusedWhenItIsNotOurs() {
        assertNull(MockCatalogue.scenariosFromCatalogue("<html>404</html>", "http://192.168.1.50:8099", allowed))
        assertNull(MockCatalogue.scenariosFromCatalogue("""{"service":"nginx"}""", "http://192.168.1.50:8099", allowed))
        // Ours, but with nothing to offer: an answer, not a refusal.
        assertEquals(
            emptyList<MockCatalogue.Scenario>(),
            MockCatalogue.scenariosFromCatalogue("""{"service":"spotnav-mock"}""", "http://192.168.1.50:8099", allowed)
        )
    }
}
