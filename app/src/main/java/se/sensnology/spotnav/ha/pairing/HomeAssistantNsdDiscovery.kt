package se.sensnology.spotnav.ha.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finding Home Assistant on the network, over mDNS. */
internal class HomeAssistantNsdDiscovery(private val context: Context) {
    /**
     * Every instance that answers within [timeoutMillis], filtered and de-duplicated by
     * [InstanceDiscovery]. Blocking: callers run it on the screen's io executor.
     */
    fun discover(timeoutMillis: Long = DISCOVERY_MILLIS): List<InstanceDiscovery.Found> {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()
        val lock = runCatching { multicastLock()?.also { it.acquire() } }.getOrNull()
        val answers = LinkedHashSet<InstanceDiscovery.Found>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val name = resolved.serviceName ?: host
                        answers += InstanceDiscovery.Found("http://$host:${resolved.port}", name)
                    }
                })
            }
        }
        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            // The discovery callback itself is asynchronous; the wait is what turns "answers will
            // arrive" into a list.
            answersLatch(timeoutMillis).await()
            InstanceDiscovery.found(answers.toList()) { HomeAssistantSettings.isAllowedBaseUrl(it) }
        } catch (failure: Exception) {
            emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    /**
     * How long to collect answers. a second instance may be a fraction slower, and a phone that
     * stopped at the first would silently hide it.
     */
    private fun answersLatch(timeoutMillis: Long): CountDownLatch {
        val latch = CountDownLatch(1)
        Thread { runCatching { latch.await(timeoutMillis, TimeUnit.MILLISECONDS) }; latch.countDown() }.start()
        return latch
    }

    private fun multicastLock(): WifiManager.MulticastLock? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching { wifi.createMulticastLock(LOCK_TAG).apply { setReferenceCounted(false) } }.getOrNull()
    }

    private companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp"
        const val LOCK_TAG = "spotnav-mdns"
        const val DISCOVERY_MILLIS = 4_000L
    }
}
