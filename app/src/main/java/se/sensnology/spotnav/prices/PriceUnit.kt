package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * The unit per-kWh prices are shown in, chosen per phone (see [PriceUnitPreference]): the market's minor
 * unit ([MINOR], the default, "öre/kWh") or its major unit ([MAJOR], "kr/kWh"). A display preference
 * only; it is never sent to Home Assistant. As the Home Assistant card's `PriceUnit`.
 */
internal enum class PriceUnit(val key: String) {
    MINOR("minor"),
    MAJOR("major");

    companion object {
        val DEFAULT = MINOR

        /** The unit a stored [key] names; anything else, or nothing, is [DEFAULT]. */
        fun of(key: String?): PriceUnit = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * This phone's choice of [PriceUnit], kept beside the chart time axis and the app's other appearance
 * choices. The in-app chart, its readout, the price table, History and every widget show it; it is never
 * sent to Home Assistant. Nothing stored, or anything unknown, reads as [PriceUnit.DEFAULT].
 */
internal class PriceUnitPreference(private val store: KeyValueStore) {
    fun unit(): PriceUnit = PriceUnit.of(store.getString(KEY))

    fun save(unit: PriceUnit) {
        store.putString(KEY, unit.key)
    }

    companion object {
        /** The preference file the app's theme and chart time axis are kept in. */
        private const val PREFS = "app_appearance"
        const val KEY = "price_unit"

        fun of(context: Context): PriceUnitPreference = PriceUnitPreference(SharedPreferencesKeyValueStore(context, PREFS))
    }
}

/**
 * Per-kWh prices as text in the chosen [unit], as the Home Assistant card's `priceFigure`,
 * `pricePerKwh` and `priceAxisLabel` write them. Every price arrives in the market's minor unit. The
 * units are the market's own names ([minorName] "öre", [majorName] "kr"), never inferred from a currency
 * code; a market without both shows the minor unit whatever was chosen. Money totals and inputs entered
 * in the minor unit (tax, grid fee) are not written here. Android-free.
 */
internal class PriceFormat(
    val unit: PriceUnit,
    minorName: String?,
    majorName: String?,
    private val locale: Locale
) {
    private val minor = minorName?.takeIf { it.isNotBlank() }
    private val major = majorName?.takeIf { it.isNotBlank() }

    /** Whether the market names both units, so there is a choice to offer between them. */
    val choosable: Boolean get() = minor != null && major != null

    private val showsMajor: Boolean get() = unit == PriceUnit.MAJOR && choosable

    /** The unit name prices are shown in ("öre" or "kr"), without "/kWh"; empty when the market names none. */
    val unitName: String get() = (if (showsMajor) major else minor).orEmpty()

    /** "öre/kWh" or "kr/kWh"; empty when the market names no unit. */
    val perKwhUnit: String get() = if (unitName.isEmpty()) "" else "$unitName/kWh"

    /**
     * A price given in the minor unit as a figure in the shown unit: the minor unit to a tenth with zeros
     * trimmed ("163,1", "92"), the major unit to two decimals ("1,63"), rounded in whole minor units
     * first, so 208,5 öre reads 2,09 kr.
     */
    fun figure(minorValue: Double): String =
        if (showsMajor) decimals(Math.round(minorValue) / MINOR_PER_MAJOR, 2, 2)
        else decimals(minorValue, 0, 1)

    /** A price given in the minor unit, per kWh in the shown unit: "163,1 öre/kWh" or "1,63 kr/kWh". */
    fun perKwh(minorValue: Double): String =
        if (perKwhUnit.isEmpty()) figure(minorValue) else "${figure(minorValue)} $perKwhUnit"

    /**
     * One label on the chart's price axis from a value in the minor unit and the axis's span (minor unit).
     * The minor unit is whole numbers; the major unit takes the decimals the span needs (none from 10
     * major units, one from 1, two below), and zero is just "0".
     */
    fun axisLabel(minorValue: Double, minorSpan: Double): String {
        if (!showsMajor) return Math.round(minorValue).toString()
        val span = Math.abs(minorSpan) / MINOR_PER_MAJOR
        val digits = if (span >= 10) 0 else if (span >= 1) 1 else 2
        val rounded = BigDecimal(minorValue / MINOR_PER_MAJOR).setScale(digits, RoundingMode.HALF_UP)
        return if (rounded.signum() == 0) "0" else decimals(rounded.toDouble(), digits, digits)
    }

    private fun decimals(value: Double, least: Int, most: Int): String {
        val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
        format.minimumFractionDigits = least
        format.maximumFractionDigits = most
        format.isGroupingUsed = false
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value)
    }

    companion object {
        /** Minor units in one major unit: öre, øre, cent and pence are all hundredths. */
        const val MINOR_PER_MAJOR = 100.0

        /** The price the Display setting's examples are written with, in the minor unit. */
        const val EXAMPLE = 163.1

        /** The format for [market]'s units, or the minor unit unnamed when there is no market. */
        fun of(unit: PriceUnit, market: PriceMarket?, locale: Locale): PriceFormat =
            PriceFormat(unit, market?.minorUnit, market?.majorUnit, locale)
    }
}
