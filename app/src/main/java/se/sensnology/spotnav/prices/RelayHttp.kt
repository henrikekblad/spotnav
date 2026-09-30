package se.sensnology.spotnav.prices

import android.util.Log
import se.sensnology.spotnav.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** The one place this app speaks HTTP to SpotNav Relay. */
internal object RelayHttp {
    private const val TAG = "SpotNavRelay"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    fun get(url: String): String? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Log.e(TAG, "Could not open $url", error)
            return null
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Sensnology-SpotNav/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.i(TAG, "GET $url -> HTTP $status")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                Log.i(TAG, "GET $url -> empty body")
                return null
            }
            Log.i(TAG, "GET $url -> ${body.length} bytes")
            body
        } catch (error: Exception) {
            Log.e(TAG, "Failed GET $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }
}
