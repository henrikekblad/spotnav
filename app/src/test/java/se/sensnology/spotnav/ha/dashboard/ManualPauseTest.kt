package se.sensnology.spotnav.ha.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.CommandRefusal
import se.sensnology.spotnav.ha.client.WebhookHttpStatusException
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import java.time.Instant
import java.time.ZoneId

/** Home Assistant 1.11: a person's Start or Stop pauses Auto for the plug-in session. */
class ManualPauseTest {
    private val now = Instant.parse("2026-09-22T06:00:00Z")
    private fun format(language: String) = StatusFormat(language, ZoneId.of("Europe/Stockholm"), "SEK", "kr")
    private fun dashboard(name: String) = Dashboard.parse(HaFixtures.json("dashboard/$name.json"))
    private fun paused(action: String?, ends: String?, choice: String? = "manual") =
        StatusLine("paused", mapOf("until" to null, "choice" to choice, "action" to action, "ends" to ends))
    private fun say(line: StatusLine, language: String) = HaStatusText.line(line, format(language), now)

    @Test fun theManualPauseFixturesDecodeWithTheirActionAndWhatEndsThem() {
        val stop = dashboard("manual_stop")
        val line = stop.status.lines.single()
        assertEquals("paused", line.code)
        assertEquals("manual", line.params["choice"])
        assertEquals("stop", line.params["action"])
        assertEquals("resume", line.params["ends"])
        assertEquals(StatusTone.NORMAL, stop.status.tone)
        // Resume Auto is offered as for any pause, and nothing is offered to pause with.
        assertEquals(PlannerControl.RESUME, stop.control!!.plannerControl())
        assertTrue(stop.control!!.pauseChoices.isEmpty())

        val start = dashboard("action_pending").status.lines.single()
        assertEquals("start", start.params["action"])
        assertEquals("resume", start.params["ends"])
    }

    @Test fun anotherPauseCarriesNoActionAndReadsAsBefore() {
        val line = dashboard("resume_active").status.lines.single()
        assertEquals("until_resumed", line.params["choice"])
        assertNull(line.params["action"])
        assertNull(line.params["ends"])
        assertEquals("Paused until you resume.", say(line, "en"))
        assertEquals("Pausad tills du återupptar.", say(line, "sv"))
    }

    @Test fun theFixturesAreWordedAsTheCardWordsThem() {
        assertEquals(
            "Stopped manually – until you resume automatic charging.",
            HaStatusText.render(dashboard("manual_stop").status, format("en"), now)
        )
        assertEquals(
            "Laddar manuellt – tills bilen är full eller du återupptar automatisk laddning.",
            HaStatusText.render(dashboard("action_pending").status, format("sv"), now)
        )
    }

    @Test fun eachManualStateIsWordedInSwedishAndEnglish() {
        val expected = listOf(
            Triple("stop", "unplug", "Stoppad manuellt – tills bilen kopplas ur." to "Stopped manually – until the car is unplugged."),
            Triple("start", "unplug", "Laddar manuellt – tills bilen är full eller kopplas ur." to "Charging manually – until the car is full or unplugged."),
            Triple("stop", "next_plug_in", "Stoppad manuellt – till och med nästa inkoppling." to "Stopped manually – until the next plug-in ends."),
            Triple("stop", "resume", "Stoppad manuellt – tills du återupptar automatisk laddning." to "Stopped manually – until you resume automatic charging."),
            Triple("start", "resume", "Laddar manuellt – tills bilen är full eller du återupptar automatisk laddning." to "Charging manually – until the car is full or you resume automatic charging."),
        )
        for ((action, ends, words) in expected) {
            assertEquals("sv $action/$ends", words.first, say(paused(action, ends), "sv"))
            assertEquals("en $action/$ends", words.second, say(paused(action, ends), "en"))
        }
    }

    @Test fun everyManualStateIsWordedInEveryLanguageWithoutAClock() {
        for (language in HaStatusWording.LANGUAGES) {
            val seen = mutableSetOf<String>()
            for (action in listOf("start", "stop")) {
                for (ends in listOf("unplug", "next_plug_in", "resume")) {
                    val text = say(paused(action, ends), language)
                    assertFalse("$language $action/$ends", text.isBlank() || text.contains('{'))
                    seen += text
                }
            }
            // Five sentences: a Start never ends with the next plug-in, so it reads as its unplug.
            assertEquals(language, 5, seen.size)
        }
        // A manual pause that names a time would still not be worded as a clock.
        val withTime = StatusLine("paused", mapOf("until" to "2026-09-22T09:00:00+00:00", "choice" to "manual", "action" to "stop", "ends" to "unplug"))
        assertEquals("Stopped manually – until the car is unplugged.", say(withTime, "en"))
    }

    @Test fun theChargerThatIgnoresAStopIsABlockingLineInEveryLanguage() {
        assertEquals(emptyList<String>() to "blocking", HaFixtures.statusCodes().getValue("charger_ignores_stop"))
        val line = StatusLine("charger_ignores_stop", emptyMap())
        assertEquals(
            "The charger keeps charging although it was stopped, so SpotNav sends no more stops. Stop it at the charger or unplug the car.",
            say(line, "en")
        )
        assertEquals(
            "Laddaren fortsätter ladda fast den stoppades, så SpotNav skickar inga fler stopp. Stoppa den vid laddaren eller koppla ur bilen.",
            say(line, "sv")
        )
        for (language in HaStatusWording.LANGUAGES) {
            assertFalse(language, say(line, language) == HaStatusWording.text(language, HaStatusWording.UNKNOWN_CODE))
        }
    }

    @Test fun manualIsNeverAPauseChoice() {
        val listed = AutoControl.of("start", null, "pause", null, listOf("manual", "next_period", "until_resumed"))!!
        assertEquals(listOf("next_period", "until_resumed"), listed.pauseChoices)
        assertEquals(PlannerControl.PAUSE, listed.plannerControl())
        val only = AutoControl.of("start", null, "pause", null, listOf("manual"))!!
        assertTrue(only.pauseChoices.isEmpty())
        assertNull(only.plannerControl())
        // A choice this build does not know still makes the axis unreadable, as before.
        assertNull(AutoControl.of("none", "no_settings", "pause", null, listOf("until_sunset"))?.automaticAction)
        for (file in HaFixtures.files("dashboard")) {
            val control = Dashboard.parse(HaFixtures.json("dashboard/${file.name}")).control ?: continue
            assertFalse(file.name, AutoControl.PAUSE_MANUAL in control.pauseChoices)
        }
    }

    @Test fun aStartWithNoCarIsReadFromTheRefusal() {
        val refused = WebhookHttpStatusException(409, """{"ok":false,"error":"vehicle_not_connected"}""")
        assertEquals(CommandRefusal.VEHICLE_NOT_CONNECTED, CommandRefusal.code(refused))
        assertEquals("pause_unsettled", CommandRefusal.code(WebhookHttpStatusException(409, """{"ok":false,"error":"pause_unsettled"}""")))
        assertNull(CommandRefusal.code(WebhookHttpStatusException(502, "Bad gateway")))
        assertNull(CommandRefusal.code(IllegalStateException("offline")))
        assertNull(CommandRefusal.code(null))
    }

    @Test fun aConflictInWhichOnlyTheRevisionMovedIsRetriedQuietly() {
        val base = SettingsFixtures.parsed(revision = 12, amps = 16)
        // A Start or Stop stored meanwhile: the same settings at a newer revision.
        assertTrue(HaSettingsEditor.onlyRevisionMoved(base, SettingsUpdate.Outcome.Conflict(base.copy(revision = 13))))
        // Someone else changed a setting: said as before, never sent over it.
        assertFalse(HaSettingsEditor.onlyRevisionMoved(base, SettingsUpdate.Outcome.Conflict(base.copy(revision = 13, amps = 10))))
        assertFalse(HaSettingsEditor.onlyRevisionMoved(base, SettingsUpdate.Outcome.Conflict(base)))
        assertFalse(HaSettingsEditor.onlyRevisionMoved(base, SettingsUpdate.Outcome.Updated(base.copy(revision = 13))))
    }
}
