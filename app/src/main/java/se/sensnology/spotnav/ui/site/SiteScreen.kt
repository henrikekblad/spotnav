package se.sensnology.spotnav.ui.site

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.client.SiteDoor
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.session.HomeAssistantTransport
import se.sensnology.spotnav.sites.MainSubject
import se.sensnology.spotnav.sites.SiteProfile
import se.sensnology.spotnav.sites.SiteProfileStore
import se.sensnology.spotnav.sites.SiteUpkeep
import se.sensnology.spotnav.ui.Screen
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.pairAgainNotice

/**
 * The main screen for a home without a charger (Home Assistant 3.0): the top row with the app's mark and the gear,
 * and the house page and, where the heating has something to show, the heat page -- no charger page and no charger
 * icon. Everything goes through the home's own webhook. `get_site` says which pages exist (and is the read that
 * notices a webhook Home Assistant no longer has); until it has answered, the pages last offered are shown.
 */
internal class SiteScreen(shell: ScreenShell, private val site: SiteProfile, private val rebuild: () -> Unit) : ScreenPart(shell) {
    private val key = MainSubject.pagesKey(site)
    private lateinit var session: HaSession
    private lateinit var line: TextView

    fun show() {
        beginScreen()
        shell.pages.showTop { showScreen(Screen.SETTINGS) }
        val generation = viewGeneration
        val handler = Handler(Looper.getMainLooper())
        fun current() = !activity.isDestroyed && generation == shell.generation
        session = HaSession(
            transport = HomeAssistantTransport(site.toHomeAssistantSettings()),
            // A home has no dashboard: nothing to record.
            recorder = { null },
            background = shell.io,
            mainThread = { block -> activity.runOnUiThread { if (current()) block() } },
            schedule = { delayMs, block -> handler.postDelayed({ if (current()) block() }, delayMs) }
        )
        // The scrolling body, shown until the pages are known (and when the home waits to be paired again).
        content.addView(TextView(context).apply {
            text = site.name
            textSize = 20f
            setTextColor(dark)
            setPadding(0, dp(8), 0, dp(8))
        })
        line = TextView(context).apply { textSize = 14f; setTextColor(muted) }
        content.addView(line)
        if (site.needsPairing) {
            showPairAgain()
            return
        }
        shell.pages.knownFor(key)?.let(::offer) ?: run { line.text = t(R.string.instance_refreshing) }
        read()
        shell.onForeground = { read() }
    }

    private fun showPairAgain() {
        line.visibility = View.GONE
        content.addView(pairAgainNotice(t(R.string.pair_again_needed)) {
            shell.pairAgainSite = site.localId
            shell.navigate(Screen.SETTINGS)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** `get_site` (and with it the home's chargers followed), then the pages it offers. */
    private fun read() {
        val generation = viewGeneration
        val app = applicationContext
        shell.io.execute {
            val answer = SiteUpkeep(ChargerProfileStore.forContext(app), SiteProfileStore.forContext(app)).refresh(site.localId)
            activity.runOnUiThread {
                if (activity.isDestroyed || generation != shell.generation) return@runOnUiThread
                when (answer) {
                    // A charger added to the home meanwhile is now a charger profile too (Settings -> Home Assistant
                    // shows it); this screen stays the home's until the person picks it.
                    is SiteDoor.GetSite.Read -> offerFrom(answer)
                    SiteDoor.GetSite.Gone -> rebuild()
                    // Not loaded, offline: what was offered stays; else say Home Assistant could not be reached.
                    else -> if (shell.pages.knownFor(key) == null) line.text = t(R.string.instance_unreachable)
                }
            }
        }
    }

    private fun offerFrom(answer: SiteDoor.GetSite.Read) {
        val flags = answer.site.capabilities
        val memory = shell.pages.memory(key)
        if (!flags.getHeat) {
            offer(PageOffer.kinds(house = flags.getHouse, heat = false, charger = false))
            return
        }
        memory.heatShown?.let { offer(PageOffer.kinds(house = flags.getHouse, heat = it, charger = false)); return }
        session.heat { outcome ->
            if (outcome is SiteRead.Read) {
                memory.heat = outcome.value
                memory.heatShown = outcome.value.hasSomething
            }
            offer(PageOffer.kinds(house = flags.getHouse, heat = memory.heatShown == true, charger = false))
        }
    }

    private fun offer(kinds: List<PageKind>) {
        if (kinds.isEmpty()) {
            line.text = t(R.string.instance_connected_nothing)
            return
        }
        shell.pages.offer(key, kinds) { kind ->
            when (kind) {
                PageKind.HOUSE -> HousePage(this, session, shell.pages.memory(key), SitePreferences.of(context), key)
                PageKind.HEAT -> HeatPage(this, session, shell.pages.memory(key), SitePreferences.of(context), key)
                else -> error("no charger page for a home")
            }
        }
    }
}
