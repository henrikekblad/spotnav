package se.sensnology.spotnav.prices

import android.util.Log
import se.sensnology.spotnav.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** The one place this app speaks HTTP to SpotNav Relay: the push registration's POST; prices are never asked of it. */
internal object RelayHttp {
    private const val TAG = "SpotNavRelay"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * One JSON POST: the status and body of whatever answered, or `(null, null)` with no answer at
     * all. Only the path and the status are logged, never the body sent or received.
     */
    fun post(url: String, body: String): Pair<Int?, String?> {
        val connection = try {
            URL(RelayAddress.resolve(url)).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Log.w(TAG, "Could not open POST ${URL_PATH.find(url)?.value}")
            return Pair(null, null)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Sensnology-SpotNav/${BuildConfig.VERSION_NAME}")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            Log.i(TAG, "POST ${URL_PATH.find(url)?.value} -> HTTP $status")
            Pair(status, text)
        } catch (error: Exception) {
            Log.w(TAG, "Failed POST ${URL_PATH.find(url)?.value}: ${error.javaClass.simpleName}")
            Pair(null, null)
        } finally {
            connection.disconnect()
        }
    }

    private val URL_PATH = Regex("""(?<=://)[^?#]*""")
}
