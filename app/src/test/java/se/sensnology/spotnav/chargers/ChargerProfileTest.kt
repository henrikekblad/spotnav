package se.sensnology.spotnav.chargers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerProfileTest {
    private val profile = ChargerProfile(
        localId = "local-1",
        displayName = "Garage charger",
        baseUrl = "https://ha.example.com",
        webhookId = "super-secret-webhook-id"
    )

    @Test fun toStringNeverContainsTheWebhookId() {
        assertFalse(profile.toString().contains(profile.webhookId))
        assertTrue(profile.toString().contains("<redacted>"))
    }

    @Test fun toStringStillContainsNonSecretIdentifyingFields() {
        val text = profile.toString()
        assertTrue(text.contains("local-1"))
        assertTrue(text.contains("Garage charger"))
    }

    @Test fun configuredRequiresBothBaseUrlAndWebhookId() {
        assertTrue(profile.configured)
        assertFalse(profile.copy(baseUrl = "").configured)
        assertFalse(profile.copy(webhookId = "").configured)
    }

    @Test fun webhookUrlJoinsBaseUrlAndWebhookIdWithoutDuplicateSlash() {
        val withTrailingSlash = profile.copy(baseUrl = "https://ha.example.com/")
        org.junit.Assert.assertEquals(
            "https://ha.example.com/api/webhook/super-secret-webhook-id",
            withTrailingSlash.webhookUrl()
        )
    }

    @Test fun toHomeAssistantSettingsCarriesOnlyTheUrlAndWebhook() {
        val settings = profile.toHomeAssistantSettings()
        org.junit.Assert.assertEquals(profile.baseUrl, settings.baseUrl)
        org.junit.Assert.assertEquals(profile.webhookId, settings.webhookId)
    }
}
