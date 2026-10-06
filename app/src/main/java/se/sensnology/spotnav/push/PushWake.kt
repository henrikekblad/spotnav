package se.sensnology.spotnav.push

/**
 * What one push message asks for. The relay's messages carry no content, only `t`: `wake` (an event
 * this phone chose fired: check the charger now) or `test` (a test from Home Assistant: say that
 * instant notifications work, then check too).
 */
internal object PushWake {
    enum class Action { NONE, CHECK, TEST_AND_CHECK }

    fun action(data: Map<String, String>, pushEnabled: Boolean): Action {
        if (!pushEnabled) return Action.NONE
        return when (data["t"]) {
            "wake" -> Action.CHECK
            "test" -> Action.TEST_AND_CHECK
            else -> Action.NONE
        }
    }
}

/**
 * The screen in view that wants to hear of a wake-up, so it reads its charger at once instead of at
 * its next timed read. A message names no charger, so any wake counts. Set while the charging screen
 * is in view, cleared when it leaves; the listener is called on the messaging thread and posts to its
 * own.
 */
internal object PushWakeListener {
    @Volatile private var listener: (() -> Unit)? = null

    fun set(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Tell the listener, if any, when [action] asks for a check. */
    fun deliver(action: PushWake.Action) {
        if (action == PushWake.Action.NONE) return
        listener?.invoke()
    }
}
