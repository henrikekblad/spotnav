package se.sensnology.spotnav.ha.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.widget.WidgetChargerBinding

/** What one approved pairing does to a widget's charger binding. */
class ChargerPairingApprovalTest {
    private val instance = "http://192.168.1.50:8123"

    private fun profile(localId: String, remoteChargerId: String? = "entry_$localId", baseUrl: String = instance) =
        ChargerProfile(
            localId = localId,
            displayName = "",
            baseUrl = baseUrl,
            webhookId = "wh-$localId",
            remoteChargerId = remoteChargerId,
            remoteChargerName = "Charger $localId"
        )

    private fun charger(id: String, name: String = "Charger $id", webhookId: String = "wh-$id") =
        PairedCharger(id = id, name = name, webhookId = webhookId)

    private fun approved(vararg chargers: PairedCharger) = PairingStop.Approved(instance, chargers.toList())

    /** A deterministic stand-in for `ChargerProfileStore.newLocalId()`. */
    private fun ids(vararg assigned: String): () -> String {
        val remaining = assigned.toMutableList()
        return { remaining.removeAt(0) }
    }

    private fun apply(
        outcome: PairingStop,
        existing: List<ChargerProfile> = emptyList(),
        storedBinding: WidgetChargerBinding = WidgetChargerBinding(initialized = false, chargerProfileId = null),
        newLocalId: () -> String = ids("fresh-1", "fresh-2")
    ) = ChargerPairingApproval.apply(outcome, existing, instance, storedBinding, newLocalId)

    private fun bindingOf(applied: ChargerPairingApproval.Applied?) = applied?.binding

    // the cases that bind, and the cases that must not

    @Test fun anUndecidedBindingTakesTheSoleReturnedCharger() {
        val applied = apply(approved(charger("entry_a")), newLocalId = ids("local-new"))

        assertEquals(ChargerPairingApproval.Binding.Bind("local-new"), bindingOf(applied))
        assertEquals(listOf("local-new"), applied?.profiles?.map { it.localId })
    }

    @Test fun anExplicitNoneIsReplacedByTheSoleReturnedCharger() {
        // "No charger" is a decision, not an undecided state, and an approval on this widget is a
        // later and stronger statement of intent than the choice made before it -- but only here,
        // and only from a real approval.
        val applied = apply(
            approved(charger("entry_a")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = null),
            newLocalId = ids("local-new")
        )

        assertEquals(ChargerPairingApproval.Binding.Bind("local-new"), bindingOf(applied))
    }

    @Test fun aStaleBindingIsRepairedByTheSoleReturnedCharger() {
        val applied = apply(
            approved(charger("entry_b")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-gone"),
            newLocalId = ids("local-new")
        )

        assertEquals(ChargerPairingApproval.Binding.Bind("local-new"), bindingOf(applied))
    }

    @Test fun aStaleBindingThatProvisioningRestoresIsKept() {
        // Re-pairing found the same charger again, so the very id the binding names exists once
        // more.
        val applied = apply(
            approved(charger("entry_a")),
            existing = listOf(profile(localId = "local-a", remoteChargerId = "entry_a")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-a")
        )

        assertEquals(ChargerPairingApproval.Binding.Unchanged, bindingOf(applied))
        assertEquals(listOf("local-a"), applied?.profiles?.map { it.localId })
    }

    @Test fun aValidBindingIsNeverStolenByASoleDifferentCharger() {
        val applied = apply(
            approved(charger("entry_b")),
            existing = listOf(profile(localId = "local-a", remoteChargerId = "entry_a")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-a"),
            newLocalId = ids("local-new")
        )

        assertEquals(ChargerPairingApproval.Binding.Unchanged, bindingOf(applied))
        // The new charger is still provisioned.
        assertEquals(listOf("local-new"), applied?.profiles?.map { it.localId })
    }

    @Test fun zeroReturnedChargersChangeNoBinding() {
        val applied = apply(approved(), storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = null))

        assertEquals(ChargerPairingApproval.Binding.Unchanged, bindingOf(applied))
        assertTrue(applied?.profiles?.isEmpty() == true)
    }

    @Test fun twoReturnedChargersChangeNoBinding() {
        val applied = apply(
            approved(charger("entry_a"), charger("entry_b")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = null),
            newLocalId = ids("local-1", "local-2")
        )

        assertEquals(ChargerPairingApproval.Binding.Unchanged, bindingOf(applied))
        // Both are provisioned, and the user chooses between them on the card.
        assertEquals(listOf("local-1", "local-2"), applied?.profiles?.map { it.localId })
    }

    // nothing but an approval decides anything

    @Test fun anUnsuccessfulPairingDecidesNothing() {
        // Every way a pairing can fail to be approved.
        val nothing = listOf<PairingStop>(
            PairingStop.Denied,
            PairingStop.Expired,
            PairingStop.TimedOut,
            PairingStop.Unusable
        )

        for (outcome in nothing) {
            val applied = apply(
                outcome,
                storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = null)
            )
            assertNull("$outcome must not decide anything", applied)
        }
    }

    // what the binding is written *with*

    @Test fun onlyALocalProfileIdIsEverBound() {
        // A remote charger id and a webhook id are different identities on purpose; a binding names
        // the app's own profile id and nothing else.
        val applied = apply(
            approved(charger("entry_ha_42", name = "Garage", webhookId = "webhook_secret_42")),
            newLocalId = ids("local-9f2")
        )

        assertEquals(ChargerPairingApproval.Binding.Bind("local-9f2"), bindingOf(applied))
        val bound = (bindingOf(applied) as ChargerPairingApproval.Binding.Bind).localId
        assertFalse(bound.contains("entry_ha_42"))
        assertFalse(bound.contains("webhook_secret_42"))
    }

    @Test fun aReusedProfileKeepsItsLocalIdWebhookAndTypedName() {
        val held = profile(localId = "local-a", remoteChargerId = "entry_a").copy(
            displayName = "My garage",
            selectedVehicleId = "car-1",
            targetSocPercent = 80
        )
        val applied = apply(
            approved(charger("entry_a", name = "Garage renamed", webhookId = "wh-new")),
            existing = listOf(held),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-a")
        )

        val refreshed = applied?.profiles?.single()
        assertEquals("local-a", refreshed?.localId)
        assertEquals("wh-new", refreshed?.webhookId)
        assertEquals("Garage renamed", refreshed?.remoteChargerName)
        assertEquals("My garage", refreshed?.displayName)
        assertEquals("car-1", refreshed?.selectedVehicleId)
        assertEquals(80, refreshed?.targetSocPercent)
    }

    @Test fun anotherInstancesChargerIsNotReusedForThisInstance() {
        val elsewhere = profile(localId = "local-other", remoteChargerId = "entry_a", baseUrl = "http://other:8123")
        val applied = apply(
            approved(charger("entry_a")),
            existing = listOf(elsewhere),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = null),
            newLocalId = ids("local-new")
        )

        val provisioned = applied?.profiles?.single()
        assertEquals("local-new", provisioned?.localId)
        assertEquals(instance, provisioned?.baseUrl)
    }

    /** Records what was written, and in which order. */
    private class Recorder(private val active: String? = null) : ChargerPairingApproval.Device {
        val written = mutableListOf<ChargerProfile>()
        val order = mutableListOf<String>()
        var activated: String? = null
        var bound: String? = null
        var repaints = 0

        val forgotten = mutableListOf<String>()

        override fun forgetCached(localId: String) {
            forgotten += localId
            order += "forget:$localId"
        }

        override fun upsert(profile: ChargerProfile) {
            written += profile
            order += "upsert:${profile.localId}"
        }

        override fun activeProfileId(): String? = active

        override fun activate(localId: String) {
            activated = localId
            order += "activate:$localId"
        }

        override fun bind(localId: String) {
            bound = localId
            order += "bind:$localId"
        }

        override fun repaint() {
            repaints += 1
            order += "repaint"
        }
    }

    @Test fun theBindingIsWrittenAfterProvisioningAndBeforeTheRepaint() {
        val device = Recorder()
        val applied = apply(approved(charger("entry_a")), newLocalId = ids("local-new"))

        ChargerPairingApproval.commit(applied, device)

        assertEquals(listOf("upsert:local-new", "activate:local-new", "bind:local-new", "repaint"), device.order)
        assertEquals("local-new", device.bound)
        assertEquals(1, device.repaints)
    }

    @Test fun aRePairedProfileForgetsItsCachesFirstAndANewOneHasNothingToForget() {
        val held = profile(localId = "local-a", remoteChargerId = "entry_a")
        val device = Recorder(active = "local-a")
        val applied = apply(
            approved(charger("entry_a", webhookId = "wh-new"), charger("entry_b")),
            existing = listOf(held),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-a"),
            newLocalId = ids("local-new")
        )

        ChargerPairingApproval.commit(applied, device)

        assertEquals(listOf("local-a"), device.forgotten)
        assertEquals(listOf("forget:local-a", "upsert:local-a", "upsert:local-new", "repaint"), device.order)
    }

    @Test fun anAlreadyActiveDeviceKeepsItsActiveProfile() {
        val device = Recorder(active = "local-other")
        ChargerPairingApproval.commit(apply(approved(charger("entry_a")), newLocalId = ids("local-new")), device)

        assertNull(device.activated)
        assertEquals(listOf("upsert:local-new", "bind:local-new", "repaint"), device.order)
    }

    @Test fun anUnchangedBindingIsNotWrittenAtAll() {
        val device = Recorder(active = "local-a")
        val applied = apply(
            approved(charger("entry_b")),
            existing = listOf(profile(localId = "local-a", remoteChargerId = "entry_a")),
            storedBinding = WidgetChargerBinding(initialized = true, chargerProfileId = "local-a"),
            newLocalId = ids("local-new")
        )

        ChargerPairingApproval.commit(applied, device)

        assertNull(device.bound)
        assertEquals(listOf("upsert:local-new", "repaint"), device.order)
    }

    @Test fun anUnsuccessfulPairingWritesNothingAndDoesNotRepaint() {
        val device = Recorder()
        ChargerPairingApproval.commit(apply(PairingStop.Expired), device)

        assertTrue(device.order.isEmpty())
        assertEquals(0, device.repaints)
    }

    @Test fun aCancelledOrNeverReachedPairingAppliesNothing() {
        // What a caller with no approval to apply hands over: nothing.
        val device = Recorder()
        ChargerPairingApproval.commit(null, device)

        assertTrue(device.order.isEmpty())
        assertEquals(0, device.repaints)
    }
}
