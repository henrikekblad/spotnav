package se.sensnology.spotnav.sites

import android.content.Context
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.ownWebhook
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.SiteDoor
import se.sensnology.spotnav.ha.client.WebhookGoneException
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.push.PushStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the phone on the home's webhook, the app's single door since Home Assistant 3.0, without anyone pairing
 * again:
 *
 * 1. **The silent move.** Each charger profile still on its own webhook asks it `get_site_access` once. A home in
 *    the answer becomes a [SiteProfile] with the charger inside, reached through the home's webhook from then on.
 *    Home Assistant 2.x ("Unsupported action"), a charger in no home (`spotnav_no_site`) and a rate limit keep the
 *    charger's own webhook; a home not loaded (`spotnav_site_unloaded`) is asked again next time, never read as "no
 *    home". Only an empty answer, confirmed, marks the charger to be paired again.
 * 2. **The home's chargers.** Each home is asked `get_site`, and a charger added to it in Home Assistant appears as a
 *    charger profile ([SiteSync]); an empty answer marks the home to be paired again, a home not loaded waits.
 *
 * Nothing here touches the views; [run] is called off the main thread.
 */
internal class SiteUpkeep(
    private val chargers: ChargerProfileStore,
    private val sites: SiteProfileStore,
    private val access: (HomeAssistantSettings) -> SiteDoor.Access = HomeAssistantClient::getSiteAccess,
    private val getSite: (HomeAssistantSettings) -> SiteDoor.GetSite = HomeAssistantClient::getSite,
    private val newLocalId: () -> String = { ChargerProfileStore.newLocalId() }
) {
    /** What one run did: the charger profiles that changed door (to be told push again) and the ones added. */
    data class Report(val moved: List<String> = emptyList(), val added: List<String> = emptyList())

    fun run(now: Long = System.currentTimeMillis()): Report {
        val moved = mutableListOf<String>()
        val added = mutableListOf<String>()
        moveChargers(now, moved, added)
        for (site in sites.list()) refreshSite(site, now, moved, added)
        return Report(moved.distinct(), added)
    }

    /** One home's `get_site`, and its chargers merged (see [SiteSync]); the screens call it when they show a home. */
    fun refresh(siteLocalId: String, now: Long = System.currentTimeMillis()): SiteDoor.GetSite? {
        val site = sites.get(siteLocalId) ?: return null
        return refreshSite(site, now, mutableListOf(), mutableListOf())
    }

    private fun moveChargers(now: Long, moved: MutableList<String>, added: MutableList<String>) {
        for (profile in chargers.listProfiles()) {
            if (!candidate(profile)) continue
            // Moved already in this run, as another charger of the same home.
            val current = chargers.getProfile(profile.localId) ?: continue
            if (!candidate(current)) continue
            if (!chargers.claimRead(current.localId, now)) continue
            when (val answer = access(current.ownWebhook())) {
                is SiteDoor.Access.Granted -> {
                    val site = answer.site
                    // The home always lists the charger that asked; should it not, the charger still moves in.
                    val listed = if (site.chargers.any { it.id == current.remoteChargerId }) site.chargers
                    else site.chargers + se.sensnology.spotnav.ha.pairing.SiteCharger(current.remoteChargerId!!, current.remoteChargerName ?: current.remoteChargerId)
                    write(current.baseUrl, site.id, site.name, site.webhookId, listed, moved, added)
                }
                SiteDoor.Access.Gone -> chargers.recordWebhookAnswer(current.localId, WebhookGoneException(), now)
                // HA 2.x, no home, rate-limited, not loaded, offline: the charger's own webhook, as before.
                else -> Unit
            }
        }
    }

    private fun candidate(profile: ChargerProfile): Boolean =
        !profile.viaSite && profile.webhookId.isNotBlank() && profile.baseUrl.isNotBlank() && !profile.remoteChargerId.isNullOrBlank()

    private fun refreshSite(site: SiteProfile, now: Long, moved: MutableList<String>, added: MutableList<String>): SiteDoor.GetSite? {
        if (!site.configured || !sites.claimRead(site.localId, now)) return null
        val answer = getSite(site.toHomeAssistantSettings())
        when (answer) {
            is SiteDoor.GetSite.Read -> {
                sites.recordWebhookAnswer(site.localId, null, now)
                // The site id is the home's own; an answer naming another is not this home's to merge.
                if (answer.site.siteId == site.siteId) {
                    write(site.baseUrl, site.siteId, answer.site.name, site.webhookId, answer.site.chargers, moved, added)
                }
            }
            SiteDoor.GetSite.Gone -> sites.recordWebhookAnswer(site.localId, WebhookGoneException(), now)
            else -> sites.recordWebhookAnswer(site.localId, IllegalStateException("no answer"), now)
        }
        return answer
    }

    private fun write(
        baseUrl: String, siteId: String, name: String, webhookId: String, listed: List<se.sensnology.spotnav.ha.pairing.SiteCharger>,
        moved: MutableList<String>, added: MutableList<String>
    ) {
        val applied = SiteSync.apply(
            baseUrl, siteId, name, webhookId, listed, sites.list(), chargers.listProfiles(),
            newSiteLocalId = { SiteProfileStore.newLocalId() }, newChargerLocalId = newLocalId
        )
        sites.upsert(applied.site)
        applied.chargers.forEach { chargers.upsertProfile(it) }
        moved += applied.moved
        added += applied.added
    }

    companion object {
        private val started = AtomicBoolean(false)

        /**
         * Once per process (the app's start): the run on [executor], then push told again through each changed
         * door, and [done] on whatever thread the run ended on.
         */
        fun startOnce(context: Context, executor: Executor, done: (Report) -> Unit = {}) {
            if (!started.compareAndSet(false, true)) return
            val app = context.applicationContext
            executor.execute {
                val report = runCatching {
                    SiteUpkeep(ChargerProfileStore.forContext(app), SiteProfileStore.forContext(app)).run()
                }.getOrElse { Report() }
                afterRun(app, report)
                done(report)
            }
        }

        /** Push follows the door: a charger that moved is registered again through its new one. */
        fun afterRun(context: Context, report: Report) {
            if (report.moved.isEmpty() && report.added.isEmpty()) return
            val push = PushStore.forContext(context)
            report.moved.forEach { push.remember(it, null) }
            LocalNotifications.sync(context)
        }
    }
}
