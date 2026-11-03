package se.sensnology.spotnav.ha.dashboard

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

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

/**
 * "Release the import target tonight" (the card's release button): offered while the site's import target holds or
 * pauses the charge (`import_target_held`, `import_target_paused` with `releasable`), and beside `import_target_at_risk`
 * where the plan asks (`releasable` not `false`), to any user, and only where the dashboard states
 * `capabilities.release_import_target`. It is sent only after a confirmation that says what it means ([confirmText]).
 */
internal object ImportTargetRelease {
    const val LABEL_KEY = "issue.importTargetRelease"

    private val HOLDING = setOf("import_target_held", "import_target_paused")

    /** The line that offers the release, or `null`. */
    private fun line(dashboard: Dashboard?): StatusLine? {
        if (dashboard == null || !dashboard.capabilities.releaseImportTarget) return null
        return dashboard.status.lines.firstOrNull {
            (it.code in HOLDING && it.params["releasable"] == true) ||
                (it.code == "import_target_at_risk" && it.params["releasable"] != false)
        }
    }

    fun offered(dashboard: Dashboard?): Boolean = line(dashboard) != null

    /** The button's words in [language], or `null` where it is not offered. */
    fun label(dashboard: Dashboard?, language: String): String? =
        if (offered(dashboard)) HaStatusWording.text(language, LABEL_KEY) else null

    /**
     * What setting the target aside would mean (the card's `releaseConfirmText`): until the unplug, or until the
     * departure at its time; and the month's peak it may reach against today's level. `null` where it is not offered.
     */
    fun confirmText(dashboard: Dashboard?, format: StatusFormat, now: Instant): String? {
        val line = line(dashboard) ?: return null
        val language = format.language
        fun say(key: String, params: Map<String, String> = emptyMap()): String {
            var text = HaStatusWording.text(language, key).orEmpty()
            for ((name, value) in params) text = text.replace("{$name}", value)
            return text
        }
        val p = line.params
        val until = (p["release_until"] as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        val zone = format.zone
        val sentences = mutableListOf(
            if (until == null || zone == null) say("release.confirm.unplug")
            else {
                val clock = DateTimeFormatter.ofPattern("HH:mm")
                val zoned = until.atZone(zone)
                // Tonight's departure by its time; one more than a day ahead with its day, as the status lines say it.
                val time = if (Duration.between(now, until) < Duration.ofHours(24) || zoned.toLocalDate() == now.atZone(zone).toLocalDate()) {
                    clock.format(zoned)
                } else "${DateTimeFormatter.ofPattern("EEE d MMM", format.locale).format(zoned)} ${clock.format(zoned)}"
                say("release.confirm.departure", mapOf("time" to time))
            }
        )
        val peak = (p["release_peak_kw"] as? Double)?.takeIf { it.isFinite() }
        val today = (p["target_kw"] as? Double)?.takeIf { it.isFinite() }
        if (peak != null && today != null) {
            sentences += say("release.confirm.peak", mapOf(
                "peak" to HaStatusText.number(format.locale, peak, 0),
                "target" to HaStatusText.fixed(format.locale, today, 1)
            ))
        }
        return sentences.joinToString(" ")
    }
}
