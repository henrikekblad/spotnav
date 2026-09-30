package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.chargers.ChargerPhases

/**
 * What the vehicle card shows, kept free of any Android dependency so the decision can be unit
 * tested directly — like [VehicleEnergy] and [ChargerPhases], and for a sharper reason here: this
 * card exists in every state, including one where Home Assistant knows nothing at all.
 */
object VehicleFacts {
    /** One row of the card. */
    enum class Fact {
        /** The car's state of charge: only ever read, only with a vehicle. */
        STATE_OF_CHARGE,

        /** The car's own reported charge limit: only with a vehicle. */
        CHARGE_LIMIT,

        /** The car's battery capacity: a property, entered or detected. */
        BATTERY_CAPACITY,

        /** How much the car uses per mil: a property, entered. */
        CONSUMPTION
    }

    /**
     * The card's content for one moment: which car it is about, whether its title doubles as a
     * selector, and which rows it has (in reading order: state first, then the car's own
     * properties).
     */
    data class Content(
        val vehicle: VehicleStatus?,
        val selectable: Boolean,
        val facts: List<Fact>
    ) {
        /**
         * Whether the capacity row can be entered. A capacity lives in [VehicleCapacityStore] keyed
         * by vehicle id, so with no vehicle there is nothing to key it to.
         */
        val capacitySettable: Boolean get() = vehicle != null
    }

    /**
     * The card's content, from the vehicles Home Assistant reports and the vehicle id the user
     * chose on this charger profile.
     */
    fun content(vehicles: List<VehicleStatus>, storedVehicleId: String?): Content {
        val vehicle = effectiveVehicle(vehicles, storedVehicleId)
        val facts = buildList {
            if (vehicle != null) {
                add(Fact.STATE_OF_CHARGE)
                if (chargeLimit(vehicle) != null) add(Fact.CHARGE_LIMIT)
            }
            add(Fact.BATTERY_CAPACITY)
            add(Fact.CONSUMPTION)
        }
        return Content(vehicle = vehicle, selectable = vehicles.size > 1, facts = facts)
    }

    /** The vehicle the card is about, or `null` when the choice is ambiguous. */
    fun effectiveVehicle(vehicles: List<VehicleStatus>, storedVehicleId: String?): VehicleStatus? {
        vehicles.find { it.id == storedVehicleId }?.let { return it }
        return vehicles.singleOrNull()
    }

    /** The charge limit [vehicle] reports, as whole percent, or `null` when it reports none usable. */
    fun chargeLimit(vehicle: VehicleStatus): Int? =
        vehicle.targetSocPercentMax?.takeIf { it.isFinite() }?.toInt()
}
