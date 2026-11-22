package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.dashboard.DoesNotFit
import se.sensnology.spotnav.ha.house.House
import java.time.Instant
import java.time.ZoneId

/**
 * The "does not fit" line and its buttons' words, as the card's `does-not-fit.ts` says them, for the charger page's
 * status area and the Plan box's car lane. Pure: [zone] is the price area's (the clock the departure is said in;
 * without one the line names no time), [money] the units a cost is given in.
 */
internal class DoesNotFitText(private val text: SiteText, private val zone: ZoneId?, private val money: House.Money?) {
    private val words get() = text.words

    /** A power as the card's `kwText` writes it: one decimal ("6,0 kW"). */
    private fun kw(value: Double): String = "${text.fixed(value, 1)} kW"

    private fun time(at: Instant?): String? = if (at == null || zone == null) null else text.clock(at, zone)

    /** The ceiling in words ("Effektmål 6,0 kW"); a ceiling of another kind by its kW alone. */
    fun ceiling(block: DoesNotFit): String =
        if (block.ceiling in CEILINGS) words.say("house.plan.ceiling.${block.ceiling}", "kw" to kw(block.ceilingKw)) else kw(block.ceilingKw)

    /**
     * The line: what does not fit and what still fits ("Ryms inte under taket (Effektmål 6,0 kW): 16,2 kWh till 14:30 –
     * 7,1 kWh hinner."), or what was chosen ("Bilen tar det som ryms till 14:30 (7,1 kWh).").
     */
    fun line(block: DoesNotFit): String {
        val time = time(block.departure)
        if (block.chosen == DoesNotFit.ACCEPT_PEAK) {
            val peak = block.choices.firstOrNull { it.code == DoesNotFit.ACCEPT_PEAK }?.peakKw
            val base = if (time == null) words.say("doesNotFit.chosen.acceptPeakUnplug", "ceiling" to ceiling(block))
            else words.say("doesNotFit.chosen.acceptPeak", "ceiling" to ceiling(block), "time" to time)
            return if (peak == null) base else "$base ${words.say("doesNotFit.peakNote", "kw" to text.fixed(peak, 0))}"
        }
        val fits = block.deliverableKwh
        if (block.chosen == DoesNotFit.TAKES_WHAT_FITS) {
            return if (time == null || fits == null) words.say("doesNotFit.chosen.takesWhatFitsNoTime")
            else words.say("doesNotFit.chosen.takesWhatFits", "time" to time, "kwh" to text.kwh(fits))
        }
        val short = block.shortKwh ?: 0.0
        return when {
            time == null -> words.say("doesNotFit.shortNoTime", "ceiling" to ceiling(block), "kwh" to text.kwh(short))
            fits == null -> words.say("doesNotFit.short", "ceiling" to ceiling(block), "kwh" to text.kwh(short), "time" to time)
            else -> words.say(
                "doesNotFit.line", "ceiling" to ceiling(block), "need" to text.kwh(fits + short), "time" to time, "fits" to text.kwh(fits)
            )
        }
    }

    /** A choice's button ("Ta det som ryms (≈ 7,1 kWh)", "Senare avresa (17:00)", "Tillåt toppen (≈ 12 kW, ≈ 105 kr)"). */
    fun choice(choice: DoesNotFit.Choice): String = when (choice.code) {
        DoesNotFit.TAKES_WHAT_FITS -> choice.kwh?.let { words.say("doesNotFit.choice.takesWhatFitsKwh", "kwh" to text.kwh(it)) }
            ?: words.say("doesNotFit.choice.takesWhatFits")
        DoesNotFit.LATER -> time(choice.departure)?.let { words.say("doesNotFit.choice.laterAt", "time" to it) }
            ?: words.say("doesNotFit.choice.later")
        else -> {
            val peak = choice.peakKw
            val cost = choice.cost
            when {
                peak == null -> words.say("doesNotFit.choice.acceptPeak")
                cost == null -> words.say("doesNotFit.choice.acceptPeakKw", "kw" to text.fixed(peak, 0))
                else -> words.say("doesNotFit.choice.acceptPeakCost", "kw" to text.fixed(peak, 0), "cost" to text.amount(cost, money))
            }
        }
    }

    /** What pressing a choice does. */
    enum class Press {
        /** `take_what_fits`. */
        TAKE_WHAT_FITS,
        /** `later_departure`: "Later departure" with its time. */
        LATER_DEPARTURE,
        /** "Later departure" without a time that fits: the departure's editor, as today. */
        OPEN_DEPARTURE,
        /** `release_import_target`, confirmed first (it has no undo). */
        ACCEPT_PEAK
    }

    data class Button(val code: String, val label: String, val press: Press)

    /**
     * The buttons under the line, one per choice still open, in Home Assistant's order (the card's `doesNotFitNode`).
     * "Allow the peak" only where the release can be confirmed ([releaseOffered]: the dashboard offers the release and
     * says what it would mean), as the card asks.
     */
    fun buttons(block: DoesNotFit, releaseOffered: Boolean): List<Button> = block.choices.mapNotNull { choice ->
        val press = when (choice.code) {
            DoesNotFit.TAKES_WHAT_FITS -> Press.TAKE_WHAT_FITS
            DoesNotFit.LATER -> if (choice.moves) Press.LATER_DEPARTURE else Press.OPEN_DEPARTURE
            DoesNotFit.ACCEPT_PEAK -> if (releaseOffered) Press.ACCEPT_PEAK else return@mapNotNull null
            else -> return@mapNotNull null
        }
        Button(choice.code, choice(choice), press)
    }

    /** "Yes, allow the peak": the second press "Allow the peak" asks for, as the card does. */
    fun acceptPeakAgain(): String = words.say("doesNotFit.choice.acceptPeakAgain")

    private companion object {
        val CEILINGS = setOf("import_target", "power_level", "power_tariff", "fuse")
    }
}
