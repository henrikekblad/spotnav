package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardGuest
import java.util.Locale

/**
 * The car on a paired charger's card, worked out without a view: a full-width button over Start and
 * Pause where more than one car can charge (it opens the change-car chooser, and is marked while the
 * question "which car is plugged in?" is open), a value row under the charge history where there is
 * one car (it opens that car's settings), and nothing where no car is known.
 */
internal object ChargerCarFace {
    enum class Place { BUTTON, ROW }

    /** What a tap opens: the change-car chooser, or the car's own settings. */
    enum class Tap { CHOOSER, CAR_SETTINGS }

    data class Face(
        val place: Place,
        /** The car planned for; `null` when two cars can charge and none is known yet. */
        val vehicleId: String?,
        val name: String?,
        val basis: VehicleIdentification.Basis?,
        /** "72 % of 95 % target", or the level alone without a target; `null` with nothing to say. */
        val levels: String?,
        /** The question is open: the button reads "Choose car", outlined in the accent. */
        val highlighted: Boolean,
        val tap: Tap?
    )

    /** The translated words: [carWith] is the `car_with` template ("Car · %1$s"). */
    class Words(val car: String, val carWith: String, val choose: String, val basis: (VehicleIdentification.Basis) -> String)

    /**
     * The car's control from [dashboard]; `null` when no car is known and none can be chosen. A guest car is
     * [guestName] with the amount its plan charges ("Guest car · 10 kWh", written by [amount]): it has no level.
     */
    fun of(
        dashboard: Dashboard,
        percent: (Int) -> String,
        ofTarget: String,
        guestName: String = VehicleIdentification.GUEST_NAME,
        amount: (Double) -> String = { "$it kWh" }
    ): Face? {
        val line = VehicleIdentification.carLine(dashboard, guestName) ?: return null
        val id = line.vehicleId
        val levels = if (id == DashboardGuest.VEHICLE_ID) {
            PairedCarLine.guestAmount(dashboard)?.let(amount)
        } else {
            id?.let { PairedCarLine.levelsText(PairedCarLine.levels(dashboard, it), percent, ofTarget) }
        }
        val name = id?.let { line.name ?: it }
        return if (line.canSwitch) {
            Face(Place.BUTTON, id, name, line.basis, levels, VehicleIdentification.banner(dashboard) != null, Tap.CHOOSER)
        } else {
            Face(Place.ROW, id, name, line.basis, levels, highlighted = false, tap = id?.let { Tap.CAR_SETTINGS })
        }
    }

    /** The button's small line: "Choose car" while asked, else "Car · identified by …", else "Car". */
    fun caption(face: Face, words: Words): String = when {
        face.highlighted -> words.choose
        face.basis != null -> words.carWith.format(Locale.ROOT, words.basis(face.basis))
        else -> words.car
    }

    /** The row's label: "Car · EV6". */
    fun rowLabel(face: Face, words: Words): String =
        face.name?.let { words.carWith.format(Locale.ROOT, it) } ?: words.car
}
