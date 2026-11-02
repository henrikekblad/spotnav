package se.sensnology.spotnav.ha.dashboard

/**
 * The button of a status line that says the charger itself works against SpotNav (the card's `ownModeFix`): the
 * `charger_own_mode` line's one safe value written back ("Set to Default", "Turn off"), or the `charger_disabled` line's
 * enable switch turned on. Offered only where the dashboard states `capabilities.fix_own_mode`, and never for a line
 * that names no single safe value. What is sent is always the raw value.
 */
internal data class OwnModeAction(val entityId: String, val value: String, val labelKey: String, val labelValue: String?) {
    /** The button's words in [language] (the card's own), or `null` for a locale table without them. */
    fun label(language: String): String? = HaStatusWording.text(language, labelKey)?.let { text ->
        if (labelValue == null) text else text.replace("{value}", labelValue)
    }

    companion object {
        fun of(dashboard: Dashboard?): OwnModeAction? {
            if (dashboard == null || !dashboard.capabilities.fixOwnMode) return null
            for (line in dashboard.status.lines) {
                val p = line.params
                val entity = (p["entity"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                when (line.code) {
                    "charger_own_mode" -> {
                        val value = (p["value"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                        return if (entity.startsWith("switch.") && value == "off") {
                            OwnModeAction(entity, value, "issue.ownMode.turnOff", null)
                        } else {
                            val shown = (p["value_name"] as? String)?.takeIf { it.isNotBlank() } ?: value
                            OwnModeAction(entity, value, "issue.ownMode.set", shown)
                        }
                    }
                    "charger_disabled" -> return OwnModeAction(entity, "on", "issue.chargerDisabled.turnOn", null)
                }
            }
            return null
        }
    }
}
