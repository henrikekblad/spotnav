package se.sensnology.spotnav.prices

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one process-local fact a widget publication refresh emits, and one screen's guarded reaction
 * to it.
 */

/**
 * What one publication refresh did for one widget: which area it loaded, and whether that area's
 * accepted documents actually moved.
 */
internal data class PublicationOutcome(val areaId: String, val accepted: Boolean)

/** The areas one publication operation must announce, and the only place that decides it. */
internal object PricePublicationBatch {
    /** The areas to announce, once each, for the outcomes one publication operation produced. */
    fun areas(outcomes: List<PublicationOutcome>): List<String> {
        val announced = LinkedHashSet<String>()
        for (outcome in outcomes) {
            if (outcome.accepted) announced.add(outcome.areaId)
        }
        return announced.toList()
    }

    /** Announce them, and hand back what was announced. */
    fun announce(outcomes: List<PublicationOutcome>, registry: PricePublicationRegistry): List<String> {
        val areas = areas(outcomes)
        for (areaId in areas) registry.publish(areaId)
        return areas
    }
}

/** One publication operation, as a rule instead of a callback. */
internal fun <T> runPublicationBatch(
    widgets: List<T>,
    visit: (T, MutableList<PublicationOutcome>) -> Unit,
    finish: () -> Unit,
    announce: (List<PublicationOutcome>) -> List<String>
): List<String> {
    val outcomes = ArrayList<PublicationOutcome>()
    var operationFailure: Throwable? = null
    for (widget in widgets) {
        // One widget's failure is that widget's. The widget after a broken one has its own area,
        // its own result and its own widget to draw, so the batch goes on and its outcome is
        // announced too.
        try {
            visit(widget, outcomes)
        } catch (error: Throwable) {
            val first = operationFailure
            if (first == null) operationFailure = error else first.addSuppressed(error)
        }
    }
    val announced = try {
        announce(outcomes)
    } catch (listenerFailure: Throwable) {
        val original = operationFailure
        if (original == null) operationFailure = listenerFailure else original.addSuppressed(listenerFailure)
        emptyList()
    } finally {
        finish()
    }
    operationFailure?.let { throw it }
    return announced
}

/** The process-local listeners, and the only way to reach them. */
internal class PricePublicationRegistry {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun register(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /** Announce one area's accepted prices. */
    fun publish(areaId: String) {
        var failure: Throwable? = null
        for (listener in listeners) {
            // One faulty screen must not silence the others.
            try {
                listener(areaId)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    /** How many listeners are attached: what a leak test asserts about. */
    fun size(): Int = listeners.size
}

/** The one process-wide registry: the widget publishes into it, screens listen to it. */
internal object PricePublications {
    val process = PricePublicationRegistry()
}

/** One screen's guarded reaction to that event, and to its own resume. */
internal class PriceScreenRefresh(
    /** The screen's own price-load path: the existing one, reused rather than reimplemented. */
    private val reload: () -> Unit,
    /** The area this screen is currently priced from, or `null` when it has no subject at all. */
    private val currentArea: () -> String?,
    /** Whether the charging screen is on display: set by the screen's own build, cleared by teardown. */
    private val built: () -> Boolean
) {
    private var resumedOnce = false
    private var inFlight = false
    private var pending = false

    /** A publication for [areaId] was accepted: reload only if this screen is showing that market. */
    fun published(areaId: String) {
        if (!built()) return
        if (currentArea() != areaId) return
        refresh()
    }

    /** The activity resumed: the first one follows creation, every later one may have missed an event. */
    fun resumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (!built()) return
        refresh()
    }

    /** The screen's own load started. */
    fun loadStarted() {
        inFlight = true
    }

    /** The screen's own load finished: applied, dropped as late, or refused. */
    fun loadFinished() {
        inFlight = false
        if (!pending) return
        pending = false
        start()
    }

    /**
     * One trigger: a load now if none is running, and otherwise the one follow-up read that is
     * owed.
     */
    private fun refresh() {
        if (inFlight) {
            pending = true
            return
        }
        start()
    }

    /** Start one load, marking it in flight **before** the callback runs. */
    private fun start() {
        inFlight = true
        reload()
    }
}
