package se.sensnology.spotnav.notify

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardIdentification
import se.sensnology.spotnav.vehicles.VehicleIdentification

/**
 * "Which car is plugged in?" as this phone's own notification, decided from one dashboard read: when a
 * check posts the question, with which buttons, when a later check takes it off, and whether a tapped
 * button still answers. Pure, so every rule is tested; [IdentifyNotifications] does the posting.
 *
 * A question is known by its key, the identification's `since` (the plug-in it belongs to), so the same
 * question is posted once however often it is read, and a button answers only the question it was posted
 * for. The buttons follow Home Assistant's `candidates`, which it orders by the cars' own reports and the
 * camera, as its Companion question does: every car up to three, else the first two and one that opens
 * the app.
 */
internal object IdentifyNotice {
    /** Android shows at most this many buttons. */
    const val MAX_BUTTONS = 3

    data class Car(val vehicleId: String, val name: String)

    data class Question(val key: String, val cars: List<Car>, val openButton: Boolean)

    sealed interface Step {
        /** Post [question], replacing an older one of this charger. */
        data class Post(val question: Question) : Step

        /** The question shown is gone (answered elsewhere, decided, unplugged, turned off): take it off. */
        data object Retire : Step

        /** The banner in view asks the same: take a posted one off and post nothing. */
        data object Suppress : Step

        /** Nothing changes. */
        data object Keep : Step
    }

    /** What a tapped car button does. */
    enum class Tap { ANSWER, GONE, FAILED }

    private fun open(dashboard: Dashboard): DashboardIdentification? =
        dashboard.identification
            ?.takeIf { VehicleIdentification.advertised(dashboard) && it.state == DashboardIdentification.State.ASKING }
            ?.takeIf { it.candidates.isNotEmpty() }

    /** A question's key: the plug-in it belongs to. */
    fun key(block: DashboardIdentification): String = block.since ?: block.state.wire

    /**
     * What one read means for the question: [chosen] is whether this phone's event is on, [posted] the key
     * of the question it shows (`null`: none), and [inApp] the key of the question the banner shows in view.
     * Only an open question (`asking`) is posted: while Home Assistant still looks at the cars' own reports
     * (`waiting`) one may settle it, and Home Assistant asks the phones only once it asks.
     */
    fun step(dashboard: Dashboard, chosen: Boolean, posted: String?, inApp: String?): Step {
        val block = open(dashboard)?.takeIf { chosen }
            ?: return if (posted != null) Step.Retire else Step.Keep
        val key = key(block)
        return when (key) {
            posted -> Step.Keep
            inApp -> Step.Suppress
            else -> {
                val cars = block.candidates.map { Car(it.vehicleId, it.name) }
                val fits = cars.size <= MAX_BUTTONS
                Step.Post(Question(key, if (fits) cars else cars.take(MAX_BUTTONS - 1), openButton = !fits))
            }
        }
    }

    /** Whether a button of the question [key] still answers, from a read just before (`null`: it failed). */
    fun tap(dashboard: Dashboard?, key: String): Tap {
        dashboard ?: return Tap.FAILED
        val block = open(dashboard) ?: return Tap.GONE
        return if (key(block) == key) Tap.ANSWER else Tap.GONE
    }
}
