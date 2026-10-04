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
