package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.ha.client.WebhookGone

/**
 * When a charger profile's webhook was found gone ([markedAt]) and when it was last asked anyway
 * ([checkedAt]), both in epoch milliseconds.
 */
data class PairAgainMark(val markedAt: Long, val checkedAt: Long)

/**
 * A charger whose webhook SpotNav in Home Assistant no longer recognises: what a read's outcome does
 * to the mark, and how often a marked webhook is still asked. Nothing here touches the network.
 */
internal object PairAgain {
    /**
     * How often a marked webhook is still asked, by anything in the app (the charging screen, the
     * widget, the notification check): a webhook that came back (a restored backup) is noticed,
     * without asking Home Assistant every minute for something it does not have.
     */
    const val SLOW_CHECK_MS = 15 * 60 * 1_000L

    /** Whether [profile]'s webhook may be asked at [now]. */
    fun mayRead(profile: ChargerProfile, now: Long): Boolean = mayRead(profile.pairAgain, now)

    /** Whether a webhook with [mark] (a charger's or a home's) may be asked at [now]. */
    fun mayRead(mark: PairAgainMark?, now: Long): Boolean {
        if (mark == null) return true
        return now - mark.checkedAt >= SLOW_CHECK_MS || now < mark.checkedAt
    }

    /**
     * [profile] after one read of its webhook at [now] that ended in [failure] (`null`: it
     * answered): an answer clears the mark, a confirmed gone webhook sets it (keeping when it was
     * first found), and any other failure -- offline, an error Home Assistant worded -- changes
     * nothing but when a marked webhook was last asked.
     */
    fun afterRead(profile: ChargerProfile, failure: Throwable?, now: Long): ChargerProfile {
        val mark = markAfter(profile.pairAgain, failure, now)
        return if (mark == profile.pairAgain) profile else profile.copy(pairAgain = mark)
    }

    /** A webhook's [mark] (a charger's or a home's) after one read at [now] that ended in [failure]; see [afterRead]. */
    fun markAfter(mark: PairAgainMark?, failure: Throwable?, now: Long): PairAgainMark? = when {
        failure == null -> null
        WebhookGone.isGone(failure) -> PairAgainMark(markedAt = mark?.markedAt ?: now, checkedAt = now)
        else -> mark?.copy(checkedAt = now)
    }
}
