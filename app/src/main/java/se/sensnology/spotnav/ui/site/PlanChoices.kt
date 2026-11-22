package se.sensnology.spotnav.ui.site

/**
 * Where the Plan box's "does not fit" choices for a car's lane are sent. The lane's id is its charger's entry id.
 * - [Home]: the home's own webhook (a home without a charger page): every car lane, naming its charger (`charger_id`).
 * - [Charger]: a charger page's session. Behind the home's webhook ([behindHome]) every car lane, naming its charger;
 *   on the charger's own webhook only this charger's lane ([ownId]), which that webhook answers without `charger_id`.
 */
internal sealed interface PlanChoiceScope {
    data object Home : PlanChoiceScope

    data class Charger(val ownId: String?, val behindHome: Boolean) : PlanChoiceScope

    /** The choices' address: [chargerId] to name (`null`: the session's own); a lane with none has no buttons. */
    data class Target(val chargerId: String?)

    companion object {
        fun target(scope: PlanChoiceScope, laneId: String): Target? = when (scope) {
            Home -> Target(laneId)
            is Charger -> when {
                scope.behindHome -> Target(laneId)
                scope.ownId != null && scope.ownId == laneId -> Target(null)
                else -> null
            }
        }
    }
}

/**
 * When the Plan box asks a webhook that answered "Unsupported action" (Home Assistant before 3.0.0b3) again: when the
 * page comes into view again, after [RETRY_MS] at the most, or when the webhook's capabilities change (the page memory's
 * refusal is then cleared). Never before, so an older Home Assistant is not asked every minute; never only after a
 * restart, so an update of Home Assistant shows the box without one.
 */
internal object PlanProbe {
    const val RETRY_MS = 15 * 60 * 1000L

    /** Whether to read now: [refusedAt] the elapsed time of the last "Unsupported action" (`null`: none). */
    fun ask(offered: Boolean?, refusedAt: Long?, now: Long, cameIntoView: Boolean): Boolean = when {
        offered == false -> false
        refusedAt == null -> true
        else -> cameIntoView || now - refusedAt >= RETRY_MS
    }
}
