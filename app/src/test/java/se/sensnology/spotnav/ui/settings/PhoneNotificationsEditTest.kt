package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.client.PhoneNotifications
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall.Chargers
import se.sensnology.spotnav.testing.HaFixtures
import java.io.File

/** The Notifications box for this phone, decided as the card's phone editor decides it. */
class PhoneNotificationsEditTest {
    private val read = (PhoneNotificationsCall.answer(200, HaFixtures.json("webhook/phone_notifications_success.json").toString())
        as PhoneNotificationsCall.Outcome.Read).value
    private val chargerEvents = listOf(
        "plan_stopped", "plan_at_risk", "charge_complete", "charge_started", "plugged_in", "unplugged", "plan_installed", "vehicle_identify"
    )

    private fun loads(chargers: Int = 1, pool: Boolean = false, spa: Boolean = false, sauna: Boolean = false) =
        PhoneNotifications.Loads(chargers, pool, spa, sauna)

    private fun twoChargers(chargers: List<String>? = null) = read.copy(
        chargers = chargers,
        installationChargers = listOf(PhoneNotifications.Charger("entry_a", "Garage"), PhoneNotifications.Charger("entry_b", "Carport")),
        loads = loads(chargers = 2, pool = true)
    )

    @Test
    fun theGroupsAreTheInstallationsLoads() {
        assertEquals(
            listOf(PhoneNotificationsEdit.EventGroup(PhoneNotificationsEdit.Group.CHARGING, chargerEvents)),
            PhoneNotificationsEdit.groups(loads())
        )
        assertEquals(
            listOf(PhoneNotificationsEdit.Group.CHARGING, PhoneNotificationsEdit.Group.POOL),
            PhoneNotificationsEdit.groups(loads(pool = true, spa = true, sauna = true)).map { it.group }
        )
        // Not ready in time once: under the spa without chargers, else the sauna.
        assertEquals(
            listOf(PhoneNotificationsEdit.EventGroup(PhoneNotificationsEdit.Group.SPA, listOf("plan_at_risk"))),
            PhoneNotificationsEdit.groups(loads(chargers = 0, spa = true, sauna = true))
        )
        assertEquals(
            listOf(PhoneNotificationsEdit.Group.POOL, PhoneNotificationsEdit.Group.SAUNA),
            PhoneNotificationsEdit.groups(loads(chargers = 0, pool = true, sauna = true)).map { it.group }
        )
        assertTrue(PhoneNotificationsEdit.atRiskHelp(loads(spa = true), "plan_at_risk"))
        assertFalse(PhoneNotificationsEdit.atRiskHelp(loads(), "plan_at_risk"))
        assertFalse(PhoneNotificationsEdit.atRiskHelp(loads(chargers = 0, spa = true), "plan_at_risk"))
        assertFalse(PhoneNotificationsEdit.atRiskHelp(loads(spa = true), "plan_stopped"))
    }

    @Test
    fun theChargersAreAskedOnlyWithMoreThanOne() {
        assertFalse(PhoneNotificationsEdit.asksChargers(read))
        assertTrue(PhoneNotificationsEdit.asksChargers(twoChargers()))
    }

    @Test
    fun aSaveWritesOnlyWhatChanged() {
        assertNull(PhoneNotificationsEdit.change(read, true, read.events, emptyList()))
        assertEquals(PhoneNotificationsCall.Change(on = false), PhoneNotificationsEdit.change(read, false, read.events, emptyList()))
        // An event not shown here (the pool's, with no pool) is kept, in Home Assistant's order.
        assertEquals(
            PhoneNotificationsCall.Change(events = listOf("plan_stopped", "plugged_in", "pool_season")),
            PhoneNotificationsEdit.change(read, true, listOf("plugged_in", "plan_stopped"), emptyList())
        )
        // One charger only, back to all as "all", none at all.
        val two = twoChargers()
        assertEquals(PhoneNotificationsCall.Change(chargers = Chargers.Only(listOf("entry_b"))), PhoneNotificationsEdit.change(two, true, two.events, listOf("entry_b")))
        assertEquals(PhoneNotificationsCall.Change(chargers = Chargers.All), PhoneNotificationsEdit.change(twoChargers(listOf("entry_a")), true, two.events, listOf("entry_a", "entry_b")))
        assertEquals(PhoneNotificationsCall.Change(chargers = Chargers.Only(emptyList())), PhoneNotificationsEdit.change(two, true, two.events, emptyList()))
        // With one charger the chargers are never written.
        assertNull(PhoneNotificationsEdit.change(read, true, read.events, emptyList()))
    }

    @Test
    fun theRowSaysOffOrHowManyEventsAreOn() {
        val sv = object : NotificationTexts {
            private val xml = listOf("src/main/res", "app/src/main/res").map { File(it, "values-sv/strings.xml") }.first { it.exists() }.readText()
            private fun name(owner: Class<*>, id: Int) = owner.fields.first { it.getInt(null) == id }.name
            override fun text(id: Int, vararg args: Any) =
                Regex("<string name=\"${name(R.string::class.java, id)}\">(.*?)</string>").find(xml)!!.groupValues[1]
            override fun quantity(id: Int, count: Int, vararg args: Any): String {
                val block = xml.substringAfter("<plurals name=\"${name(R.plurals::class.java, id)}\">").substringBefore("</plurals>")
                val form = if (count == 1) "one" else "other"
                return String.format(Regex("<item quantity=\"$form\">(.*?)</item>").find(block)!!.groupValues[1], *args)
            }
        }
        assertEquals("Av", NotificationsOverview.phone(read.copy(on = false), instant = false, texts = sv))
        assertEquals("Var 15:e minut · 4 av 8 händelser", NotificationsOverview.phone(read, instant = false, texts = sv))
        assertEquals("Direkt · 5 av 9 händelser", NotificationsOverview.phone(read.copy(loads = loads(pool = true)), instant = true, texts = sv))
    }
}
