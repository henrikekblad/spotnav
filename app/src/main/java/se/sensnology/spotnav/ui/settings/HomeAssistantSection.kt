package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.ChargerReconciliation
import se.sensnology.spotnav.chargers.ProfileCaches
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.WebhookGone
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.notify.AppIdentity
import se.sensnology.spotnav.notify.PhoneName
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.push.PushStore
import se.sensnology.spotnav.ha.pairing.ChargerPairingApproval
import se.sensnology.spotnav.ha.pairing.HomeAssistantInstanceStore
import se.sensnology.spotnav.ha.pairing.HomeAssistantNsdDiscovery
import se.sensnology.spotnav.ha.pairing.HomeAssistantPairingClient
import se.sensnology.spotnav.ha.pairing.InstanceDiscovery
import se.sensnology.spotnav.ha.pairing.PairingProtocol
import se.sensnology.spotnav.ha.pairing.PairingStop
import se.sensnology.spotnav.sites.SiteProfile
import se.sensnology.spotnav.sites.SiteProfileStore
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.chargerName
import se.sensnology.spotnav.ui.common.pairAgainNotice
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.app.LauncherActivity
import se.sensnology.spotnav.widget.PriceWidgetProvider
import se.sensnology.spotnav.widget.WidgetChargerBindingStore
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Home Assistant card of the settings screen: the instance, its pairing, and what it handed
 * over.
 */
internal class HomeAssistantSection(
    shell: ScreenShell,
    /** Told when pairing switched this widget's plan line on, so the screen's own checkbox follows. */
    private val onPlanDefaulted: () -> Unit = {},
    /**
     * Told when an instance was paired or removed, so the screen shows or drops the cards that
     * belong to it. The page is built again, and the new section asks the instance itself.
     */
    private val onInstanceChanged: () -> Unit = {}
) : ScreenPart(shell) {
    /** The Home Assistant connection: **one instance, paired once**. */
    /**
     * The line under the Home Assistant heading: what the app connects *to*, with the integration's
     * own name as a link to where it is installed from.
     */
    private fun homeAssistantIntro(): TextView {
        val link = t(R.string.home_assistant_integration_link)
        val introduction = SpannableString(t(R.string.home_assistant_intro, link)).apply {
            val start = toString().indexOf(link)
            if (start >= 0) setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HOME_ASSISTANT_REPOSITORY)))
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = accent
                    ds.isUnderlineText = true
                }
            }, start, start + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(context).apply {
            text = introduction
            textSize = 13f
            setTextColor(muted)
            // The same header-to-first-content gap every other card's first row carries, by the
            // same means:
            setPadding(0, dp(16), 0, dp(6))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0x00000000
        }
    }

    fun add(parent: LinearLayout) {
        // Where the other half of this comes from.
        parent.addView(homeAssistantIntro())
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(body)

        val instanceStore = HomeAssistantInstanceStore.forContext(applicationContext)
        val profileStore = ChargerProfileStore.forContext(applicationContext)
        val cancelled = AtomicBoolean(false)

        var code: String? = null
        var pairing = false
        var statusLine = ""
        var choices: List<InstanceDiscovery.Found> = emptyList()
        var askForAddress = false
        // What the person typed, kept across redraws so a wrong address can be corrected, not retyped.
        var typedAddress = ""
        // What discovery called the instance being paired with, so the connected screen can say
        // "Home" rather than an address the user never typed.
        var pairedName: String? = null
        // The instance being paired with, so the pairing screen can offer a way straight to the
        // page the request is waiting on.
        var pairingTarget: String? = null
        var report: ChargerReconciliation.Report? = null

        // Declared mutable so the builders below can call it: every action in this section is
        // "change a var, then redraw".
        var redraw: () -> Unit = {}

        fun line(text: String) {
            body.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(6))
            })
        }

        fun row(vararg views: android.view.View) {
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                views.forEach { addView(it) }
            })
        }

        fun button(label: String, onClick: () -> Unit) = Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        /** Ask the instance what it has, and reconcile it against our profiles. */
        fun refresh() {
            val baseUrl = instanceStore.baseUrl() ?: return
            // A charger whose webhook is known to be gone is asked only when nothing else can be:
            // The homes are asked too (`get_site`): a charger added to one appears, a gone webhook is marked.
            val siteStore = SiteProfileStore.forContext(applicationContext)
            ioExecutor.execute {
                val upkeep = se.sensnology.spotnav.sites.SiteUpkeep(profileStore, siteStore)
                siteStore.list().filter { it.baseUrl == baseUrl }.forEach { upkeep.refresh(it.localId) }
                runOnUiThread { if (!pairing) redraw() }
            }
            val asked = profileStore.listProfiles()
                .filter { it.baseUrl == baseUrl && it.configured }
                .minByOrNull { if (it.needsPairing) 1 else 0 } ?: return
            statusLine = t(R.string.instance_refreshing)
            redraw()
            ioExecutor.execute {
                val read = runCatching { HomeAssistantClient.dashboard(asked.toHomeAssistantSettings()) }
                // Asked by hand: the answer also says whether that webhook is (still) recognised.
                profileStore.applyDashboardResult(asked.localId, read)
                val answer = read.getOrNull()
                val reconciled = answer?.let {
                    ChargerReconciliation.reconcile(profileStore.listProfiles(), baseUrl, it.chargers)
                }
                runOnUiThread {
                    report = reconciled
                    // What changes is what Home Assistant owns:
                    reconciled?.updates?.forEach { profileStore.upsertProfile(it) }
                    statusLine = when {
                        answer != null -> ""
                        // The pair-again line under the instance says it, with its button.
                        WebhookGone.isGone(read.exceptionOrNull()) -> ""
                        else -> t(R.string.instance_unreachable)
                    }
                    redraw()
                }
            }
        }

        /**
         * What an approval means on this device: the instance is remembered, and every charger it
         * handed over becomes a profile.
         */
        val applyApproval: (PairingStop.Approved) -> Unit = { approved ->
            val baseUrl = approved.baseUrl
            instanceStore.remember(baseUrl, pairedName)
            val siteStore = SiteProfileStore.forContext(applicationContext)
            val bindingStore = WidgetChargerBindingStore.forContext(applicationContext)
            // What this approval means is [ChargerPairingApproval]'s decision, taken on the widget
            // that asked for the pairing:
            val applied = ChargerPairingApproval.apply(
                outcome = approved,
                existing = profileStore.listProfiles(),
                baseUrl = baseUrl,
                // What is *stored* for this widget, never a lazy default:
                storedBinding = bindingStore.storedBinding(widgetId),
                newLocalId = { ChargerProfileStore.newLocalId() },
                existingSites = siteStore.list(),
                newSiteLocalId = { SiteProfileStore.newLocalId() }
            )
            ChargerPairingApproval.commit(applied, object : ChargerPairingApproval.Device {
                override fun forgetCached(localId: String) = ProfileCaches.forget(applicationContext, localId)

                override fun upsert(profile: ChargerProfile) = profileStore.upsertProfile(profile)

                override fun activeProfileId(): String? = profileStore.getActiveProfileId()

                override fun activate(localId: String) = profileStore.setActiveProfileId(localId)

                override fun upsertSite(site: SiteProfile) = siteStore.upsert(site)

                override fun activeSiteId(): String? = siteStore.activeSiteId()

                override fun activateSite(localId: String) = siteStore.setActiveSiteId(localId)

                override fun forgetPushRegistration(localId: String) =
                    PushStore.forContext(applicationContext).remember(localId, null)

                override fun syncPush() = LocalNotifications.sync(applicationContext)

                override fun bind(localId: String) {
                    val hadCharger = bindingStore.storedBinding(widgetId).chargerProfileId != null
                    bindingStore.setBinding(widgetId, localId)
                    if (!hadCharger && widgetId > 0) {
                        WidgetSettings.planDefaultOnBinding(applicationContext, widgetId)
                        onPlanDefaulted()
                    }
                }

                override fun repaint() {
                    report = null
                    // Nothing handed over: say what to do about it (add one, or update SpotNav there).
                    statusLine = if (applied?.empty == true) t(R.string.instance_connected_nothing)
                    else t(R.string.instance_connected, baseUrl)
                    redraw()
                }
            })
            // Only a successful approval changes the page: its paired cards come with it.
            if (applied != null) onInstanceChanged()
        }

        /** [alternatives] are tried in order when [baseUrl] does not answer; the first that does is paired. */
        fun beginPairing(baseUrl: String, discoveredName: String? = null, alternatives: List<String> = emptyList()) {
            cancelled.set(false)
            pairedName = discoveredName
            pairingTarget = baseUrl
            code = PairingProtocol.newCode()
            pairing = true
            statusLine = t(R.string.instance_pairing_waiting)
            choices = emptyList()
            askForAddress = false
            redraw()
            val shown = code.orEmpty()
            ioExecutor.execute {
                var target = baseUrl
                var requestId: String? = null
                // This app's secret id beside the device's name: Home Assistant 3.0 knows this phone by it.
                val appId = AppIdentity.forContext(applicationContext).appId()
                // The phone's own name (what the approval in Home Assistant shows) and its model beside it.
                val deviceName = PhoneName.of(applicationContext)
                for (candidate in listOf(baseUrl) + alternatives) {
                    if (cancelled.get()) return@execute
                    requestId = HomeAssistantPairingClient.request(candidate, deviceName, shown, appId, PhoneName.model)
                    if (requestId != null) { target = candidate; break }
                }
                if (requestId == null) {
                    runOnUiThread {
                        pairing = false
                        statusLine = t(R.string.instance_unreachable)
                        // The address field comes back with what was typed in it, to be corrected:
                        askForAddress = true
                        redraw()
                    }
                    return@execute
                }
                if (target != baseUrl) runOnUiThread {
                    // The address that answered is the one to open and to remember:
                    if (pairing && !cancelled.get()) { pairingTarget = target; redraw() }
                }
                val stop = HomeAssistantPairingClient.awaitApproval(target, requestId) { cancelled.get() }
                runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    pairing = false
                    when (stop) {
                        is PairingStop.Approved -> applyApproval(stop)
                        PairingStop.Denied -> { statusLine = t(R.string.instance_pairing_denied); redraw() }
                        PairingStop.Expired -> { statusLine = t(R.string.instance_pairing_expired); redraw() }
                        PairingStop.TimedOut -> { statusLine = t(R.string.instance_pairing_timed_out); redraw() }
                        PairingStop.Unreachable -> { statusLine = t(R.string.instance_pairing_lost_contact); redraw() }
                        else -> { statusLine = t(R.string.instance_pairing_unusable); redraw() }
                    }
                }
            }
        }

        fun findHomeAssistant() {
            statusLine = t(R.string.instance_refreshing)
            askForAddress = false
            choices = emptyList()
            redraw()
            ioExecutor.execute {
                // Never fatal:
                val found = runCatching { HomeAssistantNsdDiscovery(applicationContext).discover() }
                    .getOrDefault(emptyList())
                runOnUiThread {
                    statusLine = ""
                    when {
                        found.size == 1 -> beginPairing(found.single().baseUrl, found.single().name)
                        found.isEmpty() -> { askForAddress = true; redraw() }
                        else -> { choices = found; redraw() }
                    }
                }
            }
        }

        /**
         * Pair again with the instance held: the same handshake, and an approval that names the same
         * chargers gives their profiles the new webhooks and keeps everything else (see
         * [ChargerPairingApproval]). The instance keeps the name it had.
         */
        fun pairAgain(baseUrl: String) = beginPairing(baseUrl, instanceStore.name())

        fun removeInstance(baseUrl: String) {
            AlertDialog.Builder(context)
                .setTitle(t(R.string.instance_remove_confirm_title, baseUrl))
                .setMessage(t(R.string.instance_remove_confirm_message))
                .setPositiveButton(t(R.string.instance_remove)) { _, _ ->
                    val removed = profileStore.listProfiles().filter { it.baseUrl == baseUrl }
                    removed.forEach { profile ->
                        profileStore.removeProfile(profile.localId)
                        ProfileCaches.forget(applicationContext, profile.localId)
                    }
                    // Whatever was bound to these chargers is "No charger" now: a person removed them,
                    // so there is no missing charger to warn about.
                    val manager = AppWidgetManager.getInstance(applicationContext)
                    val widgetIds = manager.getAppWidgetIds(ComponentName(applicationContext, PriceWidgetProvider::class.java)).toList()
                    WidgetChargerBindingStore.forContext(applicationContext).releaseRemovedProfiles(
                        removed.map { it.localId }.toSet(),
                        widgetIds + LauncherActivity.STANDALONE_SETTINGS_ID + widgetId
                    )
                    widgetIds.forEach { PriceWidgetProvider.update(applicationContext, manager, it) }
                    // Its homes go with it.
                    val siteStore = SiteProfileStore.forContext(applicationContext)
                    siteStore.list().filter { it.baseUrl == baseUrl }.forEach { siteStore.remove(it.localId) }
                    instanceStore.forget()
                    report = null
                    statusLine = t(R.string.instance_removed)
                    redraw()
                    onInstanceChanged()
                }
                .setNegativeButton(t(R.string.instance_pairing_cancel), null)
                .show()
        }

        fun showPairingCode() {
            body.addView(TextView(context).apply {
                text = code.orEmpty()
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            line(t(R.string.instance_pairing_enter_code))
            // Where to go, as something to press rather than something to know:
            pairingTarget?.let { target ->
                body.addView(button(t(R.string.instance_pairing_open_ha)) {
                    val page = Uri.parse("$target/config/integrations/dashboard")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, page)) }
                        .onFailure { statusLine = t(R.string.instance_pairing_open_failed); redraw() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
            if (statusLine.isNotEmpty()) line(statusLine)
            body.addView(button(t(R.string.instance_pairing_cancel)) {
                cancelled.set(true)
                pairing = false
                statusLine = ""
                redraw()
            })
        }

        redraw = {
            body.removeAllViews()
            val baseUrl = instanceStore.baseUrl()
            when {
                // Pairing again with the instance already held shows the code too, not the instance.
                pairing -> showPairingCode()
                baseUrl != null -> {
                    // The name discovery heard, with the address under it: the name is what a
                    // person recognises, and the address is still what tells two instances apart.
                    val instanceName = instanceStore.name()
                    row(
                        TextView(context).apply {
                            text = instanceName ?: baseUrl
                            textSize = 15f
                            setTextColor(dark)
                            typeface = Typeface.DEFAULT_BOLD
                        }.also { it.layoutParams = weight() },
                        button(t(R.string.instance_refresh)) { refresh() },
                        button(t(R.string.instance_remove)) { removeInstance(baseUrl) }
                    )
                    if (instanceName != null) line(baseUrl)
                    if (statusLine.isNotEmpty()) line(statusLine)
                    // A charger whose webhook Home Assistant no longer has: said, and offered, first.
                    val waiting = profileStore.listProfiles().filter { it.baseUrl == baseUrl && it.needsPairing }
                    if (waiting.isNotEmpty()) {
                        body.addView(pairAgainNotice(t(R.string.pair_again_needed)) { pairAgain(baseUrl) },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                .apply { topMargin = dp(4); bottomMargin = dp(6) })
                    }
                    // The homes (Home Assistant 3.0): each with its chargers, the one shown or a Show to switch to it.
                    body.showHomes(baseUrl) { pairAgain(baseUrl) }
                    report?.let { reconciled ->
                        val stale = reconciled.stale
                        if (stale.isNotEmpty()) {
                            line(t(R.string.instance_stale, stale.joinToString(", ") { chargerName(it) }))
                        }
                        if (reconciled.unprovisioned.isNotEmpty()) {
                            line(t(R.string.instance_unprovisioned, reconciled.unprovisioned.joinToString(", ") { it.name }))
                            body.addView(button(t(R.string.instance_pair_again)) { beginPairing(baseUrl, instanceStore.name()) })
                        }
                    }
                }
                else -> {
                    body.addView(button(t(R.string.home_assistant_log_in)) { findHomeAssistant() },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                    if (statusLine.isNotEmpty()) line(statusLine)
                    choices.forEach { found ->
                        body.addView(button(found.name + "  (" + found.baseUrl + ")") { beginPairing(found.baseUrl, found.name) },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
                    }
                    if (choices.isNotEmpty()) line(t(R.string.instance_several_found))
                    if (askForAddress) {
                        line(t(R.string.instance_none_found))
                        val field = EditText(context).apply {
                            hint = t(R.string.instance_address_label)
                            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                            setSingleLine(true)
                            setText(typedAddress)
                        }
                        body.addView(field)
                        body.addView(button(t(R.string.instance_use_address)) {
                            typedAddress = field.text?.toString()?.trim().orEmpty()
                            val candidates = HomeAssistantSettings.addressCandidates(typedAddress)
                            if (candidates.isNotEmpty()) {
                                beginPairing(candidates.first(), alternatives = candidates.drop(1))
                            } else {
                                statusLine = t(R.string.home_assistant_address_required)
                                redraw()
                            }
                        })
                    }
                }
            }
        }

        redraw()
        // "Pair again" pressed on a home's screen: the same, for the home's instance.
        val siteAsked = shell.pairAgainSite?.let { SiteProfileStore.forContext(applicationContext).get(it) }
        shell.pairAgainSite = null
        if (siteAsked != null && siteAsked.baseUrl.isNotBlank()) {
            pairAgain(siteAsked.baseUrl)
            return
        }
        // "Pair again" pressed on the charging screen: the handshake starts here at once, for that
        // charger's own instance.
        val pairAgainAsked = shell.pairAgainProfile?.let { profileStore.getProfile(it) }
        shell.pairAgainProfile = null
        when {
            pairAgainAsked != null && pairAgainAsked.baseUrl.isNotBlank() -> pairAgain(pairAgainAsked.baseUrl)
            // A connected instance shows what it has without being asked: the checklist is the answer
            // to "is it still there, and what does it have".
            instanceStore.baseUrl() != null -> refresh()
        }
    }

    /**
     * The homes of the instance at [baseUrl], under it: the home's name, its chargers (or that it has none: the house and
     * heat pages), a pair-again notice when Home Assistant no longer has its webhook, and Show, which makes the main
     * screen show it: its first charger, or the home itself when it has none.
     */
    private fun LinearLayout.showHomes(baseUrl: String, pairAgain: () -> Unit) {
        val sites = SiteProfileStore.forContext(applicationContext)
        val chargers = ChargerProfileStore.forContext(applicationContext)
        val bindings = WidgetChargerBindingStore.forContext(applicationContext)
        val resolved = se.sensnology.spotnav.widget.WidgetChargerResolver.resolve(WidgetSettings.load(context, widgetId), chargers)
        val shownSite = se.sensnology.spotnav.sites.MainSubject.site(resolved, chargers.listProfiles(), sites.list(), sites.activeSiteId())
        for (site in sites.list().filter { it.baseUrl == baseUrl }) {
            val inside = chargers.listProfiles().filter { it.baseUrl == baseUrl && it.siteId == site.siteId }
            val shown = if (inside.isEmpty()) shownSite?.localId == site.localId else resolved?.localId in inside.map { it.localId }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(TextView(context).apply {
                    text = t(R.string.site_home_title, site.name)
                    textSize = 14f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                }, weight())
                if (!shown && !site.needsPairing) addView(Button(context).apply {
                    text = t(R.string.site_show)
                    isAllCaps = false
                    setOnClickListener {
                        val first = inside.firstOrNull()
                        bindings.setBinding(widgetId, first?.localId)
                        sites.setActiveSiteId(if (first == null) site.localId else null)
                        shell.navigate(se.sensnology.spotnav.ui.Screen.MAIN)
                    }
                })
            })
            addView(TextView(context).apply {
                text = if (inside.isEmpty()) t(R.string.site_home_no_charger)
                else t(R.string.site_home_chargers, inside.joinToString(", ") { chargerName(it) })
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(6))
            })
            if (site.needsPairing) addView(pairAgainNotice(t(R.string.pair_again_needed)) { pairAgain() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
    }

    private companion object {
        const val HOME_ASSISTANT_REPOSITORY = "https://github.com/henrikekblad/spotnav-home-assistant"
    }
}
