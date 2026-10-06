package se.sensnology.spotnav.ui.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import se.sensnology.spotnav.BuildConfig
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.app.StoreAction
import se.sensnology.spotnav.app.StoreActions
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.CommitRoute
import se.sensnology.spotnav.ha.authority.WriteSubject
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.FormSaveOutcome
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.SaveEffect
import se.sensnology.spotnav.ha.settings.SettingsFormSession
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.ha.settings.SettingsSave
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.CatalogueRefresh
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.testmode.TestMode
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.HEADER_CONTROL_GAP_DP
import se.sensnology.spotnav.ui.common.authorityRefusalText
import se.sensnology.spotnav.ui.common.authorityStateNote
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chargerName
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.editOnOff
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.ui.common.label
import se.sensnology.spotnav.ui.common.settingsSaveTarget
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.ui.haSession
import se.sensnology.spotnav.vehicles.PairedVehicles
import se.sensnology.spotnav.widget.PriceWidgetProvider
import se.sensnology.spotnav.widget.WidgetChargerResolver
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The settings screen: the general, price, widget and Home Assistant cards. There is no Save for
 * the screen as a whole: what belongs to this phone (language, theme, the widget's options, and the
 * price settings while unpaired) applies the moment it changes, and what a paired charger owns is
 * changed in a dialog with its own Save and Cancel. Each card that has a life of its own is a
 * controller in this package; this class is the page they sit on.
 */
internal class SettingsScreen(shell: ScreenShell) : ScreenPart(shell) {
    fun show() {
        beginScreen()
        showPanel(t(R.string.settings))
        val old = WidgetSettings.load(context, widgetId)
        // The store's own link (see StoreAction), kept above the cards so it needs no trip to the
        // bottom of this long panel. It remains separate from the settings cards:
        addStoreAction(StoreActions.action)
        // The cards, in the order they are read, drawn with the same card pattern the main screen
        // uses:
        val generalCard = card(content, t(R.string.section_general), R.drawable.ic_settings)
        addGeneralSettings(generalCard.body)
        // The market, its resolution and its money: controls while unpaired, an overview while paired.
        val priceCard = card(content, t(R.string.section_electricity_price), R.drawable.ic_price_table)
        val settingsProfile = WidgetChargerResolver.resolve(
            old, ChargerProfileStore.forContext(applicationContext)
        )?.takeIf { it.configured }
        val settingsCache = ConfirmedSettingsStore.forContext(applicationContext)
        val settingsAuthority = AuthorityController(
            profileId = settingsProfile?.localId,
            screenGeneration = viewGeneration,
            // No coordinator: nothing on this screen seeds a record or polls a status -- it
            // resolves from the cache and writes through the settings transport alone.
            coordinator = null,
            cache = settingsCache,
            catalogue = { PriceMarkets.all },
            presentation = { HaPresentation(old.intervalMinutes) }
        )
        authorityController = settingsAuthority
        var confirmedRecord = settingsProfile?.let { settingsCache.confirmed(it.localId) }
        // The paired screen's commit path (set further down, where the session exists):
        var runFormSave: (SettingsFormValues, () -> Unit) -> Unit = { _, done -> done() }
        val authorityNoteView = TextView(context).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(8))
        }
        priceCard.body.addView(authorityNoteView)
        val paired = settingsProfile != null
        // The phone-only settings apply as they change: one reader of the controls, one store.
        var readInterval: () -> Int = { old.intervalMinutes }
        var readShowPlan: () -> Boolean = { old.showChargingPlan }
        fun applyLocal() {
            applyLocalSettings(
                paired = paired,
                price = null,
                intervalMinutes = readInterval(),
                showChargingPlan = readShowPlan()
            )
        }
        // Unpaired: this phone's own price as value rows, whose dialog applies at once. Paired: the
        // record's, whose dialog writes through the paired path (wired below).
        val pairedPrice = if (paired) PairedPriceCard(this) else null
        val localPrice = if (paired) null else LocalPriceCard(this)
        pairedPrice?.add(priceCard.body, old) { values, done -> runFormSave(values, done) }
        localPrice?.add(priceCard.body, current = { WidgetSettings.load(context, widgetId) }) { next ->
            storeIfChanged(WidgetSettings.load(context, widgetId), next)
        }
        // The resolution is this phone's own, paired or not: a row whose choice applies at once.
        var interval = old.intervalMinutes
        readInterval = { interval }
        addResolutionRow(priceCard.body, { interval }) { chosen -> interval = chosen; applyLocal() }
        // What the authority has to say about these values, written where they are read.
        var authorityNote: String? = settingsAuthority.seedFromConfirmedRecord()?.let { authorityStateNote(it) }
        fun showAuthorityNote(extra: String? = null) {
            // A paired price that cannot be written says why, next to the button it disables:
            val readOnly = if (paired && !priceControlsEnabled(true, settingsAuthority.authority)) {
                t(R.string.settings_paired_read_only)
            } else null
            authorityNoteView.text = listOfNotNull(authorityNote, readOnly, extra).joinToString(" ")
            // An empty note takes no room:
            authorityNoteView.visibility = if (authorityNoteView.text.isEmpty()) View.GONE else View.VISIBLE
        }
        showAuthorityNote()
        // The paired screen's commit path:
        var applyPairedRecord: (HaPlanningSettings?) -> Unit = {}
        // Widget appearance, not operation:
        val widgetCard = card(content, t(R.string.section_widget), R.drawable.ic_widget_grid)
        val display = addWidgetDisplayControls(widgetCard.body, old) { applyLocal() }
        if (display.offered) readShowPlan = { display.showPlan() }
        // The instance, everything it hands over, and where the integration comes from.
        // A paired charger's vehicles, charger, site and solar come after the phone's own settings
        // and right before the Home Assistant card, in the order the Home Assistant card has them.
        // The page is an overview: each area that can be changed from here opens its own dialog.
        val pairedCards = settingsProfile?.let { PairedSettingsCards(scope = this, parent = content, chargerName = chargerName(it)) }
        // Who hears about the charge: Home Assistant's Companion app choice and this phone's own check.
        val notificationsCard = settingsProfile?.let { PairedNotificationsCard(scope = this, parent = content) }
        val settingsGeneration = viewGeneration
        val homeAssistantCard = card(content, t(R.string.home_assistant), R.drawable.ic_card_charger)
        HomeAssistantSection(
            shell,
            onPlanDefaulted = { display.planOn() },
            // The paired cards above belong to the instance: the page is built again with or without them.
            onInstanceChanged = { show() }
        ).add(homeAssistantCard.body)
        if (settingsProfile != null && pairedCards != null) {
            val session = shell.haSession(settingsProfile)
            fun showNotifications() {
                val writable = priceControlsEnabled(true, settingsAuthority.authority)
                notificationsCard?.show(settingsCache.confirmed(settingsProfile.localId)?.notifications, writable)
                pairedCards.setIdentificationWritable(writable)
            }
            fun loadPaired() = session.peekDashboard { dashboard ->
                pairedCards.show(dashboard)
                if (dashboard != null && settingsCache.observeDashboard(settingsProfile.localId, dashboard) is ConfirmedSettingsStore.Merge.Stored) {
                    // A newer record (or one now stating its notifications) is what this screen writes against.
                    settingsAuthority.seedFromConfirmedRecord()
                    applyPairedRecord(settingsCache.confirmed(settingsProfile.localId))
                }
                showNotifications()
            }
            // A car's charge limit, written to the car through the integration; the cards read again after it.
            pairedCards.attachChargeLimit { vehicleId, percent, done ->
                session.setChargeLimit(vehicleId, percent) { answer ->
                    if (isDestroyed || viewGeneration != settingsGeneration) return@setChargeLimit
                    done(answer)
                    if (answer == ChargeLimit.Answer.Set) loadPaired()
                }
            }
            // Which cars can charge here and how the plugged-in one is found: the same settings write.
            pairedCards.attachIdentification { mode, vehicleIds, done ->
                when (val route = settingsAuthority.beginWrite(HaSettingsEdit.Identification(mode, vehicleIds))) {
                    is CommitRoute.Send -> session.updateSettings(route.expectedRevision, route.replacement) { answer ->
                        if (isDestroyed || viewGeneration != settingsGeneration) return@updateSettings
                        val outcome = settingsAuthority.onWriteAnswer(
                            WriteSubject(settingsProfile.localId, route.operation, route.expectedRevision), answer
                        )
                        if (outcome is WriteOutcome.Applied) applyPairedRecord(settingsCache.confirmed(settingsProfile.localId))
                        done(
                            when (answer) {
                                is SettingsUpdate.Outcome.Updated, is SettingsUpdate.Outcome.CommittedButReconcileFailed -> null
                                is SettingsUpdate.Outcome.Conflict -> t(R.string.authority_changed_elsewhere)
                                else -> authorityRefusalText(answer)
                            }
                        )
                        loadPaired()
                    }
                    is CommitRoute.Refused -> done(t(R.string.authority_refused_invalid))
                    CommitRoute.ReadOnly, CommitRoute.LocalSave -> done(t(R.string.settings_paired_read_only))
                }
            }
            notificationsCard?.attach(
                save = { targets, events, done ->
                    when (val route = settingsAuthority.beginWrite(HaSettingsEdit.Notifications(targets, events))) {
                        is CommitRoute.Send -> session.updateSettings(route.expectedRevision, route.replacement) { answer ->
                            if (isDestroyed || viewGeneration != settingsGeneration) return@updateSettings
                            val outcome = settingsAuthority.onWriteAnswer(
                                WriteSubject(settingsProfile.localId, route.operation, route.expectedRevision), answer
                            )
                            if (outcome is WriteOutcome.Applied) applyPairedRecord(settingsCache.confirmed(settingsProfile.localId))
                            showNotifications()
                            done(
                                when (answer) {
                                    is SettingsUpdate.Outcome.Updated, is SettingsUpdate.Outcome.CommittedButReconcileFailed -> null
                                    is SettingsUpdate.Outcome.Conflict -> t(R.string.authority_changed_elsewhere)
                                    else -> authorityRefusalText(answer)
                                }
                            )
                        }
                        is CommitRoute.Refused -> done(t(R.string.authority_refused_invalid))
                        CommitRoute.ReadOnly, CommitRoute.LocalSave -> done(t(R.string.settings_paired_read_only))
                    }
                },
                requestPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), LocalNotifications.PERMISSION_REQUEST)
                    }
                }
            )
            shell.onNotificationPermission = { notificationsCard?.permissionAnswered() }
            showNotifications()
            pairedCards.attachWrites(
                vehicle = { vehicleId, changes, done ->
                    session.updateVehicle(vehicleId, changes) { outcome ->
                        done(outcome)
                        if (PairedVehicles.feedback(outcome).reload) loadPaired()
                    }
                },
                site = { request, done ->
                    session.updateSite(request) { outcome ->
                        done(outcome)
                        if (PairedVehicles.feedback(outcome).reload) loadPaired()
                    }
                },
                priority = { expected, chosen, done ->
                    session.updateChargerPriority(expected, chosen) { outcome ->
                        done(outcome)
                        if (PairedVehicles.feedback(outcome).reload) loadPaired()
                    }
                }
            )
            loadPaired()
        }
        // Revalidate the catalogue off the main thread, as the rest of this screen's work is.
        ioExecutor.execute {
            val result = AreaCatalogue.refreshIfDue(applicationContext)
            if (result is CatalogueRefresh.Updated) {
                runOnUiThread {
                    if (isDestroyed || settingsGeneration != viewGeneration) return@runOnUiThread
                    localPrice?.show()
                    pairedPrice?.show(confirmedRecord, priceControlsEnabled(true, settingsAuthority.authority))
                }
            }
        }


        /** Render one confirmed record into the paired controls. */
        applyPairedRecord = { record ->
            confirmedRecord = record
            // What may be edited is the *state's* answer, not the form's: a pending, offline or
            // conflicting authority shows the confirmed values and offers no write.
            pairedPrice?.show(record, priceControlsEnabled(true, settingsAuthority.authority))
        }
        // The seeded state's own answer decides what may be edited straight away: a cached but
        // uncheckable record offers no write even before the first status answer arrives.
        applyPairedRecord(confirmedRecord)

        /** The record one answer left standing, and what the answer was. */
        fun renderAnswer(effect: SaveEffect.RenderAnswer, values: SettingsFormValues) {
            when (val outcome = effect.outcome) {
                WriteOutcome.Stale -> Unit
                is WriteOutcome.Reported -> {
                    applyPairedRecord(confirmedRecord)
                    authorityNote = listOfNotNull(
                        authorityStateNote(outcome.authority ?: settingsAuthority.authority!!),
                        authorityRefusalText(effect.answer)
                    ).joinToString(" ")
                }
                is WriteOutcome.Applied -> {
                    // The exact record the charger returned, which the form shows from here on.
                    applyPairedRecord(settingsCache.confirmed(effect.decision.profileId))
                    refreshWidget()
                    val answerNote = when (effect.answer) {
                        is SettingsUpdate.Outcome.Updated -> t(R.string.authority_saved)
                        is SettingsUpdate.Outcome.CommittedButReconcileFailed ->
                            t(R.string.authority_committed_unreconciled)
                        is SettingsUpdate.Outcome.Conflict -> t(R.string.authority_changed_elsewhere)
                        else -> authorityRefusalText(effect.answer)
                    }
                    authorityNote = listOfNotNull(
                        authorityStateNote(outcome.authority), answerNote
                    ).joinToString(" ")
                }
            }
            showAuthorityNote()
        }

        /**
         * The screen's side of one press: the local saves, the renders and the finish, in the order
         * the session's effects name them (see [SaveEffect]).
         */
        fun performSaveEffects(effects: List<SaveEffect>, save: FormSaveOutcome, values: SettingsFormValues) {
            effects.forEach { effect ->
                when (effect) {
                    // A contract this app cannot read leaves the dialog's values with the phone:
                    is SaveEffect.PersistLocal -> if (!effect.paired) storeDialogValues(values)
                    // The phone's own store has already redrawn the widget.
                    SaveEffect.Publish -> Unit
                    is SaveEffect.RenderState -> {
                        applyPairedRecord(confirmedRecord)
                        authorityNote = when {
                            effect.abandoned -> t(R.string.settings_save_not_applied)
                            effect.decision is SettingsSave.Refused -> t(R.string.authority_refused_invalid)
                            effect.decision is SettingsSave.ReadOnly -> t(R.string.settings_paired_read_only)
                            else -> authorityNote
                        }
                        showAuthorityNote()
                    }
                    is SaveEffect.RenderAnswer -> renderAnswer(effect, values)
                }
            }
        }

        /**
         * One press of Save, off the main thread: the admission, the one request, the one answer.
         */
        runFormSave = { values, done ->
            ioExecutor.execute {
                val save = SettingsFormSession.save(settingsAuthority, values) { profileId -> settingsSaveTarget(context, profileId) }
                runOnUiThread {
                    if (isDestroyed || viewGeneration != settingsGeneration) return@runOnUiThread
                    performSaveEffects(SettingsFormSession.effects(save), save, values)
                    done()
                }
            }
        }

        // Debug builds only, and last but one: a developer tool, below everything a user actually
        // came here for. A release build compiles the no-op twin.
        TestMode.addSettingsSection(context, content)
        addAppFooter()
    }

    /** The widget's own redraw, as the old Save did it. */
    private fun refreshWidget() {
        if (widgetId > 0) PriceWidgetProvider.update(context, AppWidgetManager.getInstance(context), widgetId)
    }

    /**
     * Apply the phone's own controls now: store what [ImmediateSettings] makes of them, redraw the
     * widget, and name any figure that was refused (its stored value is kept).
     */
    private fun applyLocalSettings(paired: Boolean, price: PriceSettings?, intervalMinutes: Int, showChargingPlan: Boolean): Set<FigureField> {
        val current = WidgetSettings.load(context, widgetId)
        val draft = LocalSettingsDraft(
            area = price?.area() ?: current.area,
            vat = price?.vat() ?: current.vat,
            tax = price?.tax() ?: current.tax,
            taxText = price?.taxText() ?: "",
            transfer = price?.transfer() ?: current.transfer,
            transferText = price?.transferText() ?: "",
            intervalMinutes = intervalMinutes,
            showChargingPlan = showChargingPlan
        )
        // With no price controls at hand only the phone-side fields change (the price is kept as stored).
        val result = ImmediateSettings.apply(current, draft, paired || price == null)
        storeIfChanged(current, result.settings)
        return result.invalid
    }

    /** The paired dialog's values, stored as the phone's own when the paired contract cannot be used. */
    private fun storeDialogValues(values: SettingsFormValues) {
        val current = WidgetSettings.load(context, widgetId)
        val draft = LocalSettingsDraft(
            area = values.areaId, vat = values.vat, tax = values.tax,
            taxText = values.taxFigure?.toString().orEmpty(),
            transfer = values.transfer, transferText = values.transferFigure?.toString().orEmpty(),
            intervalMinutes = current.intervalMinutes, showChargingPlan = current.showChargingPlan
        )
        storeIfChanged(current, ImmediateSettings.apply(current, draft, paired = false).settings)
    }

    private fun storeIfChanged(current: WidgetSettings, next: WidgetSettings) {
        if (next == current) return
        WidgetSettings.save(context, widgetId, next)
        refreshWidget()
    }

    /** The store's link: the one action here that leaves the app on purpose. */
    private fun addStoreAction(action: StoreAction) {
        val label = t(action.labelRes)
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke(dp(1), muted)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { openStoreLink(action) }
            addView(ImageView(context).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(action.iconTint ?: accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                // Theme-readable in both themes; the glyph above is decoration.
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
            }, weight())
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_external_link)
                imageTintList = ColorStateList.valueOf(muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })
    }

    /** Open the store's link, or say so if this device cannot. */
    private fun openStoreLink(action: StoreAction) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
        } catch (noHandler: ActivityNotFoundException) {
            reportStoreLinkUnavailable(action)
        } catch (forbidden: SecurityException) {
            reportStoreLinkUnavailable(action)
        }
    }

    private fun reportStoreLinkUnavailable(action: StoreAction) {
        Toast.makeText(context, t(action.unavailableRes), Toast.LENGTH_LONG).show()
    }

    // Settings-tab pieces (wired together by show)   Same shape as the pieces in
    // `ChargingScreen.show`:

    /**
     * The "General" card: language and theme. Both persist themselves and `recreate()` the activity
     * the moment they change, so there is nothing for the caller to read back -- the piece has no
     * handle. The card's own header carries the title, which is why nothing here adds one.
     */
    private fun addGeneralSettings(parent: LinearLayout) {
        val languages = AppLanguageSettings.choices
        val language = languages.indexOfFirst { it.code == AppLanguageSettings.selected(context) }.coerceAtLeast(0)
        settingRow(parent, t(R.string.language), t(languages[language].label)) {
            chooseOne(t(R.string.language), languages.map { t(it.label) }, language) { index, done ->
                done(null)
                AppLanguageSettings.save(context, languages[index].code)
                activity.recreate()
            }
        }
        val themeModes = listOf(AppThemeSettings.SYSTEM, AppThemeSettings.LIGHT, AppThemeSettings.DARK)
        val themeNames = listOf(t(R.string.system), t(R.string.light), t(R.string.dark))
        val theme = themeModes.indexOf(AppThemeSettings.mode(context)).coerceAtLeast(0)
        settingRow(parent, t(R.string.theme), themeNames[theme]) {
            chooseOne(t(R.string.theme), themeNames, theme) { index, done ->
                done(null)
                AppThemeSettings.save(context, themeModes[index])
                activity.recreate()
            }
        }
    }

    /** The resolution row: this phone's own, applied as soon as it is chosen. */
    private fun addResolutionRow(parent: LinearLayout, current: () -> Int, onChosen: (Int) -> Unit) {
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(holder)
        val values = listOf(PresentationIntervals.QUARTER_HOUR_MINUTES, PresentationIntervals.HOUR_MINUTES)
        val names = listOf(t(R.string.quarter), t(R.string.hour))
        fun paint() {
            holder.removeAllViews()
            val index = values.indexOf(current()).coerceAtLeast(0)
            settingRow(holder, t(R.string.resolution), names[index]) {
                chooseOne(t(R.string.resolution), names, index) { chosen, done -> done(null); onChosen(values[chosen]); paint() }
            }
        }
        paint()
    }

    /** The widget's option as a value row ("On"/"Off"), applied as soon as it is chosen. */
    private fun addWidgetDisplayControls(
        parent: LinearLayout,
        settings: WidgetSettings,
        onChange: () -> Unit
    ): WidgetDisplayControls {
        // Which widget these settings change: the one this screen was opened from. Opened from the app
        // with no widget placed (or none chosen), there is nothing to change, and the section says so.
        parent.addView(TextView(context).apply {
            text = t(if (widgetId > 0) R.string.widget_settings_this else R.string.widget_settings_none)
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(4))
        })
        if (widgetId <= 0) return WidgetDisplayControls(offered = false, showPlan = { settings.showChargingPlan }, planOn = {})
        var on = settings.showChargingPlan
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(holder)
        fun paint() {
            holder.removeAllViews()
            settingRow(holder, t(R.string.widget_plan), t(if (on) R.string.site_on else R.string.site_off)) {
                editOnOff(t(R.string.widget_plan), on) { chosen, done ->
                    done(null)
                    on = chosen
                    paint()
                    onChange()
                }
            }
        }
        paint()
        return WidgetDisplayControls(offered = true, showPlan = { on }, planOn = { on = true; paint() })
    }

    private fun addAppFooter() {
        val issueText = t(R.string.report_issue)
        val footer = SpannableString("${t(R.string.version_label, BuildConfig.VERSION_NAME)} · $issueText").apply {
            val start = toString().indexOf(issueText)
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPOTNAV_ISSUES)))
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = accent
                    ds.isUnderlineText = true
                }
            }, start, start + issueText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        content.addView(TextView(context).apply {
            text = footer
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(12))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0x00000000
        })
    }

    private companion object {
        const val SPOTNAV_ISSUES = "https://github.com/henrikekblad/spotnav/issues"
    }
}

/** What [addWidgetDisplayControls] hands back: whether the option is offered, its value, and a way to turn it on. */
internal class WidgetDisplayControls(val offered: Boolean, val showPlan: () -> Boolean, val planOn: () -> Unit)
