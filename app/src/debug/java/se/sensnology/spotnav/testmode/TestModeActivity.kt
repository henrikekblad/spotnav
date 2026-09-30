package se.sensnology.spotnav.testmode

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ui.WidgetConfigActivity
import se.sensnology.spotnav.widget.WidgetChargerBindingStore
import java.util.concurrent.Executors

/** The test-mode screen — **debug builds only**. */
class TestModeActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var addressField: EditText
    private lateinit var fallback: LinearLayout
    private lateinit var enterButton: Button
    private lateinit var leaveButton: Button

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var servers: List<MockCatalogue.Server> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeSettings.apply(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(heading(getString(R.string.test_mode_title), 22f))
        content.addView(body(getString(R.string.test_mode_intro)))

        // One action, not a selection: discovery works, so entering is "take what is out there" and
        // the choice of scenario is made afterwards, on the charging screen, where it can be made
        // with something on screen.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        enterButton = Button(this).apply {
            text = getString(R.string.test_mode_enter)
            isAllCaps = false
            setOnClickListener { enter() }
        }
        leaveButton = Button(this).apply {
            text = getString(R.string.test_mode_leave)
            isAllCaps = false
            setOnClickListener { leave() }
        }
        actions.addView(enterButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(leaveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        status = body("")
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        content.addView(Button(this).apply {
            text = getString(R.string.test_mode_discover)
            isAllCaps = false
            setOnClickListener { discover() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })

        // The typed address is the fallback, and the failure case is the only case that needs an
        // input: a host firewall can drop the broadcast, and then there is nothing else the user
        // could do.
        fallback = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(heading(getString(R.string.test_mode_manual_label), 15f))
            addressField = EditText(this@TestModeActivity).apply {
                hint = getString(R.string.test_mode_manual_hint)
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine(true)
            }
            addView(addressField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(this@TestModeActivity).apply {
                text = getString(R.string.test_mode_manual_fetch)
                isAllCaps = false
                setOnClickListener { fetchTypedAddress() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        }
        content.addView(fallback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        render()
        // Discovery runs on open: it is the one thing entering needs, and it is the only step that
        // could have failed.
        discover()
    }

    override fun onDestroy() {
        // A discovery pass may still be waiting on its socket; it is harmless if it finishes, and
        // this keeps a developer's phone from collecting a thread per visit to this screen.
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // finding servers

    private fun discover() {
        status.text = getString(R.string.test_mode_discovering)
        enterButton.isEnabled = false
        ioExecutor.execute {
            val found = runCatching { MockDiscovery.discover() }.getOrDefault(emptyList())
            runOnUiThread {
                servers = merge(found, servers)
                status.text = if (servers.isEmpty()) getString(R.string.test_mode_none_found)
                    else getString(R.string.test_mode_found, servers.size)
                // The failure case is the only one that needs an input.
                fallback.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
                render()
            }
        }
    }

    private fun fetchTypedAddress() {
        val address = addressField.text?.toString()?.trim().orEmpty()
        status.text = getString(R.string.test_mode_discovering)
        enterButton.isEnabled = false
        ioExecutor.execute {
            val result = runCatching { MockDiscovery.catalogue(address) }
            runOnUiThread {
                when {
                    result.isFailure -> status.text = getString(R.string.test_mode_unreachable, address)
                    result.getOrNull() == null -> status.text = getString(R.string.test_mode_not_a_mock, address)
                    result.getOrNull()!!.isEmpty() -> status.text = getString(R.string.test_mode_no_scenarios, address)
                    else -> {
                        val server = MockCatalogue.Server(address.trimEnd('/'), result.getOrNull()!!)
                        servers = merge(listOf(server), servers)
                        status.text = getString(R.string.test_mode_found, servers.size)
                    }
                }
                render()
            }
        }
    }

    /**
     * The freshly found servers first, and any earlier one whose address has not just answered
     * again kept as it was: a second discovery pass that only hears one of two machines must not
     * drop the other.
     */
    private fun merge(found: List<MockCatalogue.Server>, existing: List<MockCatalogue.Server>): List<MockCatalogue.Server> {
        val byAddress = LinkedHashMap<String, MockCatalogue.Server>()
        found.forEach { byAddress[it.baseUrl] = it }
        existing.forEach { byAddress.putIfAbsent(it.baseUrl, it) }
        return byAddress.values.toList()
    }

    // the one thing the screen has to keep in step

    private fun render() {
        // One live button at a time: test mode is either something to enter or something to leave,
        // never both. This is also what re-enables Enter once discovery has answered.
        val inTestMode = TestModeSnapshotStore.forContext(this).exists()
        enterButton.isEnabled = TestModePlan.canEnter(inTestMode)
        leaveButton.isEnabled = TestModePlan.canLeave(inTestMode)
    }

    // entering and leaving

    private fun enter() {
        val store = ChargerProfileStore.forContext(applicationContext)
        val snapshots = TestModeSnapshotStore.forContext(this)
        val outcome = TestModePlan.enter(
            snapshotPresent = snapshots.exists(),
            servers = servers,
            newLocalId = { ChargerProfileStore.newLocalId() }
        )
        if (outcome is TestModePlan.Enter.AlreadyActive) {
            // The guard, in the one place a user could trip it: entering again would put the *test*
            // profiles in the slot the real ones occupy.
            toast(getString(R.string.test_mode_already_on))
            return
        }
        val activated = (outcome as TestModePlan.Enter.Activate)
        // Snapshot first, and nothing else if it refuses: this is the write that cannot be undone
        // from memory.
        if (!snapshots.snapshot()) {
            toast(getString(R.string.test_mode_already_on))
            return
        }
        store.listProfiles().forEach { store.removeProfile(it.localId) }
        activated.profiles.forEach { store.upsertProfile(it) }
        store.setActiveProfileId(TestModePlan.activeProfileId(activated.profiles))
        // The profile store's active id is not what the charging screen reads: it resolves *this
        // widget's own binding*, so that is what has to point at the mocked charger -- or at
        // nothing, for "No Home Assistant configured", which is what makes that scenario the real
        // first-run state rather than an accident.
        widgetId().let { widget ->
            if (widget != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetChargerBindingStore.forContext(applicationContext).setBinding(widget, activated.boundProfileId)
            }
        }
        toast(getString(R.string.test_mode_on))
        restartConfigActivity()
    }

    private fun leave() {
        if (!TestModeSnapshotStore.forContext(this).restore()) {
            toast(getString(R.string.test_mode_nothing_to_restore))
            return
        }
        toast(getString(R.string.test_mode_off))
        restartConfigActivity()
    }

    /**
     * Back to the screen this was opened from, as a fresh instance: the chargers on it have just
     * changed underneath it, and a widget-config flow must keep its widget id and its "still
     * editing" flag.
     */
    private fun restartConfigActivity() {
        startActivity(Intent(this, WidgetConfigActivity::class.java).replaceExtras(intent))
        finish()
    }

    /** Which widget's config screen opened this, or invalid when unknown. */
    private fun widgetId(): Int =
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    // small view helpers

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textPrimary())
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(textSecondary())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** The same two text colours the config screen uses, chosen the same way. */
    private fun textPrimary(): Int =
        if (AppThemeSettings.isDark(this)) 0xFFF5F7FA.toInt() else 0xFF192029.toInt()

    private fun textSecondary(): Int =
        if (AppThemeSettings.isDark(this)) 0xFFA8B1BC.toInt() else 0xFF667180.toInt()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Open the screen, carrying the caller's extras so it can restart it. */
        fun launch(activity: Activity) = Intent(activity, TestModeActivity::class.java).replaceExtras(activity.intent)

        /**
         * The banner's way out: put the real chargers back, then reopen the config screen so it
         * shows them rather than the mocked ones.
         */
        fun leaveAndRestart(activity: Activity) {
            if (!TestModeSnapshotStore.forContext(activity).restore()) return
            activity.startActivity(Intent(activity, WidgetConfigActivity::class.java).replaceExtras(activity.intent))
            activity.finish()
        }
    }
}
