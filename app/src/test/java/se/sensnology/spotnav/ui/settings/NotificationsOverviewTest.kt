package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.HaNotifyService
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.push.PushRegistration
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.io.File
import java.util.Locale

/** The Notifications card's summary, the dialog's help, and what Save writes where. */
class NotificationsOverviewTest {
    /** The string resources of one locale, looked up by id as the app does. */
    private class Strings(private val directory: String, language: String) : NotificationTexts {
        private val locale = Locale.forLanguageTag(language)
        private val xml = listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }
            .first { it.exists() }.readText()

        private fun name(owner: Class<*>, id: Int) = owner.fields.first { it.getInt(null) == id }.name

        private fun raw(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(xml)!!.groupValues[1].replace("\\'", "'")

        override fun text(id: Int, vararg args: Any) = String.format(locale, raw(name(R.string::class.java, id)), *args)

        override fun quantity(id: Int, count: Int, vararg args: Any): String {
            val block = xml.substringAfter("<plurals name=\"${name(R.plurals::class.java, id)}\">").substringBefore("</plurals>")
            val form = if (count == 1) "one" else "other"
            val pattern = Regex("<item quantity=\"$form\">(.*?)</item>").find(block)!!.groupValues[1]
            return String.format(locale, pattern, *args)
        }
    }

    private val sv = Strings("values-sv", "sv")
    private val en = Strings("values", "en")

    private val n6 = HaNotifyService("notify.mobile_app_n6", "N6")
    private val pixel = HaNotifyService("notify.mobile_app_pixel", "Pixel")
    private val three = NotificationEvent.LOCAL_DEFAULTS.map { it.wire }

    private fun ha(targets: List<String>, events: List<String> = three) =
        HaNotificationSettings(targets = targets, events = events, available = listOf(n6, pixel))

    // --- The card's summary -----------------------------------------------------------------------

    @Test fun theHomeAssistantRowNamesNoPhoneOnePhoneOrHowMany() {
        assertEquals("Ingen telefon vald", NotificationsOverview.homeAssistant(ha(emptyList()), sv))
        assertEquals("N6 · 3 av 8 händelser", NotificationsOverview.homeAssistant(ha(listOf(n6.service)), sv))
        assertEquals("2 telefoner · 3 av 8 händelser", NotificationsOverview.homeAssistant(ha(listOf(n6.service, pixel.service)), sv))
        assertEquals("No phone chosen", NotificationsOverview.homeAssistant(ha(emptyList()), en))
        assertEquals("N6 · 3 of 8 events", NotificationsOverview.homeAssistant(ha(listOf(n6.service)), en))
        assertEquals("2 phones · 3 of 8 events", NotificationsOverview.homeAssistant(ha(listOf(n6.service, pixel.service)), en))
    }

    @Test fun aChosenPhoneThatIsGoneIsMarkedAndAnUnknownEventIsNotCounted() {
        val settings = ha(listOf("notify.mobile_app_old"), events = three + "from_the_future")
        assertEquals("notify.mobile_app_old (hittas inte) · 3 av 8 händelser", NotificationsOverview.homeAssistant(settings, sv))
    }

    @Test fun theSpotNavRowIsOffEveryFifteenMinutesOrInstant() {
        val events = NotificationEvent.LOCAL_DEFAULTS.toSet()
        assertEquals("Av", NotificationsOverview.spotNav(false, events, instant = true, texts = sv))
        assertEquals("Var 15:e minut · 3 av 7 händelser", NotificationsOverview.spotNav(true, events, instant = false, texts = sv))
        assertEquals("Direkt · 3 av 7 händelser", NotificationsOverview.spotNav(true, events, instant = true, texts = sv))
        assertEquals("Off", NotificationsOverview.spotNav(false, events, instant = false, texts = en))
        assertEquals("Every 15 minutes · 1 of 7 events",
            NotificationsOverview.spotNav(true, setOf(NotificationEvent.PLAN_STOPPED), instant = false, texts = en))
        assertEquals("Instant · 3 of 7 events", NotificationsOverview.spotNav(true, events, instant = true, texts = en))
    }

    @Test fun everyLocaleHasTheNewWordingAndNoneOfTheOld() {
        val names = listOf(
            "notify_companion_title", "notify_companion_intro", "notify_app_title", "notify_local_toggle", "notify_local_help",
            "notify_push_toggle", "notify_push_help", "notify_phones_none", "notify_app_off",
            "notify_app_periodic", "notify_app_instant"
        )
        for (dir in listOf("values", "values-sv", "values-nb", "values-da", "values-fi")) {
            val xml = listOf("src/main/res", "app/src/main/res").map { File(it, "$dir/strings.xml") }.first { it.exists() }.readText()
            for (name in names) assertTrue("$dir $name", xml.contains("<string name=\"$name\">"))
            assertTrue("$dir phones", xml.contains("<plurals name=\"notify_phones_count\">"))
            assertTrue("$dir events", xml.contains("<plurals name=\"notify_events_count\">"))
            for (gone in listOf("notify_events_on", "notify_local_title", "notify_local_events_change", "notify_change")) {
                assertFalse("$dir $gone", xml.contains("name=\"$gone\""))
            }
        }
    }

    // --- The dialog's help ------------------------------------------------------------------------

    @Test fun theHelpFollowsTheInstantSwitch() {
        assertEquals("Aviseringen kommer direkt. Väckningen innehåller inget om din laddning.",
            sv.text(NotificationsOverview.spotNavHelp(instantChecked = true)))
        assertEquals("SpotNav kollar ungefär var 15:e minut, så en avisering kan komma upp till 15 minuter sent.",
            sv.text(NotificationsOverview.spotNavHelp(instantChecked = false)))
    }

    @Test fun aFailedTurnOnSaysWhyInOneLine() {
        assertNull(NotificationsOverview.pushFailure(null))
        assertNull(NotificationsOverview.pushFailure(PushRegistration.Result.ON))
        assertNull(NotificationsOverview.pushFailure(PushRegistration.Result.OFF))
        assertEquals(R.string.notify_push_no_token, NotificationsOverview.pushFailure(PushRegistration.Result.NO_TOKEN))
        assertEquals(R.string.notify_push_server_off, NotificationsOverview.pushFailure(PushRegistration.Result.SERVER_OFF))
        assertEquals(R.string.notify_push_rate_limited, NotificationsOverview.pushFailure(PushRegistration.Result.RATE_LIMITED))
        assertEquals(R.string.notify_push_failed, NotificationsOverview.pushFailure(PushRegistration.Result.FAILED))
    }

    // --- Save -------------------------------------------------------------------------------------

    private class FakePush(override val offered: Boolean = true, override var on: Boolean = false) : NotificationsSave.Push {
        val calls = mutableListOf<Boolean>()
        var answer: ((PushRegistration.Result) -> Unit)? = null
        override fun set(on: Boolean, done: (PushRegistration.Result) -> Unit) {
            calls += on
            answer = done
        }
    }

    private class Harness(offered: Boolean = true, pushOn: Boolean = false) {
        val local = LocalNotificationStore(FakeKeyValueStore())
        val push = FakePush(offered, pushOn)
        val writes = mutableListOf<Pair<List<String>, List<String>>>()
        var haAnswer: ((String?) -> Unit)? = null
        var synced = 0
        var permissionAsked = 0
        val outcomes = mutableListOf<NotificationsSave.Outcome>()
        val save = NotificationsSave(
            local = local,
            push = push,
            writeHomeAssistant = { targets, events, done -> writes += targets to events; haAnswer = done },
            localChanged = { synced += 1 },
            turnedOn = { permissionAsked += 1 }
        )

        fun run(current: HaNotificationSettings?, choice: NotificationsSave.Choice) = save.save(current, choice) { outcomes += it }
    }

    private val defaults = NotificationEvent.LOCAL_DEFAULTS.toSet()

    private fun choice(
        homeAssistant: NotificationsSave.HomeAssistantChoice? = null,
        on: Boolean = false,
        events: Set<NotificationEvent> = defaults,
        instant: Boolean = false
    ) = NotificationsSave.Choice(homeAssistant, on, events, instant)

    @Test fun nothingChangedWritesNothingAndClosesAtOnce() {
        val harness = Harness()
        val current = ha(listOf(n6.service))
        harness.run(current, choice(NotificationsSave.HomeAssistantChoice(listOf(n6.service), three.reversed())))
        assertEquals(listOf(NotificationsSave.Outcome()), harness.outcomes)
        assertTrue(harness.writes.isEmpty())
        assertEquals(0, harness.synced)
        assertTrue(harness.push.calls.isEmpty())
    }

    @Test fun theHomeAssistantChoiceGoesThroughTheWebhookAndKeepsUnknownEvents() {
        val current = ha(listOf(n6.service), events = three + "from_the_future")
        val phones = NotificationPhones.of(current)
        val ticked = NotificationsSave.homeAssistantChoice(
            current, phones, phoneTicked = listOf(true, true),
            eventTicked = NotificationEvent.entries.map { it == NotificationEvent.CHARGE_COMPLETE }
        )
        assertEquals(listOf(n6.service, pixel.service), ticked.targets)
        assertEquals(listOf("charge_complete", "from_the_future"), ticked.events)
        val harness = Harness()
        harness.run(current, choice(ticked))
        assertEquals(listOf(ticked.targets to ticked.events), harness.writes)
        assertTrue(harness.outcomes.isEmpty())
        harness.haAnswer!!(null)
        assertTrue(harness.outcomes.single().saved)
    }

    @Test fun aHomeAssistantRefusalKeepsTheDialogOpenWithItsWords() {
        val harness = Harness()
        harness.run(ha(emptyList()), choice(NotificationsSave.HomeAssistantChoice(listOf(n6.service), three)))
        harness.haAnswer!!("Changed elsewhere")
        assertFalse(harness.outcomes.single().saved)
        assertEquals("Changed elsewhere", harness.outcomes.single().homeAssistantError)
    }

    @Test fun readOnlyHomeAssistantIsNotWritten() {
        val harness = Harness()
        harness.run(ha(emptyList()), choice(homeAssistant = null, on = true))
        assertTrue(harness.writes.isEmpty())
        assertTrue(harness.local.enabled)
    }

    @Test fun turningTheSpotNavAppOnStoresItSyncsAndAsksForThePermission() {
        val harness = Harness()
        val events = setOf(NotificationEvent.PLUGGED_IN)
        harness.run(null, choice(on = true, events = events))
        assertTrue(harness.local.enabled)
        assertEquals(events, harness.local.events)
        assertEquals(1, harness.synced)
        assertEquals(1, harness.permissionAsked)
        assertTrue(harness.outcomes.single().saved)
        // Changing only the events later syncs again (instant notifications get the new events) without asking.
        harness.run(null, choice(on = true, events = defaults))
        assertEquals(defaults, harness.local.events)
        assertEquals(2, harness.synced)
        assertEquals(1, harness.permissionAsked)
    }

    @Test fun turningTheSpotNavAppOffLeavesInstantAsItIs() {
        val harness = Harness(pushOn = true)
        harness.local.enabled = true
        harness.run(null, choice(on = false, instant = false))
        assertFalse(harness.local.enabled)
        assertEquals(1, harness.synced)
        assertTrue(harness.push.calls.isEmpty())
    }

    @Test fun instantIsTurnedOnAfterTheLocalSwitchAndAFailureSaysWhy() {
        val harness = Harness()
        harness.run(null, choice(on = true, instant = true))
        // The local switch is stored first: the registration reads it.
        assertTrue(harness.local.enabled)
        assertEquals(listOf(true), harness.push.calls)
        assertTrue(harness.outcomes.isEmpty())
        harness.push.answer!!(PushRegistration.Result.SERVER_OFF)
        assertEquals(PushRegistration.Result.SERVER_OFF, harness.outcomes.single().push)
        assertFalse(harness.outcomes.single().saved)
    }

    @Test fun instantTurnedOffSucceeds() {
        val harness = Harness(pushOn = true)
        harness.local.enabled = true
        harness.run(null, choice(on = true, instant = false))
        assertEquals(listOf(false), harness.push.calls)
        harness.push.answer!!(PushRegistration.Result.OFF)
        assertTrue(harness.outcomes.single().saved)
    }

    @Test fun instantIsNeverTouchedWhereItIsNotOffered() {
        val harness = Harness(offered = false)
        harness.run(null, choice(on = true, instant = true))
        assertTrue(harness.push.calls.isEmpty())
        assertTrue(harness.outcomes.single().saved)
    }

    @Test fun bothWritesAnswerOnceTogether() {
        val harness = Harness()
        harness.run(ha(emptyList()), choice(NotificationsSave.HomeAssistantChoice(listOf(n6.service), three), on = true, instant = true))
        harness.push.answer!!(PushRegistration.Result.ON)
        assertTrue(harness.outcomes.isEmpty())
        harness.haAnswer!!(null)
        assertTrue(harness.outcomes.single().saved)
    }
}
