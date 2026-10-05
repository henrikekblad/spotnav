package se.sensnology.spotnav.prices

import se.sensnology.spotnav.BuildConfig

/**
 * Where relay requests go. Always [OFFICIAL], except in a debug build made with
 * `-PrelayBaseUrl=...` (the documentation screenshots point one at a local stub). A release build
 * ignores the configured address even if one were compiled in, and the Gradle build refuses the
 * property for any release task.
 */
internal object RelayAddress {
    const val OFFICIAL = "https://spotnav.sensnology.se"

    /** The base this build sends relay requests to. */
    val base: String = baseFor(BuildConfig.DEBUG, BuildConfig.RELAY_BASE_URL)

    /** [OFFICIAL] for a release build whatever was configured; the configured base for a debug one. */
    fun baseFor(debug: Boolean, configured: String): String =
        if (debug && configured.isNotBlank()) configured.trimEnd('/') else OFFICIAL

    /** [url] (always written against [OFFICIAL]) moved onto [base]; anything else unchanged. */
    fun resolve(url: String, base: String = this.base): String =
        if (base != OFFICIAL && url.startsWith(OFFICIAL)) base + url.substring(OFFICIAL.length) else url
}
