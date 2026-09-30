package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

class VehicleCapacityStoreTest {
    private val backing = FakeKeyValueStore()

    private fun store(backing: FakeKeyValueStore = this.backing) = VehicleCapacityStore(backing)

    @Test fun roundTripsACapacityForOneVehicleId() {
        store().set("vehicle-one", 77.5)
        assertEquals(77.5, store().get("vehicle-one")!!, 1e-9)
    }

    @Test fun returnsNullForAVehicleNothingIsRememberedFor() {
        store().set("vehicle-one", 77.5)
        assertNull(store().get("vehicle-two"))
        assertNull(store().get(""))
    }

    @Test fun keepsDifferentVehiclesIndependent() {
        store().set("vehicle-one", 77.5)
        store().set("vehicle-two", 58.0)

        assertEquals(77.5, store().get("vehicle-one")!!, 1e-9)
        assertEquals(58.0, store().get("vehicle-two")!!, 1e-9)

        // Overwriting one never disturbs the other.
        store().set("vehicle-one", 80.0)
        assertEquals(80.0, store().get("vehicle-one")!!, 1e-9)
        assertEquals(58.0, store().get("vehicle-two")!!, 1e-9)
    }

    /**
     * The capacity is keyed by Home Assistant's vehicle id alone, so it outlives any charger
     * profile: a second store instance over the same storage (an app restart, or a different
     * profile looking the same car up) reads exactly the same value, and nothing about a profile is
     * involved in the key.
     */
    @Test fun survivesAFreshStoreInstanceAndIsNotTiedToAnyProfile() {
        store().set("vehicle-one", 77.5)

        val afterRestart = VehicleCapacityStore(backing)

        assertEquals(77.5, afterRestart.get("vehicle-one")!!, 1e-9)
        assertEquals("77.5", backing.rawOrNull("capacity:vehicle-one"))
    }

    @Test fun refusesToStoreForABlankVehicleId() {
        assertThrows(IllegalArgumentException::class.java) { store().set("", 77.5) }
        assertThrows(IllegalArgumentException::class.java) { store().set("   ", 77.5) }
        assertNull(backing.rawOrNull("capacity:"))
    }

    @Test fun refusesToStoreACapacityThatCouldNotBeARealOne() {
        assertThrows(IllegalArgumentException::class.java) { store().set("vehicle-one", 0.0) }
        assertThrows(IllegalArgumentException::class.java) { store().set("vehicle-one", -60.0) }
        assertThrows(IllegalArgumentException::class.java) { store().set("vehicle-one", Double.NaN) }
    }

    /**
     * A value this app cannot read back as a plausible capacity reads as "unknown" (null), never as
     * a made-up number — the capacity prompt then simply appears again for that vehicle rather than
     * the app silently computing a suggestion from garbage.
     */
    @Test fun treatsACorruptOrImplausibleStoredValueAsUnknown() {
        listOf("", "not-a-number", "-5", "0").forEach { raw ->
            val backing = FakeKeyValueStore()
            backing.setRaw("capacity:vehicle-one", raw)
            assertNull(VehicleCapacityStore(backing).get("vehicle-one"))
        }
    }
}
