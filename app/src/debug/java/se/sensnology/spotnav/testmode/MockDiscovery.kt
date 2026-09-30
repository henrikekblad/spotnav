package se.sensnology.spotnav.testmode

import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Finding the mock Home Assistant: the UDP probe, and the HTTP fallback. */
internal object MockDiscovery {
    /** How long to keep listening for answers. */
    private const val LISTEN_MILLIS = 2_500L

    /** How long a single `receive` waits before the loop checks its deadline. */
    private const val RECEIVE_TIMEOUT_MILLIS = 300

    private const val MAX_REPLY_BYTES = 4_096

    /** Broadcast the probe and collect every answer for [LISTEN_MILLIS]. */
    fun discover(timeoutMillis: Long = LISTEN_MILLIS): List<MockCatalogue.Server> {
        val found = LinkedHashMap<String, MockCatalogue.Server>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = RECEIVE_TIMEOUT_MILLIS
            send(socket)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val payload = receive(socket) ?: continue
                MockCatalogue.serverFromReply(payload, allowedBaseUrl)?.let { found[it.baseUrl] = it }
            }
        }
        return found.values.toList()
    }

    /**
     * The catalogue at a typed-in address, or `null` when that address is not one this app may talk
     * to — checked before a connection is opened, so an address the user mistyped cannot even be
     * contacted.
     */
    fun catalogue(baseUrl: String): List<MockCatalogue.Scenario>? {
        if (!allowedBaseUrl(baseUrl)) return null
        val connection = URL(MockCatalogue.catalogueUrl(baseUrl)).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            MockCatalogue.scenariosFromCatalogue(body, baseUrl, allowedBaseUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun send(socket: DatagramSocket) {
        val probe = MockCatalogue.DISCOVERY_PROBE.toByteArray(Charsets.UTF_8)
        socket.send(
            DatagramPacket(
                probe, probe.size,
                InetAddress.getByName("255.255.255.255"), MockCatalogue.DISCOVERY_PORT
            )
        )
    }

    /** The next reply, or null when this window's wait ran out. */
    private fun receive(socket: DatagramSocket): String? {
        val buffer = ByteArray(MAX_REPLY_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            String(packet.data, 0, packet.length, Charsets.UTF_8)
        } catch (timeout: SocketTimeoutException) {
            null
        }
    }

    private val allowedBaseUrl: (String) -> Boolean = { value -> HomeAssistantSettings.isAllowedBaseUrl(value) }
}
