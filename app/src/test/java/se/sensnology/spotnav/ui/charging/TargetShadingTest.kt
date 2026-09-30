package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.planning.ChargeNeed
import se.sensnology.spotnav.vehicles.VehicleStatus

/** What the target slider draws, and what its two ends say. */
class TargetShadingTest {
    private fun vehicle(
        id: String = "car",
        socPercent: Double = 41.0,
        targetSocPercentMax: Double? = null
    ) = VehicleStatus(
        id = id,
        name = "Car $id",
        socPercent = socPercent,
        targetSocPercentMax = targetSocPercentMax,
        batteryCapacityKwh = null,
        capacitySource = "unknown"
    )

    /** An EV6 at 41 %, with a 90 % limit. */
    private val ev6 = TargetShading.of(vehicle(socPercent = 41.0, targetSocPercentMax = 90.0))

    private val gap = 8

    // what is shaded

    @Test fun bothEndsAreShadedWhenBothAreKnown() {
        assertEquals(0.41f, ev6.below, 0.0001f)
        assertEquals(0.90f, ev6.above!!, 0.0001f)
        assertEquals(41, ev6.floorPercent)
        assertEquals(90, ev6.ceilingPercent)
    }

    @Test fun theShadedEndsAreExactlyWhatTheThumbCannotReach() {
        // The band's own two bounds:
        val band = ChargeNeed.targetRange(41.0, 90.0)

        assertEquals(band.currentPercent / 100f, ev6.below, 0.0001f)
        assertEquals(band.limitPercent / 100f, ev6.above!!, 0.0001f)
    }

    @Test fun anUnknownLimitShadesBelowAndSaysNothingAbove() {
        // A sleeping car.
        val sleepingCar = TargetShading.of(vehicle(socPercent = 88.0, targetSocPercentMax = null))

        assertEquals(0.88f, sleepingCar.below, 0.0001f)
        assertNull(sleepingCar.above)
        assertEquals(88, sleepingCar.floorPercent)
        assertNull(sleepingCar.ceilingPercent)
    }

    @Test fun aLimitOfOneHundredIsStillALimitAndIsNotTheUnknownCase() {
        // The Leaf: 88 %, and a car that really does accept 100 %.
        val leaf = TargetShading.of(vehicle(socPercent = 88.0, targetSocPercentMax = 100.0))

        assertEquals(0.88f, leaf.below, 0.0001f)
        assertEquals(1.0f, leaf.above!!, 0.0001f)
        assertEquals(100, leaf.ceilingPercent)
    }

    @Test fun aCarAboveItsOwnLimitShadesTheWholeTrackAndLabelsOneNumber() {
        // The e-tron: 95 % against a 90 % limit.
        val etron = TargetShading.of(vehicle(socPercent = 95.0, targetSocPercentMax = 90.0))

        assertEquals(0.90f, etron.below, 0.0001f)
        assertEquals(0.90f, etron.above!!, 0.0001f)
        assertEquals(90, etron.floorPercent)
        assertEquals(90, etron.ceilingPercent)
    }

    @Test fun aChargeAboveTheScaleIsStillClampedByTheBand() {
        val impossible = TargetShading.of(vehicle(socPercent = 130.0, targetSocPercentMax = null))

        assertEquals(100, impossible.floorPercent)
        assertEquals(1.0f, impossible.below, 0.0001f)
    }

    @Test fun theFloorRoundsDownSoItMatchesTheNearestReachableTarget() {
        val car = TargetShading.of(vehicle(socPercent = 52.6, targetSocPercentMax = 80.0))

        assertEquals(52, car.floorPercent)
        assertEquals(0.52f, car.below, 0.0001f)
    }

    @Test fun aLimitAboveTheScaleIsACeilingOfOneHundred() {
        val car = TargetShading.of(vehicle(socPercent = 41.0, targetSocPercentMax = 200.0))

        assertEquals(100, car.ceilingPercent)
        assertEquals(1.0f, car.above!!, 0.0001f)
    }

    @Test fun anUnreadableChargeShadesNothingButTheLimitIsStillLabelled() {
        for (soC in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val car = TargetShading.of(vehicle(socPercent = soC, targetSocPercentMax = 90.0))

            assertEquals(0f, car.below, 0.0001f)
            assertEquals(0, car.floorPercent)
            // The limit is still a fact about the car, so it is still labelled.
            assertEquals(90, car.ceilingPercent)
        }
    }

    @Test fun nothingDetectedShadesNothingAndLabelsNothing() {
        val nothing = TargetShading.of(null)

        assertEquals(0f, nothing.below, 0.0001f)
        assertNull(nothing.above)
        assertNull(nothing.floorPercent)
        assertNull(nothing.ceilingPercent)
    }

    // where the two labels go

    @Test fun eachLabelSitsAtTheEndItNames() {
        // 1000 px of track, 40 px labels:
        val labels = TargetShading.labels(1000, 40, 40, ev6, gap)

        assertEquals(410 - 40, labels.floorX!!)
        assertEquals(900, labels.ceilingX!!)
    }

    @Test fun aNarrowGapPushesTheFloorLabelBackInsteadOfOverlappingTheCeiling() {
        // 88 % against 100 % on a 300 px track:
        val leaf = TargetShading.of(vehicle(socPercent = 88.0, targetSocPercentMax = 100.0))
        val labels = TargetShading.labels(300, 40, 40, leaf, gap)

        val ceilingX = labels.ceilingX!!
        assertEquals(300 - 40, ceilingX)
        assertEquals(ceilingX - gap - 40, labels.floorX!!)
    }

    @Test fun anUnknownCeilingKeepsItsOwnLabelRightOfTheFloorAndPushesItBackIfNeeded() {
        // The word for "no limit" is wider than a number, so the unknown case is where a collision
        // is easiest to provoke.
        val sleepingCar = TargetShading.of(vehicle(socPercent = 41.0, targetSocPercentMax = null))
        val labels = TargetShading.labels(300, 40, 200, sleepingCar, gap)

        val ceilingX = labels.ceilingX!!
        assertEquals(300 - 200, ceilingX)
        assertEquals(ceilingX - gap - 40, labels.floorX!!)
    }

    @Test fun crossedEndsAreLabelledOnce() {
        // The car is above its own limit:
        val etron = TargetShading.of(vehicle(socPercent = 95.0, targetSocPercentMax = 90.0))
        val labels = TargetShading.labels(1000, 40, 40, etron, gap)

        assertNull(labels.floorX)
        assertEquals(900, labels.ceilingX!!)
    }

    @Test fun equalEndsAreLabelledOnce() {
        val car = TargetShading.of(vehicle(socPercent = 20.0, targetSocPercentMax = 20.0))
        val labels = TargetShading.labels(1000, 40, 40, car, gap)

        assertNull(labels.floorX)
        assertEquals(200, labels.ceilingX!!)
    }

    @Test fun aFloorLabelWithNoRoomToTheLeftIsNotDrawnAtAll() {
        // Two wide labels on a very short track:
        val car = TargetShading.of(vehicle(socPercent = 5.0, targetSocPercentMax = 6.0))
        val labels = TargetShading.labels(80, 30, 30, car, gap)

        assertNull(labels.floorX)
        assertEquals(5, labels.ceilingX!!)
    }

    @Test fun aCeilingLabelNeverRunsOffTheRightEdge() {
        val car = TargetShading.of(vehicle(socPercent = 41.0, targetSocPercentMax = 90.0))
        val labels = TargetShading.labels(100, 40, 40, car, gap)

        assertEquals(100 - 40, labels.ceilingX!!)
    }

    @Test fun nothingKnownMeansNoLabels() {
        val labels = TargetShading.labels(1000, 40, 40, TargetShading.of(null), gap)

        assertNull(labels.floorX)
        assertNull(labels.ceilingX)
    }

    @Test fun anUnmeasuredTrackDrawsNoLabels() {
        // The view asks before its first layout pass has measured anything.
        val labels = TargetShading.labels(0, 40, 40, ev6, gap)

        assertNull(labels.floorX)
        assertNull(labels.ceilingX)
    }
}
