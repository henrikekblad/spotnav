package se.sensnology.spotnav.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.sites.SiteProfile
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures

class PhoneNotificationsSyncTest {
    private val appId = "S".repeat(43)
    private val door = PhoneDoor("http://ha.local:8123", HomeAssistantSettings("http://ha.local:8123", "site_hook"), listOf("p1"))
    private val answer = (PhoneNotificationsCall.answer(200, HaFixtures.json("webhook/phone_notifications_success.json").toString())
        as PhoneNotificationsCall.Outcome.Read).value

    private class Harness(
        var outcome: PhoneNotificationsCall.Outcome,
        var own: Pair<Boolean, Set<NotificationEvent>>? = null,
        var clock: Long = 1_000_000L
    ) {
        val store = PhoneNotificationsStore(FakeKeyValueStore())
        val bodies = mutableListOf<JSONObject>()
        val sync = PhoneNotificationsSync(
            store = store,
            appId = { "S".repeat(43) },
            deviceName = "Pixel 8",
            deviceModel = "GKWS6",
            call = { _, body -> bodies += body; outcome },
            ownChoice = { own },
            now = { clock }
        )
    }

    @Test
    fun theFirstReadNamesThePhoneAndSaysWhatTheAppShows() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer))
        val result = harness.sync.read(door)
        assertEquals(PhoneNotificationsSync.Result.Current(answer), result)
        val body = harness.bodies.single()
        assertEquals(appId, body.getString("app_id"))
        assertEquals("Pixel 8", body.getString("name"))
        assertEquals("GKWS6", body.getString("model"))
        assertFalse(body.has("push_ref"))
        assertFalse(body.has("set"))
        val shows = body.getJSONArray("shows").let { list -> (0 until list.length()).map { list.getString(it) } }
        assertEquals(PhoneNotificationsSync.SHOWS, shows)
        assertTrue(shows.containsAll(NotificationEvent.entries.map { it.wire }))
        assertTrue("heat_at_risk" in shows)
        assertEquals(answer, harness.store.current(door.key))

        // Once joined to its Home Assistant app, neither the name, the model nor what it shows is sent again.
        harness.sync.read(door)
        assertFalse(harness.bodies.last().has("name"))
        assertFalse(harness.bodies.last().has("model"))
        assertFalse(harness.bodies.last().has("shows"))
    }

    @Test
    fun anAppOnlyPhoneKeepsNamingItselfSoHomeAssistantCanFindItsHomeAssistantApp() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer.copy(companionName = null)))
        harness.sync.read(door)
        harness.sync.read(door)
        assertEquals("Pixel 8", harness.bodies.last().getString("name"))
        assertEquals("GKWS6", harness.bodies.last().getString("model"))
        assertFalse(harness.bodies.last().has("shows"))
        // Joined now: no longer.
        harness.outcome = PhoneNotificationsCall.Outcome.Read(answer)
        harness.sync.read(door)
        harness.sync.read(door)
        assertFalse(harness.bodies.last().has("name"))
        assertFalse(harness.bodies.last().has("model"))
    }

    @Test
    fun thePhonesOwnNameComesFirstAndTheModelOtherwise() {
        assertEquals("Henrik N6", PhoneName.choose(" Henrik N6 ", "CPH2765"))
        assertEquals("CPH2765", PhoneName.choose("  ", "CPH2765"))
        assertEquals("CPH2765", PhoneName.choose(null, "CPH2765"))
    }

    @Test
    fun theFirstContactCarriesTheReferenceSoAnOlderAppsEntryIsAdopted() {
        val bodies = mutableListOf<JSONObject>()
        val sync = PhoneNotificationsSync(
            store = PhoneNotificationsStore(FakeKeyValueStore()),
            appId = { appId },
            deviceName = "Pixel 8",
            call = { _, body -> bodies += body; PhoneNotificationsCall.Outcome.Read(answer) },
            ownChoice = { null },
            pushRef = { "ref_1234567890abcdef" }
        )
        sync.read(door)
        assertEquals("ref_1234567890abcdef", bodies[0].getString("push_ref"))
        sync.read(door)
        assertFalse(bodies[1].has("push_ref"))
    }

    @Test
    fun anOlderHomeAssistantIsMarkedAndAskedAgainLater() {
        val harness = Harness(PhoneNotificationsCall.Outcome.NotSupported)
        assertEquals(PhoneNotificationsSync.Result.Legacy, harness.sync.read(door))
        assertEquals(PhoneNotificationsStore.Mode.LEGACY, harness.store.mode(door.key))
        assertNull(harness.store.current(door.key))
        assertFalse(harness.sync.due(door))
        harness.clock += PhoneNotificationsSync.RECHECK_MS
        assertTrue(harness.sync.due(door))

        // Updated since: it answers, and the app follows Home Assistant from then on.
        harness.outcome = PhoneNotificationsCall.Outcome.Read(answer)
        harness.sync.read(door)
        assertEquals(PhoneNotificationsStore.Mode.CURRENT, harness.store.mode(door.key))
        assertTrue(harness.sync.due(door))
    }

    @Test
    fun aFailedReadKeepsWhatWasKnown() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer))
        harness.sync.read(door)
        harness.outcome = PhoneNotificationsCall.Outcome.Failed(null)
        assertEquals(PhoneNotificationsSync.Result.Failed(null), harness.sync.read(door))
        assertEquals(answer, harness.store.current(door.key))
    }

    @Test
    fun theReferenceAndAChangeAreSent() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer))
        harness.sync.read(door, PhoneNotificationsCall.Ref.Set(null), PhoneNotificationsCall.Change(on = false))
        val body = harness.bodies.single()
        assertTrue(body.has("push_ref"))
        assertTrue(body.isNull("push_ref"))
        assertEquals(false, body.getJSONObject("set").getBoolean("on"))
    }

    @Test
    fun theFirstContactCarriesTheOwnChoiceOfAnAppOnlyPhone() {
        val appOnly = answer.copy(companionName = null)
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(appOnly), own = false to setOf(NotificationEvent.PLAN_STOPPED))
        harness.sync.read(door)
        assertEquals(2, harness.bodies.size)
        val set = harness.bodies[1].getJSONObject("set")
        assertEquals(false, set.getBoolean("on"))
        // The charger events as this phone chose them; the pool's season kept as Home Assistant has it.
        val events = set.getJSONArray("events").let { list -> (0 until list.length()).map { list.getString(it) } }
        assertEquals(listOf("plan_stopped", "pool_season"), events)
        // Once only.
        harness.sync.read(door)
        assertEquals(3, harness.bodies.size)
        assertFalse(harness.bodies[2].has("set"))
    }

    @Test
    fun aPhoneJoinedToTheHomeAssistantAppKeepsWhatHomeAssistantHolds() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer), own = false to emptySet())
        harness.sync.read(door)
        assertEquals(1, harness.bodies.size)
    }

    @Test
    fun nothingIsCarriedWhenNoChoiceWasMade() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer.copy(companionName = null)), own = null)
        harness.sync.read(door)
        assertEquals(1, harness.bodies.size)
    }

    @Test
    fun aChoiceAlreadyAsHomeAssistantHoldsItIsNotWritten() {
        val events = answer.events.mapNotNull { NotificationEvent.of(it) }.toSet()
        assertNull(PhoneNotificationsSync.carried(answer, on = true, events = events))
    }

    @Test
    fun oneDoorPerHomeAssistantTheHomesWebhookFirst() {
        val site = SiteProfile("s1", "http://ha.local:8123/", "site_hook", "site_entry", "Home")
        val first = ChargerProfile("p1", "Garage", "http://ha.local:8123", "charger_hook", remoteChargerId = "entry_a")
        val second = ChargerProfile("p2", "Carport", "http://ha.local:8123", "charger_hook_2", remoteChargerId = "entry_b")
        val other = ChargerProfile("p3", "Cabin", "https://cabin.example", "cabin_hook")
        val doors = PhoneDoor.of(listOf(site), listOf(first, second, other))
        assertEquals(2, doors.size)
        assertEquals(PhoneDoor("http://ha.local:8123", HomeAssistantSettings("http://ha.local:8123/", "site_hook"), listOf("p1", "p2")), doors[0])
        // A pairing before 3.0: the charger's own webhook.
        assertEquals(HomeAssistantSettings("https://cabin.example", "cabin_hook"), doors[1].settings)
        assertFalse(doors[0].toString().contains("hook"))
    }

    @Test
    fun theLocalCheckFollowsHomeAssistantsAnswer() {
        val own = setOf(NotificationEvent.PLUGGED_IN)
        // An older Home Assistant: the phone's own choice.
        assertEquals(PhoneChoice(true, own), PhoneChoice.of(null, "entry_a", true, own))
        assertEquals(PhoneChoice(false, emptySet()), PhoneChoice.of(null, "entry_a", false, own))
        // Home Assistant's answer: its events, never the own ones.
        val appOnly = answer.copy(companionName = null, companionFound = false)
        val chosen = PhoneChoice.of(appOnly, "entry_a", false, own)
        assertTrue(chosen.enabled)
        assertEquals(setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.PLAN_AT_RISK, NotificationEvent.CHARGE_COMPLETE, NotificationEvent.VEHICLE_IDENTIFY), chosen.events)
        // Nothing for a charger the phone does not follow, nothing at all while it is off.
        assertEquals(PhoneChoice(false, emptySet()), PhoneChoice.of(answer.copy(chargers = listOf("entry_b")), "entry_a", true, own))
        assertEquals(PhoneChoice(false, emptySet()), PhoneChoice.of(answer.copy(on = false), "entry_a", true, own))
        assertFalse(PhoneChoice.siteAtRisk(answer.copy(on = false), true, setOf(NotificationEvent.PLAN_AT_RISK)))
        assertTrue(PhoneChoice.siteAtRisk(appOnly, false, emptySet()))
        assertTrue(PhoneChoice.siteAtRisk(null, true, setOf(NotificationEvent.PLAN_AT_RISK)))
        assertFalse(PhoneChoice.wanted(answer.copy(on = false), true))
        assertTrue(PhoneChoice.wanted(null, true))
    }

    @Test
    fun aPhoneWithBothAppsAndInstantOffIsToldOnlyByHomeAssistantsApp() {
        val own = setOf(NotificationEvent.PLUGGED_IN)
        // Companion found, instant off: Home Assistant delivers it, the local check posts nothing.
        assertTrue(answer.companionFound && !answer.instant)
        assertEquals(PhoneChoice(false, emptySet()), PhoneChoice.of(answer, "entry_a", true, own))
        assertFalse(PhoneChoice.siteAtRisk(answer, true, own + NotificationEvent.PLAN_AT_RISK))
        assertFalse(PhoneChoice.wanted(answer, true))
        // Instant on: the wake-up path, so the local check stands as before.
        val instant = answer.copy(instant = true)
        assertTrue(PhoneChoice.of(instant, "entry_a", false, own).enabled)
        assertTrue(PhoneChoice.wanted(instant, false))
        // The Companion service gone: the app posts again.
        val gone = answer.copy(companionFound = false)
        assertTrue(PhoneChoice.of(gone, "entry_a", false, own).enabled)
        assertTrue(PhoneChoice.wanted(gone, false))
    }

    @Test
    fun theFirstContactCarriesTheOwnOnOffInItsSet() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer), own = false to setOf(NotificationEvent.PLUGGED_IN))
        harness.sync.read(door)
        assertFalse(harness.bodies.first().getJSONObject("set").getBoolean("on"))
    }

    @Test
    fun forgottenHomeAssistantsArePruned() {
        val harness = Harness(PhoneNotificationsCall.Outcome.Read(answer))
        harness.sync.read(door)
        harness.store.prune(emptySet())
        assertNull(harness.store.mode(door.key))
        assertNull(harness.store.current(door.key))
    }
}
