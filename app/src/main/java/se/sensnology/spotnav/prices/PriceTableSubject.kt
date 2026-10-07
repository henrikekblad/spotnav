package se.sensnology.spotnav.prices

import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartMarketBuild
import se.sensnology.spotnav.ha.authority.AuthorityRefresh
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.PlanningInputs

/**
 * What one Price Table was launched for: an immutable subject, captured on the screen's own thread
 * from the accepted authority, and never re-derived from the widget's stored record afterwards.
 */
internal data class PriceTableSubject(
    val requestKey: PriceRequestKey,
    val areaId: String,
    /** The exact market presentation; present even when HA owns the plan and [inputs] are absent. */
    val market: ChartMarket?,
    val inputs: PlanningInputs?,
    val revision: Int?
) {
    /** Whether a price answer for [key] may render here. */
    fun accepts(key: PriceRequestKey?): Boolean = AuthorityRefresh.accepts(key, requestKey)

    /**
     * Whether [inputs] and [revision] are still exactly this subject's, which is what a remembered
     * plan has to be checked against before it may mark rows.
     */
    fun owns(inputs: PlanningInputs?, revision: Int?): Boolean =
        inputs != null && inputs == this.inputs && revision == this.revision
}

/** The one place a launch subject is built from an authority state. */
internal object PriceTableSubjects {
    fun of(
        authority: VisibleAuthority?,
        localArea: String,
        localInputs: PlanningInputs?,
        profileId: String?,
        generation: Int,
        catalogue: List<PriceMarket>
    ): PriceTableSubject {
        if (authority != null && authority.haOwnsPlanning) {
            val record = authority.remoteSettings
            if (record != null) {
                val areaId = record.areaId ?: localArea
                return subject(
                    areaId = areaId,
                    market = (ChartMarket.from(
                        record,
                        areaId,
                        catalogue.firstOrNull { it.id == areaId }
                    ) as? ChartMarketBuild.Ready)?.market,
                    // No paired state carries calculation inputs: Home Assistant owns the plan for
                    // this charger, in either mode (see [inputsOf]).
                    inputs = inputsOf(),
                    revision = record.revision,
                    profileId = profileId,
                    generation = generation
                )
            }
            // An offline screen with no cached record: nothing is shown rather than the widget's
            // local prices, and the area falls back to the local one only so the title can name a
            // market the catalogue still knows.
            return subject(
                areaId = localArea,
                market = null,
                inputs = null,
                revision = null,
                profileId = profileId,
                generation = generation
            )
        }
        return subject(
            areaId = localArea,
            market = localInputs?.let(ChartMarket::of),
            inputs = localInputs,
            revision = null,
            profileId = profileId,
            generation = generation
        )
    }

    private fun inputsOf(): PlanningInputs? = null

    private fun subject(
        areaId: String,
        market: ChartMarket?,
        inputs: PlanningInputs?,
        revision: Int?,
        profileId: String?,
        generation: Int
    ): PriceTableSubject = PriceTableSubject(
        requestKey = PriceRequestKey(profileId, areaId, generation),
        areaId = areaId,
        market = market,
        inputs = inputs,
        revision = revision
    )
}

/**
 * Whether a price answer for [subject] may still render: the authority's current market still being
 * the one the subject was priced in, for the same profile and screen pass.
 */
internal fun PriceTableSubjects.stillCurrent(
    subject: PriceTableSubject,
    authority: VisibleAuthority?,
    localArea: String,
    catalogue: List<PriceMarket>
): Boolean = of(
    authority = authority,
    localArea = localArea,
    localInputs = subject.inputs,
    profileId = subject.requestKey.profileId,
    generation = subject.requestKey.generation,
    catalogue = catalogue
).requestKey == subject.requestKey
