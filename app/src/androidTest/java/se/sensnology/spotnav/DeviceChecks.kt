package se.sensnology.spotnav

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.app.LauncherActivity
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.LegacyPriceUser
import se.sensnology.spotnav.prices.SafeXml
import se.sensnology.spotnav.prices.entsoe.AndroidKeyStoreBox
import se.sensnology.spotnav.prices.entsoe.EntsoeKey
import se.sensnology.spotnav.prices.entsoe.EntsoeKeyStore
import se.sensnology.spotnav.prices.entsoe.KeyState
import se.sensnology.spotnav.testing.PriceParity
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * Checks that only a device can make, run in the app's own process with `am instrument` (no test library):
 *
 * - `-e mode parity`: Android's XML and JSON implementations read the vendored ENTSO-E and ECB captures and
 *   build the SE4 day document exactly as the JVM does (the lines of `PriceParityTest`; design step A5);
 * - `-e mode keystore-save`, then `-e mode keystore-load` in a new process: the AndroidKeyStore box seals and
 *   opens a key, and a key saved by one process opens in the next (design 3.6);
 * - `-e mode seed`: stored state for the screenshots of the key's screens (a dummy key and its state, the
 *   one-time explanation's flags, language, theme, area, and a relay area list with a Great Britain region).
 *
 * Prints `PASS`/`FAIL` lines and finishes with RESULT_OK only when every check passed. Never prints a key.
 */
class DeviceChecks : Instrumentation() {
    private var arguments: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = StringBuilder()
        var ok = true
        fun check(name: String, passed: Boolean, detail: String = "") {
            report.append(if (passed) "PASS " else "FAIL ").append(name).append(if (detail.isEmpty()) "" else ": $detail").append('\n')
            ok = ok && passed
        }
        try {
            when (val mode = arguments.getString("mode", "parity")) {
                "parity" -> parity(::check)
                "keystore-save" -> keystoreSave(::check)
                "keystore-load" -> keystoreLoad(::check)
                "seed" -> seed(::check)
                else -> check("mode", false, "unknown mode $mode")
            }
        } catch (error: Throwable) {
            check("run", false, error.javaClass.name)
        }
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(REPORT_KEY_STREAMRESULT, report.toString())
        })
    }

    private fun parity(check: (String, Boolean, String) -> Unit) {
        val own = context.assets
        fun asset(path: String) = own.open(path).bufferedReader().use { it.readText() }
        val areas = targetContext.assets.open(AreaCatalogue.BUNDLED_ASSET).bufferedReader().use { it.readText() }
        val actual = PriceParity.lines(areas, { asset("relay-upstream/$it") }, asset("relay-golden/v1_day_SE4.json"))
        val expected = asset("device-parity/price_parity.txt").trimEnd('\n').split('\n')
        check("parity line count", actual.size == expected.size, "${actual.size} vs ${expected.size}")
        actual.zip(expected).forEachIndexed { index, (got, want) ->
            check("parity ${want.substringBefore(' ')}", got == want, if (got == want) "" else "line $index device=[$got] jvm=[$want]")
        }
        check("SE4 day document is the relay's own bytes", actual.last().contains("golden=true"), "")
        check("a document with a DOCTYPE is refused", SafeXml.root(ENTITY_BODY) == null, "")
        check("a plain document is read", SafeXml.root("<a><b>1</b></a>")?.let { SafeXml.childText(it, "b") } == "1", "")
    }

    private fun keystoreSave(check: (String, Boolean, String) -> Unit) {
        val sealed = AndroidKeyStoreBox.seal(SENTINEL)
        check("seal", sealed != null, "")
        check("sealed text hides the key", sealed?.contains(SENTINEL) == false, "")
        check("open", sealed?.let { AndroidKeyStoreBox.open(it) } == SENTINEL, "")
        check("a sealed text opens once per seal (fresh IV)", sealed != AndroidKeyStoreBox.seal(SENTINEL), "")
        val tampered = sealed?.let { it.dropLast(2) + if (it.endsWith("AA")) "BB" else "AA" }
        check("a changed text does not open", tampered?.let { AndroidKeyStoreBox.open(it) } == null, "")
        val store = keyStore()
        store.remove()
        check("save", store.save(EntsoeKey(SENTINEL), KeyState.OK), "")
        check("state after save", store.state() == KeyState.OK, store.state().name)
        check("masked", store.masked() == "••••" + SENTINEL.takeLast(4), "")
        val raw = SharedPreferencesKeyValueStore(targetContext, CHECK_PREFS).getString(EntsoeKeyStore.KEY_SEALED).orEmpty()
        check("stored text hides the key", raw.isNotEmpty() && !raw.contains(SENTINEL), "")
        flush(CHECK_PREFS)
    }

    /**
     * Wait for the stores' queued writes: the app's stores write with `apply()`, which an activity's pause
     * flushes, while `am instrument` ends the process as soon as this finishes.
     */
    private fun flush(vararg names: String) {
        for (name in names) targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
    }

    private fun keystoreLoad(check: (String, Boolean, String) -> Unit) {
        val store = keyStore()
        check("a key saved by another process opens", store.key() == EntsoeKey(SENTINEL), "")
        check("its state survived", store.state() == KeyState.OK, store.state().name)
        store.remove()
        check("removed", store.key() == null && store.state() == KeyState.NONE, "")
        flush(CHECK_PREFS)
    }

    /** Stored state for one screenshot; the app is started afresh after it. */
    private fun seed(check: (String, Boolean, String) -> Unit) {
        val app = targetContext
        arguments.getString("lang")?.let { AppLanguageSettings.save(app, it) }
        arguments.getString("theme")?.let { AppThemeSettings.save(app, it) }
        val flags = SharedPreferencesKeyValueStore(app, LegacyPriceUser.PREFS)
        arguments.getString("legacy")?.let { flags.putString(LegacyPriceUser.KEY_LEGACY, it) }
        when (arguments.getString("why")) {
            "true" -> LegacyPriceUser.markWhyShown(flags)
            "false" -> flags.remove(LegacyPriceUser.KEY_WHY_SHOWN)
        }
        val catalogue = SharedPreferencesKeyValueStore(app, AreaCatalogue.PREFS)
        when (arguments.getString("catalogue")) {
            "relay-gb" -> {
                catalogue.putString(AreaCatalogue.KEY_LAST_GOOD_V2, context.assets.open("relay-v2/areas-v2.json").bufferedReader().use { it.readText() })
                catalogue.putString(AreaCatalogue.KEY_REFRESHED_AT, System.currentTimeMillis().toString())
            }
            "bundled" -> {
                catalogue.remove(AreaCatalogue.KEY_LAST_GOOD_V2)
                catalogue.remove(AreaCatalogue.KEY_LAST_GOOD)
                catalogue.putString(AreaCatalogue.KEY_REFRESHED_AT, System.currentTimeMillis().toString())
            }
        }
        AreaCatalogue.load(app)
        arguments.getString("area")?.let { area ->
            val id = LauncherActivity.STANDALONE_SETTINGS_ID
            WidgetSettings.save(app, id, WidgetSettings.load(app, id).copy(area = area))
        }
        val keys = EntsoeKeyStore.forContext(app)
        when (val state = arguments.getString("key")) {
            null -> Unit
            "none" -> keys.remove()
            else -> keys.save(EntsoeKey(DUMMY_KEY), KeyState.valueOf(state.uppercase()))
        }
        flush(
            "app_language", "app_appearance", "widget_settings", LegacyPriceUser.PREFS, AreaCatalogue.PREFS, EntsoeKeyStore.PREFS
        )
        check("seeded", true, "key=${keys.state()} legacy=${flags.getString(LegacyPriceUser.KEY_LEGACY)} " +
            "why=${LegacyPriceUser.whyShown(flags)} area=${WidgetSettings.load(app, LauncherActivity.STANDALONE_SETTINGS_ID).area}")
    }

    private fun keyStore() = EntsoeKeyStore(SharedPreferencesKeyValueStore(targetContext, CHECK_PREFS), AndroidKeyStoreBox)

    private companion object {
        /** Not a key: a sentinel of the length of one, never sent anywhere. */
        const val SENTINEL = "00000000-sentinel-device-check-0000"

        /** The screenshots' stand-in: no ENTSO-E key, never sent anywhere (the emulator is offline then). */
        const val DUMMY_KEY = "dummy-0000-0000-0000-screenshot-a1b2"
        const val CHECK_PREFS = "device_check_entsoe_key"

        /** An external entity the parser must never get to resolve. */
        const val ENTITY_BODY = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><a><b>&e;</b></a>"
    }
}
