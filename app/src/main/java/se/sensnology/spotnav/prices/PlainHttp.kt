package se.sensnology.spotnav.prices

import se.sensnology.spotnav.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** What one GET to an upstream source answered. */
internal sealed interface HttpAnswer {
    /** Any status, with its (bounded) body and the `Retry-After` header when one was sent. */
    data class Response(val status: Int, val body: String, val retryAfter: String? = null) : HttpAnswer

    /**
     * No answer at all. [kind] is the failure's class name and nothing else: an exception's message
     * can carry the request URL, and an ENTSO-E URL carries the person's key.
     */
    data class NoAnswer(val kind: String) : HttpAnswer
}

/** One GET, as a seam a test can replace. It logs nothing: the caller decides what may be said. */
internal fun interface HttpGet {
    fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): HttpAnswer
}

/** The production [HttpGet]: `HttpURLConnection`, no redirects followed, no logging, no messages kept. */
internal object PlainHttp : HttpGet {
    private const val CONNECT_TIMEOUT_MS = 10_000

    override fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): HttpAnswer {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "Sensnology-SpotNav/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            // An error status still has a body worth reading: ENTSO-E sends its acknowledgements with 400.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { read(it, maxBytes) } ?: ""
            HttpAnswer.Response(status, body, connection.getHeaderField("Retry-After"))
        } catch (error: Exception) {
            // The class name only: see HttpAnswer.NoAnswer.
            HttpAnswer.NoAnswer(error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** Thrown when a body is longer than the caller allows; its message is empty on purpose. */
    class TooLarge : Exception()

    private fun read(stream: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw TooLarge()
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
