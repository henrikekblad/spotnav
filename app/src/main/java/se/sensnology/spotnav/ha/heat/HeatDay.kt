package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant

/**
 * One hour of the heat plan as the day chart draws it (`band.hours` of `get_heat`): the setpoint the plan uses, what
 * it does in the hour, the minutes the pump's relay was (or is expected to be) closed, and the car and hot water.
 */
internal data class HeatBandHour(
    val start: Instant,
    val action: String,
    val targetC: Double,
    val reason: String,
    val hotWater: Boolean,
    val car: Boolean,
    /** Minutes the relay was (or is expected to be) closed by anyone; `null` without a relay. */
    val relayMin: Double?,
    val relayExpected: Boolean,
    /** Minutes SpotNav itself held the relay closed; `null` without the heat control. */
    val executedMin: Double?,
    /** Minutes SpotNav held SG Ready's raise contact closed; `null` where the pump cannot be raised. */
    val raisedMin: Double?
)

/** A day's measured rooms by the step (`indoor_day`), the comfort band, and a history day's setpoint per hour. */
internal data class HeatIndoorDay(
    val start: Instant,
    val end: Instant,
    val stepMin: Int,
    val lowC: Double,
    val highC: Double,
    val points: List<Point>,
    /** The history's setpoint per hour; `null` on the live answer, whose band hours carry it. */
    val setpoint: List<Setpoint>?,
    val rooms: Map<String, String>
) {
    data class Point(val start: Instant, val meanC: Double, val coldestC: Double, val coldest: String)

    data class Setpoint(val start: Instant, val targetC: Double, val action: String)
}

/** A day's review (`review`): what the relay really did and what SpotNav's plan would have done, with the comfort each gave. */
internal data class HeatReview(val date: String, val lowC: Double, val relay: Part, val plan: Part) {
    data class Part(val hours: Double, val value: Double, val coldestMinC: Double?, val minutesBelow: Int, val overlapHours: Double)
}

/** The blocks `get_heat` and `get_heat_history` share, read by the one rule (a key must be there and of its type). */
internal object HeatDayRead {
    fun bandHour(raw: Any?): HeatBandHour = with(StrictJson) {
        val hour = obj(raw, "band.hour")
        HeatBandHour(
            start = instant(hour.opt("start"), "band.hour.start"),
            action = text(hour.opt("action"), "band.hour.action"),
            targetC = number(hour.opt("target_c"), "band.hour.target_c"),
            reason = text(hour.opt("reason"), "band.hour.reason"),
            hotWater = flag(hour.opt("hot_water"), "band.hour.hot_water"),
            car = flag(hour.opt("car"), "band.hour.car"),
            relayMin = numberOrNull(hour.opt("relay_min"), "band.hour.relay_min"),
            relayExpected = flag(hour.opt("relay_expected"), "band.hour.relay_expected"),
            executedMin = numberOrNull(hour.opt("executed_min"), "band.hour.executed_min"),
            raisedMin = numberOrNull(hour.opt("raised_min"), "band.hour.raised_min")
        )
    }

    fun indoorDay(json: JSONObject): HeatIndoorDay = with(StrictJson) {
        HeatIndoorDay(
            start = instant(json.opt("start"), "indoor_day.start"),
            end = instant(json.opt("end"), "indoor_day.end"),
            stepMin = count(json.opt("step_min"), "indoor_day.step_min").also { if (it == 0) bad("indoor_day.step_min") },
            lowC = number(json.opt("low_c"), "indoor_day.low_c"),
            highC = number(json.opt("high_c"), "indoor_day.high_c"),
            points = list(json.opt("points"), "indoor_day.points").map {
                val point = obj(it, "indoor_day.point")
                HeatIndoorDay.Point(
                    instant(point.opt("start"), "point.start"), number(point.opt("mean_c"), "point.mean_c"),
                    number(point.opt("coldest_c"), "point.coldest_c"), text(point.opt("coldest"), "point.coldest")
                )
            },
            setpoint = json.opt("setpoint").takeIf { it !== JSONObject.NULL }?.let { raw ->
                list(raw, "indoor_day.setpoint").map {
                    val hour = obj(it, "indoor_day.setpoint.hour")
                    HeatIndoorDay.Setpoint(instant(hour.opt("start"), "setpoint.start"), number(hour.opt("target_c"), "setpoint.target_c"), text(hour.opt("action"), "setpoint.action"))
                }
            },
            rooms = list(json.opt("rooms"), "indoor_day.rooms").map { obj(it, "indoor_day.room") }
                .associate { text(it.opt("entity_id"), "room.entity_id") to text(it.opt("name"), "room.name") }
        )
    }

    fun review(json: JSONObject): HeatReview = with(StrictJson) {
        fun part(raw: Any?, what: String, overlap: Boolean): HeatReview.Part {
            val item = obj(raw, what)
            number(item.opt("saved_kwh"), "$what.saved_kwh")
            return HeatReview.Part(
                hours = number(item.opt("hours"), "$what.hours"),
                value = number(item.opt("value"), "$what.value"),
                coldestMinC = numberOrNull(item.opt("coldest_min_c"), "$what.coldest_min_c"),
                minutesBelow = count(item.opt("minutes_below"), "$what.minutes_below"),
                overlapHours = if (overlap) number(item.opt("overlap_hours"), "$what.overlap_hours") else 0.0
            )
        }
        HeatReview(
            date = text(json.opt("date"), "review.date"),
            lowC = number(json.opt("low_c"), "review.low_c"),
            relay = part(json.opt("relay"), "review.relay", false),
            plan = part(json.opt("plan"), "review.plan", true)
        )
    }
}
