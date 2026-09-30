package se.sensnology.spotnav.chart

import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The last chart this screen confirmed, kept in memory only. Offline presentation is a copy of it, not a
 * re-derivation: it holds its owner (profile and screen generation), the record it is about (area and
 * revision), the price request whose accepted result it was drawn from, and the drawn market, bands,
 * footer and schedule. Nothing of it is combined with current controls or another price answer.
 * It is never persisted; "no matching snapshot" is a normal state drawn as the unavailable graph.
 */
internal data class ConfirmedChart(
    val profileId: String?,
    val screenGeneration: Int,
    val areaId: String,
    val revision: Int,
    val subject: PriceRequestKey,
    val prices: PriceResult,
    val market: ChartMarket,
    val bands: List<ChartBand>,
    val footer: ChartFooterPlan?,
    val remotePlan: RemotePlan?,
    /** Home Assistant's own prices when the graph drew those (paired charger); [market] then matches them instead of [prices]. */
    val drawnPrices: PriceResult? = null,
    val figures: PairedPlanFigures? = null
)

/**
 * When a chart may be retained, and when a retained one may be shown again. Both rules are values so
 * callers cannot answer "same subject" differently.
 */
internal object ConfirmedChartRules {

    fun capture(
        state: VisibleAuthority?,
        subject: PriceRequestKey?,
        prices: PriceResult?,
        market: ChartMarket?,
        bands: List<ChartBand>,
        footer: ChartFooterPlan?,
        profileId: String?,
        screenGeneration: Int,
        drawnPrices: PriceResult? = null,
        figures: PairedPlanFigures? = null
    ): ConfirmedChart? {
        if (subject == null || prices == null || market == null) return null
        if (subject.profileId != profileId || subject.generation != screenGeneration) return null
        val record = when (state) {
            is VisibleAuthority.AutoRemote -> state.settings.takeIf { state.availability == AuthorityAvailability.CONFIRMED }
            else -> null
        } ?: return null
        if (subject.areaId != record.areaId) return null
        return ConfirmedChart(
            profileId = profileId,
            screenGeneration = screenGeneration,
            areaId = record.areaId,
            revision = record.revision,
            subject = subject,
            prices = prices,
            market = market,
            // Copied so the retained value cannot alias a list its caller still mutates.
            bands = bands.toList(),
            footer = footer,
            remotePlan = (state as? VisibleAuthority.AutoRemote)?.remotePlan,
            drawnPrices = drawnPrices,
            figures = figures
        )
    }

    /**
     * Whether [snapshot] is the chart to show for [record] now: profile, screen generation, area and
     * revision must all agree, and a held price answer must be the one the chart was drawn from.
     */
    fun matches(
        snapshot: ConfirmedChart?,
        profileId: String?,
        screenGeneration: Int,
        record: HaPlanningSettings?,
        subject: PriceRequestKey?
    ): Boolean {
        if (snapshot == null || record == null) return false
        if (snapshot.profileId != profileId) return false
        if (snapshot.screenGeneration != screenGeneration) return false
        if (snapshot.areaId != record.areaId) return false
        if (snapshot.revision != record.revision) return false
        if (subject != null && subject != snapshot.subject) return false
        return true
    }
}
