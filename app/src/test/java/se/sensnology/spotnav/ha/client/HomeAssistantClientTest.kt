package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.vehicles.ChargeLimit
import java.net.InetAddress
import java.net.ServerSocket

class HomeAssistantClientTest {
    @Test fun allowsHttpsAndPrivateLocalHttpOnly() {
        assertTrue(HomeAssistantSettings.isAllowedBaseUrl("https://ha.example.com"))
        assertTrue(HomeAssistantSettings.isAllowedBaseUrl("http://192.168.1.20:8123"))
        assertTrue(HomeAssistantSettings.isAllowedBaseUrl("http://homeassistant.local:8123"))
        assertTrue(HomeAssistantSettings.isAllowedBaseUrl("http://ha-server:8123"))
        assertFalse(HomeAssistantSettings.isAllowedBaseUrl("http://ha.example.com"))
        assertFalse(HomeAssistantSettings.isAllowedBaseUrl("ftp://192.168.1.20"))
    }

    @Test fun buildsWebhookUrlWithoutDuplicateSlash() {
        val settings = HomeAssistantSettings("https://ha.example/", "secret-id")
        assertEquals("https://ha.example/api/webhook/secret-id", settings.webhookUrl())
    }

    @Test fun theRetiredActionsCannotBeSent() {
        // The dashboard is the only read; `status`, `schedule` and `cancel` are not actions this
        // client can send, and refusing them happens before anything touches the network.
        val settings = HomeAssistantSettings("https://ha.example", "hook")
        for (action in listOf("status", "schedule", "cancel")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                HomeAssistantClient.send(settings, HomeAssistantCommand(action))
            }
        }
    }

    @Test fun theDashboardRequestStatesItsVersionAndNoNegotiation() {
        val read = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand(action = "dashboard")))
        assertEquals(setOf("version", "reads", "action", "api_version"), read.keys().asSequence().toSet())
        assertEquals(1, read.getInt("api_version"))

        // A settings write carries the revision and the body and no version of its own.
        val write = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand(
                    action = "settings",
                    expectedRevision = 4,
                    settingsReplacement = SettingsFixtures.parsed(revision = 4)
                )
            )
        )
        assertFalse(write.has("api_version"))
        assertFalse(write.has("settings_api_version"))

        // No other action carries an `api_version`: the dashboard is the one read.
        val refresh = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("refresh_vehicle", vehicleId = "v1")))
        assertFalse(refresh.has("api_version"))
    }

    @Test fun theDashboardIsReadFromTheWebhookAndDecoded() {
        val (dashboard, sent) = withServer("200 OK", HaFixtures.root.resolve("webhook/dashboard.json").readText()) {
            HomeAssistantClient.dashboard(it)
        }
        assertEquals("dashboard", sent.getString("action"))
        assertEquals(1, sent.getInt("api_version"))
        assertEquals(1, dashboard.settings!!.revision)
        assertEquals("entry_dash", dashboard.chargerId)
    }

    @Test fun thePayloadCarriesAPercentOnlyForTheActionThatHasOne() {
        val set = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand(action = "set_charge_limit", vehicleId = "device-1", percent = 80.0)
            )
        )
        assertEquals(80.0, set.getDouble("percent"), 0.0)
        // The vehicle travels with it, exactly as it does for `refresh_vehicle`.
        assertEquals("device-1", set.getString("vehicle_id"))

        // No value asked for, no value written.
        val withoutValue = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand(action = "set_charge_limit", vehicleId = "device-1")
            )
        )
        assertFalse(withoutValue.has("percent"))

        // And a percent on any other action is never written, because no other action has one.
        val onAStart = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand(action = "start", amps = 16, vehicleId = "device-1", percent = 80.0)
            )
        )
        assertFalse(onAStart.has("percent"))
        // A vehicle names a subject only for the two actions that have one.
        assertFalse(onAStart.has("vehicle_id"))
    }

    @Test fun theSetChargeLimitAnswerIsReadFromTheStatusAndTheBody() {
        // The transport's mapping, as one pure decision.
        assertEquals(
            ChargeLimit.Answer.Set,
            ChargeLimit.answer(200, """{"ok": true, "action": "set_charge_limit"}""")
        )
        assertEquals(
            ChargeLimit.Answer.TooSoon(60),
            ChargeLimit.answer(429, """{"ok": false, "retry_after_s": 60}""")
        )
        // A 429 whose body carries no readable number of seconds is still a refusal, with a
        // duration this app cannot name.
        assertEquals(ChargeLimit.Answer.TooSoon(null), ChargeLimit.answer(429, """{"ok": false}"""))
        assertEquals(ChargeLimit.Answer.TooSoon(null), ChargeLimit.answer(429, null))

        // A 200 that does not really say ok is not a success, and the refusals the integration uses
        // for an unknown vehicle or a value the limit will not accept are one failure here -- the
        // user can do nothing different about any of them.
        assertEquals(ChargeLimit.Answer.Failed, ChargeLimit.answer(200, """{"ok": "true"}"""))
        assertEquals(
            ChargeLimit.Answer.Failed,
            ChargeLimit.answer(400, """{"ok": false, "error": "Unknown vehicle"}""")
        )
        assertEquals(ChargeLimit.Answer.Failed, ChargeLimit.answer(500, null))
        assertEquals(ChargeLimit.Answer.Failed, ChargeLimit.answer(null, null))
    }

    @Test fun setChargeLimitBuildsAndSendsTheRequest() {
        val bodies = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                    }
                    val length = Regex("(?i)content-length: *(\\d+)").find(head)!!.groupValues[1].toInt()
                    bodies.add(String(input.readNBytes(length), Charsets.UTF_8))
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()
                    )
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val settings = HomeAssistantSettings("http://127.0.0.1:${server.localPort}", "hook")
            HomeAssistantClient.setChargeLimit(settings, "device-1", 80)
            val sent = bodies.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("the request was never sent", sent != null)
            val json = JSONObject(sent!!)
            assertEquals("set_charge_limit", json.getString("action"))
            assertEquals("device-1", json.getString("vehicle_id"))
            assertEquals(80.0, json.getDouble("percent"), 0.0)
        } finally {
            server.close()
        }
    }

    /** One request answered with [status] and [body]; hands back what the phone sent. */
    private fun <T> withServer(status: String, body: String, call: (HomeAssistantSettings) -> T): Pair<T, JSONObject> {
        val bodies = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                    }
                    val length = Regex("(?i)content-length: *(\\d+)").find(head)!!.groupValues[1].toInt()
                    bodies.add(String(input.readNBytes(length), Charsets.UTF_8))
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray() + bytes
                    )
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val result = call(HomeAssistantSettings("http://127.0.0.1:${server.localPort}", "hook"))
            val sent = bodies.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("the request was never sent", sent != null)
            return result to JSONObject(sent!!)
        } finally {
            server.close()
        }
    }

    private fun webhookFixture(name: String) = HaFixtures.root.resolve("webhook/$name.json").readText()

    @Test fun updateVehicleSendsTheRequestAndReadsAConflictBodyFromA409() {
        val (outcome, sent) = withServer("409 Conflict", webhookFixture("update_vehicle_conflict")) {
            HomeAssistantClient.updateVehicle(it, "vehicle_niro", listOf(VehicleUpdate.FieldChange(VehicleField.CAPACITY, 70.0, 64.8)))
        }
        assertEquals("update_vehicle", sent.getString("action"))
        assertEquals(70.0, sent.getJSONObject("changes").getDouble("capacity_kwh"), 0.0)
        assertEquals(64.8, sent.getJSONObject("expected").getDouble("capacity_kwh"), 0.0)
        assertEquals(64.8, (outcome as VehicleUpdate.Outcome.Conflict).row!!.capacityKwh!!, 0.0)
    }

    @Test fun updateSiteSettingsSendsTheRequestAndReadsThe403OfAWebhookRefusal() {
        val (outcome, sent) = withServer("403 Forbidden", webhookFixture("update_site_settings_not_permitted")) {
            HomeAssistantClient.updateSiteSettings(it, SiteUpdate.priorityRequest("car_first", "battery_first"))
        }
        assertEquals("update_site_settings", sent.getString("action"))
        assertEquals("battery_first", sent.getJSONObject("changes").getString("solar_priority"))
        assertTrue(outcome is SiteUpdate.Outcome.NotPermitted)
    }

    @Test fun aWriteWithNoAnswerIsAPlainFailureAndNeverThrows() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val settings = HomeAssistantSettings("http://127.0.0.1:$closed", "hook")
        assertEquals(VehicleUpdate.Outcome.Failed(null), HomeAssistantClient.updateVehicle(settings, "v", listOf(VehicleUpdate.FieldChange(VehicleField.CAPACITY, 1.0, null))))
        assertEquals(
            SiteUpdate.Outcome.Failed(null),
            HomeAssistantClient.updateSiteSettings(settings, SiteUpdate.priorityRequest(null, "car_first"))
        )
    }
}
