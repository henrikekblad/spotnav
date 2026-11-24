package se.sensnology.spotnav.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.HeatLoads
import java.time.Instant
import java.time.ZoneId

class PoolSeasonNoticeTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val day = Instant.parse("2026-09-20T10:00:00Z")
    private val night = Instant.parse("2026-09-20T21:30:00Z")

    private fun pool(open: Boolean = true, hint: Boolean = true) = HeatLoads.Pool(
        seasonOpen = open, waterC = 10.0, waterSource = "sensor", waterAt = null, targetC = 26.0,
        heatPump = null, circulation = null,
        today = HeatLoads.PoolToday(needH = null, needSource = null, doneH = 0.0, plannedH = 0.0, heatH = null, kwh = null),
        planned = emptyList(), extra = null, seasonHint = hint, would = null
    )

    @Test
    fun theHintIsToldOnceInTheDaytime() {
        assertEquals(PoolSeasonNotice.Step.POST, PoolSeasonNotice.step(pool(), chosen = true, posted = false, now = day, zone = zone))
        assertEquals(PoolSeasonNotice.Step.KEEP, PoolSeasonNotice.step(pool(), chosen = true, posted = true, now = day, zone = zone))
        assertEquals(PoolSeasonNotice.Step.WAIT, PoolSeasonNotice.step(pool(), chosen = true, posted = false, now = night, zone = zone))
    }

    @Test
    fun itGoesWhenTheHintDoesOrThePhoneNoLongerWantsIt() {
        assertEquals(PoolSeasonNotice.Step.RETIRE, PoolSeasonNotice.step(pool(hint = false), chosen = true, posted = true, now = day, zone = zone))
        assertEquals(PoolSeasonNotice.Step.RETIRE, PoolSeasonNotice.step(pool(open = false), chosen = true, posted = true, now = day, zone = zone))
        assertEquals(PoolSeasonNotice.Step.RETIRE, PoolSeasonNotice.step(pool(), chosen = false, posted = true, now = day, zone = zone))
        assertEquals(PoolSeasonNotice.Step.KEEP, PoolSeasonNotice.step(null, chosen = true, posted = false, now = day, zone = zone))
    }

    @Test
    fun theAppSaysItShowsThePoolSeason() {
        assertTrue("pool_season" in PhoneNotificationsSync.SHOWS)
    }
}
