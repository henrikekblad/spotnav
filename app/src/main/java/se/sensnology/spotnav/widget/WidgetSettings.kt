package se.sensnology.spotnav.widget

import android.content.Context
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.planning.FiscalArithmetic
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.planning.fiscalFigure
import se.sensnology.spotnav.prices.AreaSelection
import se.sensnology.spotnav.prices.IncludedPart
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint

data class WidgetSettings(
    val area: String = "SE4",
    val vat: Boolean = false,
    val tax: Boolean = false,
    val transfer: Boolean = false,
    val taxMinorUnit: Double = NO_SUGGESTION,
    val gridFeeMinorUnit: Double = NO_SUGGESTION,
    val chargingPhases: Int = 3,
    val chargingAmps: Int = 10,
    val consumptionKwhPerMil: Double = 2.0,
    val chargingKwh: Double = 20.0,
    // What drives the plan: the energy control, or a target state of charge.
    val driver: PlanDriver = PlanDriver.KWH,
    val maxChargingPeriods: Int = 1,
    val showChargingPlan: Boolean = false,
    val useDepartureTime: Boolean = true,
    val departureHour: Int = 8,
    val departureMinute: Int = 0,
    // ChargerProfile.localId, or null if unbound; set only by WidgetChargerBindingStore.
    val chargerProfileId: String? = null
) {
    /** The VAT rate the checkbox applies: the area's own figure, or 0 when the catalogue has none. */
    val effectiveVatPercent: Double get() = PriceMarkets.find(area)?.vatPercent ?: 0.0

    /**
     * The fiscal parts the area's published price already contains (contract v2's `included`): they are
     * locked as included in the price and nothing is added for them, whatever this record's own flags
     * say. The flags themselves are kept, so another area still gets the person's own choice.
     */
    val included: Set<IncludedPart> get() = PriceMarkets.find(area)?.included.orEmpty()

    /** Whether VAT is added: chosen, and not already in the price. */
    val vatAdded: Boolean get() = vat && IncludedPart.VAT !in included

    /** Whether the electricity tax is added: chosen, and not already in the price. */
    val taxAdded: Boolean get() = tax && IncludedPart.TAX !in included

    /** Whether the grid fee is added: chosen, and not already in the price. */
    val transferAdded: Boolean get() = transfer && IncludedPart.GRID_FEE !in included

    /**
     * One relay price (local major unit per kWh) into what this widget displays, via [FiscalArithmetic].
     * Deliberately not built on [PlanningInputs], so a record with unfilled plan inputs still works.
     */
    fun apply(localMajorPerKwh: Double): Double = FiscalArithmetic.apply(
        localMajorPerKwh,
        vat = component(vatAdded, effectiveVatPercent),
        tax = component(taxAdded, taxMinorUnit),
        transfer = component(transferAdded, gridFeeMinorUnit)
    )

    /** One component from this record's own figure, with no figure stated where none is usable. */
    private fun component(enabled: Boolean, figure: Double): FiscalInput =
        FiscalInput(enabled = enabled, overrideValue = fiscalFigure(figure), effectiveValue = fiscalFigure(figure))

    companion object {
        /** Pre-fill for an area the relay publishes no figure for: nothing, never a borrowed figure. */
        const val NO_SUGGESTION = 0.0

        private const val PREFS = "widget_settings"

        fun isConfigured(context: Context, id: Int): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("$id.area")

        /**
         * A widget that was placed but never saved gets the defaults its form would show, stored once, so
         * leaving the configuration screen (or a launcher that skips it) keeps a working widget. Saved
         * settings are never touched. True when the defaults were stored by this call.
         */
        fun seedDefaults(context: Context, id: Int): Boolean = seedDefaults(
            configured = isConfigured(context, id),
            defaults = { load(context, id) },
            persist = { save(context, id, it) }
        )

        /** The decision alone: nothing is stored for a configured widget or for defaults without an area. */
        internal fun seedDefaults(configured: Boolean, defaults: () -> WidgetSettings, persist: (WidgetSettings) -> Unit): Boolean {
            if (configured) return false
            val seeded = defaults()
            // No catalogue to take a default area from: storing it would mark the widget configured with nothing to draw.
            if (seeded.area.isBlank()) return false
            persist(seeded)
            return true
        }

        fun load(context: Context, id: Int): WidgetSettings {
            // Lazy default of the charger binding is decided and persisted here; see WidgetChargerBindingStore.
            val profileId = WidgetChargerBindingStore.forContext(context)
                .binding(id) { ChargerProfileStore.forContext(context).getActiveProfileId() }
                .chargerProfileId
            // The plan line defaults on for a widget bound to a charger, once, as its binding is first made.
            if (profileId != null && !prefs(context).contains("$id.showChargingPlan")) {
                prefs(context).edit().putBoolean("$id.showChargingPlan", true).apply()
            }
            return read(context, id, profileId)
        }

        /** The stored energy: a float now, a whole number in what an older version saved. */
        private fun chargingKwh(p: android.content.SharedPreferences, key: String): Double =
            try {
                p.getFloat(key + "chargingKwh", 20f).toDouble()
            } catch (_: ClassCastException) {
                p.getInt(key + "chargingKwh", 20).toDouble()
            }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /**
         * A widget just bound to a charger it had none of: the plan line goes on unless the person has
         * ever changed that option on this widget.
         */
        fun planDefaultOnBinding(context: Context, id: Int) {
            val p = prefs(context)
            if (p.getBoolean("$id.showChargingPlanChosen", false)) return
            p.edit().putBoolean("$id.showChargingPlan", true).apply()
        }

        /**
         * Stored settings with the binding read as stored, never defaulted; `null` for a widget whose
         * binding was never decided. For callers that must not decide a binding by looking.
         */
        internal fun loadStored(context: Context, id: Int): WidgetSettings? =
            WidgetChargerBindingStore.forContext(context).storedBinding(id)
                .takeIf { it.initialized }
                ?.let { binding -> read(context, id, binding.chargerProfileId) }

        private fun read(context: Context, id: Int, chargerProfileId: String?): WidgetSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "$id."
            val region = AppLanguageSettings.region(context)
            // The saved area is never replaced by a default; only a widget that never saved one gets one.
            val savedArea = p.getString(key + "area", null)
                ?: AreaSelection.defaultArea(PriceMarkets.all, region).orEmpty()
            val market = PriceMarkets.find(savedArea)
            return WidgetSettings(
                area = savedArea,
                vat = p.getBoolean(key + "vat", false),
                tax = p.getBoolean(key + "tax", false),
                transfer = p.getBoolean(key + "transfer", false),
                // A stored figure wins over the relay's suggestion; no suggestion means 0.0.
                taxMinorUnit = p.getString(key + "taxMinorUnit", null)?.toDoubleOrNull()
                    ?: market?.suggestedTax
                    ?: NO_SUGGESTION,
                gridFeeMinorUnit = p.getString(key + "gridFeeMinorUnit", null)?.toDoubleOrNull()
                    ?: market?.suggestedGridFee
                    ?: NO_SUGGESTION,
                chargingPhases = p.getInt(key + "chargingPhases", 3).takeIf { it == 1 || it == 3 } ?: 3,
                chargingAmps = p.getInt(key + "chargingAmps", 10),
                consumptionKwhPerMil = p.getString(key + "consumptionKwhPerMil", "2.0")?.toDoubleOrNull() ?: 2.0,
                chargingKwh = chargingKwh(p, key),
                driver = PlanDriver.of(p.getString(key + "driver", null)),
                maxChargingPeriods = p.getInt(key + "maxChargingPeriods", 1).coerceIn(1, 8),
                showChargingPlan = p.getBoolean(key + "showChargingPlan", false),
                useDepartureTime = p.getBoolean(key + "useDepartureTime", true),
                departureHour = p.getInt(key + "departureHour", 8),
                departureMinute = p.getInt(key + "departureMinute", 0),
                chargerProfileId = chargerProfileId
            )
        }

        fun save(context: Context, id: Int, value: WidgetSettings) {
            val key = "$id."
            val stored = prefs(context)
            // Changing a stored option is a choice; the first write of the default is not.
            val chosen = stored.contains(key + "showChargingPlan") &&
                stored.getBoolean(key + "showChargingPlan", false) != value.showChargingPlan
            stored.edit()
                .apply { if (chosen) putBoolean(key + "showChargingPlanChosen", true) }
                .putString(key + "area", value.area)
                .putBoolean(key + "vat", value.vat)
                .putBoolean(key + "tax", value.tax)
                .putBoolean(key + "transfer", value.transfer)
                .putString(key + "taxMinorUnit", value.taxMinorUnit.toString())
                .putString(key + "gridFeeMinorUnit", value.gridFeeMinorUnit.toString())
                // The resolution the phone once chose is no longer a setting: its stored value goes.
                .remove(key + "intervalMinutes")
                .putInt(key + "chargingPhases", value.chargingPhases)
                .putInt(key + "chargingAmps", value.chargingAmps)
                .putString(key + "consumptionKwhPerMil", value.consumptionKwhPerMil.toString())
                .putFloat(key + "chargingKwh", value.chargingKwh.toFloat())
                .putString(key + "driver", PlanDriver.storedForm(value.driver))
                .putInt(key + "maxChargingPeriods", value.maxChargingPeriods.coerceIn(1, 8))
                .putBoolean(key + "showChargingPlan", value.showChargingPlan)
                .putBoolean(key + "useDepartureTime", value.useDepartureTime)
                .putInt(key + "departureHour", value.departureHour)
                .putInt(key + "departureMinute", value.departureMinute)
                .apply()
            // An explicit save always decides the binding, even to null ("No charger").
            WidgetChargerBindingStore.forContext(context).setBinding(id, value.chargerProfileId)
        }

        fun delete(context: Context, id: Int) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            listOf("area", "vat", "tax", "transfer", "taxMinorUnit", "gridFeeMinorUnit", "intervalMinutes",
                "chargingPhases", "chargingAmps", "consumptionKwhPerMil", "chargingKwh", "driver", "maxChargingPeriods", "showChargingPlan", "showChargingPlanChosen")
                .forEach { editor.remove("$id.$it") }
            listOf("useDepartureTime", "departureHour", "departureMinute")
                .forEach { editor.remove("$id.$it") }
            editor.apply()
            WidgetChargerBindingStore.forContext(context).removeBinding(id)
        }
    }
}
