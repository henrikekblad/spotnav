package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardIdentification
import se.sensnology.spotnav.ha.dashboard.DashboardVehicleRef
import se.sensnology.spotnav.ha.dashboard.IdentificationSource
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.IdentifyMode

/**
 * What the screen shows of "which car is plugged in?" at a charger more than one car can charge at,
 * decided from one dashboard: the question's banner, the car line and how the car was decided, Byt
 * bil's choices, the vehicle card's "assumed" mark, the Settings section, and a car's sources. A Home
 * Assistant that does not state the identification settings shows none of it (today's behaviour).
 */
internal object VehicleIdentification {
    /** Whether Home Assistant states the identification settings, which is how it advertises the feature. */
    fun advertised(dashboard: Dashboard): Boolean = dashboard.settings?.identification != null

    /** One car to choose, with what its own report says (`null`: nothing to say). */
    data class Choice(val vehicleId: String, val name: String, val hint: Hint?)

    /** The open question: a button per car in the given order, and the car kept when nobody answers. */
    data class Banner(
        val choices: List<Choice>,
        val keptName: String?,
        /** Whether the cars were looked at first ("Automatic"), so the banner says they could not tell. */
        val carsCouldNotTell: Boolean
    )

    fun banner(dashboard: Dashboard): Banner? {
        if (!advertised(dashboard)) return null
        val block = dashboard.identification?.takeIf { it.state == DashboardIdentification.State.ASKING } ?: return null
        if (block.candidates.isEmpty()) return null
        return Banner(
            choices = block.candidates.map { Choice(it.vehicleId, it.name, Hint.of(block.verdictFor(it.vehicleId))) },
            keptName = block.vehicleId?.let { id -> nameOf(dashboard, id) },
            carsCouldNotTell = dashboard.settings?.identification?.mode != IdentifyMode.ASK
        )
    }

    /** Whether the question is open, so the connection line says it waits for an answer. */
    fun waitingForAnswer(dashboard: Dashboard): Boolean =
        advertised(dashboard) && dashboard.identification?.state == DashboardIdentification.State.ASKING

    /** How the car on the car line was decided, in the words the line uses. */
    enum class Basis { PLUG_SENSOR, LOCATION, CHOSEN_MANUALLY, ASSUMED, IDENTIFYING, CAMERA }

    /**
     * The charger card's car line: the car planned for, how it was decided (`null`: nothing to say,
     * from a Home Assistant that does not identify, or with nothing being identified), and whether
     * Byt bil is offered (more than one car to choose from). With two or more cars and none planned
     * for yet, [vehicleId] and [name] are `null` ("no car chosen") and Byt bil is how one is chosen.
     */
    data class CarLine(val vehicleId: String?, val name: String?, val basis: Basis?, val canSwitch: Boolean)

    /** The car line of a paired charger, also while the question is open (the banner follows it). */
    fun carLine(dashboard: Dashboard): CarLine? {
        val canSwitch = switchChoices(dashboard).choices.size >= 2
        val id = currentId(dashboard) ?: return if (canSwitch) CarLine(null, null, null, canSwitch = true) else null
        val block = dashboard.identification?.takeIf { advertised(dashboard) && canSwitch }
        val basis = when {
            block == null -> null
            block.state == DashboardIdentification.State.WAITING -> Basis.IDENTIFYING
            else -> when (block.method) {
                DashboardIdentification.Method.PLUG_SENSOR -> Basis.PLUG_SENSOR
                DashboardIdentification.Method.LOCATION -> Basis.LOCATION
                // A person's answer and a choice in the settings read the same: chosen manually.
                DashboardIdentification.Method.ANSWERED, DashboardIdentification.Method.MANUAL -> Basis.CHOSEN_MANUALLY
                DashboardIdentification.Method.ASSUMED -> Basis.ASSUMED
                DashboardIdentification.Method.CAMERA -> Basis.CAMERA
                null -> null
            }
        }
        return CarLine(id, nameOf(dashboard, id) ?: id, basis, canSwitch)
    }

    /** Whether the car the vehicle card shows was only assumed (nobody answered, nothing decided it). */
    fun assumed(dashboard: Dashboard): Boolean =
        advertised(dashboard) && dashboard.identification?.method == DashboardIdentification.Method.ASSUMED

    /** Whether Byt bil on the charger card stands in for the vehicle card's own picker. */
    fun replacesPicker(dashboard: Dashboard): Boolean = advertised(dashboard)

    /** Whether Byt bil answers through `identify_vehicle` (else it is the planned car's settings write). */
    fun identifies(dashboard: Dashboard): Boolean = advertised(dashboard)

    /** What a car's own report says, as Byt bil's hint under its name. */
    enum class Hint {
        PLUGGED_IN, NOT_PLUGGED_IN, AWAY, ELSEWHERE;

        companion object {
            fun of(verdict: String?): Hint? = when (verdict) {
                "plugged_in", "likely" -> PLUGGED_IN
                "not_plugged_in" -> NOT_PLUGGED_IN
                "away" -> AWAY
                "elsewhere" -> ELSEWHERE
                else -> null
            }
        }
    }

    /** Byt bil's dialog: a radio per car, the [current] one chosen. */
    data class Switch(val choices: List<Choice>, val current: String?)

    /** The question's cars while one is being identified, else the cars at this charger. */
    fun switchChoices(dashboard: Dashboard): Switch {
        val block = dashboard.identification
        val choices = block?.candidates?.takeIf { it.isNotEmpty() }
            ?.map { Choice(it.vehicleId, it.name, Hint.of(block.verdictFor(it.vehicleId))) }
            ?: dashboard.vehicles.map { Choice(it.id, it.name, Hint.of(block?.verdictFor(it.id))) }
        return Switch(choices, currentId(dashboard))
    }

    /** The car the charger plans for now: the block's, else the dashboard's target, else the `soc` block's. */
    private fun currentId(dashboard: Dashboard): String? =
        dashboard.identification?.vehicleId
            ?: dashboard.targetVehicleId?.takeIf { it.isNotEmpty() }
            ?: dashboard.soc?.vehicleId?.takeIf { it.isNotEmpty() }
            ?: dashboard.vehicles.singleOrNull()?.id

    private fun nameOf(dashboard: Dashboard, id: String): String? =
        dashboard.identification?.candidates?.firstOrNull { it.vehicleId == id }?.name
            ?: PairedVehicles.name(dashboard, id)

    // Settings

    /** One car of the Settings section, and whether it can charge here. */
    data class Car(val vehicleId: String, val name: String, val ticked: Boolean)

    /** The Settings section: the mode, and the cars this charger lists. */
    data class Section(val mode: IdentifyMode, val cars: List<Car>)

    /**
     * The section, when Home Assistant states the settings and there are at least two cars to choose
     * from. The cars are every detected car (`vehicle_choices`), so one left out can be ticked again; an
     * older Home Assistant without that list gives the charger's own cars.
     */
    fun section(dashboard: Dashboard, record: HaPlanningSettings?): Section? {
        val stated = record?.identification ?: return null
        val cars = dashboard.vehicleChoices ?: dashboard.vehicles.map { DashboardVehicleRef(it.id, it.name) }
        if (cars.size < 2) return null
        val ids = stated.vehicleIds
        return Section(stated.mode, cars.map { Car(it.id, it.name, ids == null || it.id in ids) })
    }

    /** The cars row's value: the ticked cars' names, or `null` when every car is ticked ("All cars"). */
    fun tickedNames(section: Section): List<String>? =
        section.cars.takeUnless { cars -> cars.all { it.ticked } }?.filter { it.ticked }?.map { it.name }

    /**
     * What a save of the cars writes, as the Home Assistant card does: every listed car ticked is `null`
     * (every detected car, now and later), else the ticked ones in the listed order; none ticked is an
     * empty list, which is refused.
     */
    fun vehicleIdsFor(allIds: List<String>, ticked: Set<String>): List<String>? {
        val chosen = allIds.filter { it in ticked }
        return if (chosen.size == allIds.size && chosen.isNotEmpty()) null else chosen
    }

    /** A source as a row says it. */
    sealed interface SourceText {
        data class Named(val name: String) : SourceText
        data object None : SourceText
        data object Choose : SourceText
        data object NotFound : SourceText
    }

    fun sourceText(source: IdentificationSource): SourceText = when {
        source.entityId != null -> SourceText.Named(source.name ?: source.entityId)
        source.chosen -> SourceText.None
        source.candidates.size > 1 -> SourceText.Choose
        else -> SourceText.NotFound
    }
}
