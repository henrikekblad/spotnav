package se.sensnology.spotnav.prices

import android.util.Log
import se.sensnology.spotnav.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** What one GET answered: a body, a genuine "not here" (404), or a failure of any other kind. */
internal sealed interface RelayFetch {
    data class Body(val text: String) : RelayFetch
    data object NotFound : RelayFetch
    data object Failed : RelayFetch

    /** The body, or `null` for either kind of absence. */
    val bodyOrNull: String? get() = (this as? Body)?.text
}

/** The one place this app speaks HTTP to SpotNav Relay. */
internal object RelayHttp {
    private const val TAG = "SpotNavRelay"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    fun get(url: String): String? = fetch(url).bodyOrNull

    /** [get], keeping a 404 apart from every other failure. */
    fun fetch(url: String): RelayFetch {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Log.e(TAG, "Could not open $url", error)
            return RelayFetch.Failed
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Sensnology-SpotNav/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.i(TAG, "GET $url -> HTTP $status")
                return RelayFetch.NotFound
            }
            if (status !in 200..299) {
                Log.i(TAG, "GET $url -> HTTP $status")
                return RelayFetch.Failed
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                Log.i(TAG, "GET $url -> empty body")
                return RelayFetch.Failed
            }
            Log.i(TAG, "GET $url -> ${body.length} bytes")
            RelayFetch.Body(body)
        } catch (error: Exception) {
            Log.e(TAG, "Failed GET $url", error)
            RelayFetch.Failed
        } finally {
            connection.disconnect()
        }
    }
}
