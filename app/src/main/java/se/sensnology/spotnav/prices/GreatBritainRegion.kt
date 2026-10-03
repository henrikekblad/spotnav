package se.sensnology.spotnav.prices

import android.util.Log
import org.json.JSONObject
import se.sensnology.spotnav.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Find a Great Britain price region from a postcode.
 *
 * A Great Britain region (`GB-A` … `GB-P`) is a GSP group, and few people know which one they are in.
 * Octopus Energy answers a postcode with it (`/v1/industry/grid-supply-points/?postcode=…`, public and
 * keyless). The app asks Octopus directly: a postcode is personal data, so it never goes to the SpotNav
 * relay, and it is neither stored nor logged here (a failure is logged by its kind only). Home
 * Assistant's `spotnav/find_region` follows the same rules.
 *
 * The answer is the relay's area ids for the groups Octopus names, kept only when the catalogue lists
 * them. Nothing is chosen on the person's behalf beyond selecting the region in the picker.
 */
internal object GreatBritainRegion {
    /** Octopus Energy's public grid-supply-point lookup (no key, no account). */
    const val OCTOPUS_GSP_URL = "https://api.octopus.energy/v1/industry/grid-supply-points/"

    private const val TAG = "SpotNavRegion"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_CHARS = 64 * 1024

    /** A UK postcode, loosely: an outward code of two to four characters and an inward code of three. */
    private val POSTCODE = Regex("^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$")

    /** A GSP group as Octopus names it (`_C`); there is no I and no O. */
    private val GROUP_ID = Regex("^_([A-HJ-NP])$")

    /** What one lookup answered. */
    sealed interface Answer {
        /** The catalogue's regions for the postcode, in Octopus's order, never empty. */
        data class Found(val regions: List<String>) : Answer {
            val region: String get() = regions.first()
        }

        /** Not a UK postcode; nothing was asked. */
        data object Invalid : Answer

        /** Octopus knows no region the catalogue lists for it. */
        data object NotFound : Answer

        /** The lookup failed (no network, an unexpected answer). */
        data object Unavailable : Answer
    }

    /** The postcode in upper case with single spacing, or `null` when it cannot be one. */
    fun normalized(raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)
        if (compact.length !in 5..7) return null
        val spaced = "${compact.dropLast(3)} ${compact.takeLast(3)}"
        return spaced.takeIf { POSTCODE.matches(it) }
    }

    /** The relay area ids (`GB-<letter>`) for the GSP groups an Octopus answer names, each once, in order. */
    fun regionsFrom(body: String): List<String> {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: throw IllegalArgumentException("not a grid-supply-points answer")
        val regions = LinkedHashSet<String>()
        for (index in 0 until results.length()) {
            val group = results.optJSONObject(index)?.opt("group_id") as? String ?: continue
            GROUP_ID.matchEntire(group)?.let { regions.add("GB-${it.groupValues[1]}") }
        }
        return regions.toList()
    }

    /**
     * The whole lookup without the transport: validate, ask [get] (which receives the request URL and
     * answers a body or `null`), and keep what [catalogue] lists.
     */
    fun find(raw: String, catalogue: List<PriceMarket>, get: (String) -> String?): Answer {
        val postcode = normalized(raw) ?: return Answer.Invalid
        val url = OCTOPUS_GSP_URL + "?postcode=" + URLEncoder.encode(postcode.replace(" ", ""), "UTF-8")
        val found = try {
            regionsFrom(get(url) ?: return Answer.Unavailable)
        } catch (error: Exception) {
            // The kind of failure only: the postcode and the answer stay out of the log.
            logFailure(error.javaClass.simpleName)
            return Answer.Unavailable
        }
        val listed = found.filter { id -> catalogue.any { it.id == id } }
        return if (listed.isEmpty()) Answer.NotFound else Answer.Found(listed)
    }

    /** Whether a picker offers the lookup: the catalogue has a Great Britain area, and the person is in one or in GB. */
    fun offered(catalogue: List<PriceMarket>, selected: PriceMarket?, region: String): Boolean =
        catalogue.any { it.inGreatBritain } && (selected?.inGreatBritain == true || region.equals(PriceMarket.GREAT_BRITAIN, ignoreCase = true))

    /** One GET to Octopus, logging neither the URL (it holds the postcode) nor the body. */
    fun httpGet(url: String): String? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            logFailure(error.javaClass.simpleName)
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Sensnology-SpotNav/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                logFailure("HTTP $status")
                return null
            }
            val buffer = CharArray(MAX_RESPONSE_CHARS + 1)
            val read = connection.inputStream.bufferedReader().use { reader ->
                var total = 0
                while (total < buffer.size) {
                    val count = reader.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
                total
            }
            if (read > MAX_RESPONSE_CHARS) {
                logFailure("too large")
                return null
            }
            String(buffer, 0, read)
        } catch (error: Exception) {
            logFailure(error.javaClass.simpleName)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun logFailure(kind: String) {
        runCatching { Log.w(TAG, "Looking up a Great Britain region failed: $kind") }
    }
}
