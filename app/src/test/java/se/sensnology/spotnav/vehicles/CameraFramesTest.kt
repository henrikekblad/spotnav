package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.vehicles.CameraFrames.Grip

/**
 * The frame editor's geometry, as the Home Assistant card has it: a frame moved or resized by a finger
 * stays inside the picture and at least 5 % each way, rounded to four decimals as Home Assistant keeps
 * it; the whole picture is saved as `null`; and the preview shows the frame's part of the picture.
 */
class CameraFramesTest {
    private val frame = CameraFrame(0.2, 0.3, 0.4, 0.5)

    @Test fun aMovedFrameKeepsItsSizeAndStopsAtTheEdges() {
        assertEquals(CameraFrame(0.3, 0.2, 0.4, 0.5), CameraFrames.move(frame, 0.1, -0.1))
        assertEquals(CameraFrame(0.6, 0.0, 0.4, 0.5), CameraFrames.move(frame, 0.9, -0.9))
        assertEquals(CameraFrame(0.0, 0.5, 0.4, 0.5), CameraFrames.move(frame, -1.0, 1.0))
    }

    @Test fun aDraggedCornerResizesWithTheOppositeCornerKept() {
        assertEquals(CameraFrame(0.1, 0.2, 0.5, 0.6), CameraFrames.drag(frame, Grip.NW, -0.1, -0.1))
        assertEquals(CameraFrame(0.2, 0.3, 0.5, 0.6), CameraFrames.drag(frame, Grip.SE, 0.1, 0.1))
        assertEquals(CameraFrame(0.2, 0.1, 0.8, 0.7), CameraFrames.drag(frame, Grip.NE, 0.5, -0.2))
        assertEquals(CameraFrame(0.0, 0.3, 0.6, 0.7), CameraFrames.drag(frame, Grip.SW, -0.5, 0.5))
        // Never smaller than the least size, never past an edge.
        assertEquals(CameraFrame(0.2, 0.3, 0.05, 0.05), CameraFrames.drag(frame, Grip.SE, -0.9, -0.9))
        assertEquals(CameraFrame(0.55, 0.75, 0.05, 0.05), CameraFrames.drag(frame, Grip.NW, 0.9, 0.9))
        assertEquals(CameraFrame(0.3, 0.2, 0.4, 0.5), CameraFrames.drag(frame, Grip.MOVE, 0.1, -0.1))
    }

    @Test fun aFrameIsRoundedAsHomeAssistantKeepsIt() {
        assertEquals(CameraFrame(0.1235, 0.0, 0.3333, 1.0), CameraFrames.clamp(CameraFrame(0.123456, -0.2, 0.333333, 1.4)))
        assertEquals(CameraFrame(0.95, 0.0, 0.05, 0.05), CameraFrames.clamp(CameraFrame(1.2, 0.0, 0.0, 0.01)))
    }

    @Test fun theWholePictureIsSavedAsNone() {
        assertTrue(CameraFrames.isWhole(null))
        assertTrue(CameraFrames.isWhole(CameraFrames.WHOLE))
        assertTrue(CameraFrames.isWhole(CameraFrame(0.00005, 0.0, 0.99995, 1.0)))
        assertFalse(CameraFrames.isWhole(frame))
        assertNull(CameraFrames.forSave(CameraFrames.WHOLE))
        assertEquals(frame, CameraFrames.forSave(frame))
    }

    @Test fun aTouchTakesTheNearestCornerWithinReachElseTheFrameItself() {
        // Reach is 5 % of the picture each way here.
        assertEquals(Grip.NW, CameraFrames.grip(frame, 0.22, 0.28, 0.05, 0.05))
        assertEquals(Grip.SE, CameraFrames.grip(frame, 0.63, 0.82, 0.05, 0.05))
        assertEquals(Grip.NE, CameraFrames.grip(frame, 0.6, 0.3, 0.05, 0.05))
        assertEquals(Grip.SW, CameraFrames.grip(frame, 0.2, 0.8, 0.05, 0.05))
        assertEquals(Grip.MOVE, CameraFrames.grip(frame, 0.4, 0.5, 0.05, 0.05))
        assertNull(CameraFrames.grip(frame, 0.9, 0.1, 0.05, 0.05))
        // A frame smaller than the reach: the corner nearest the touch.
        val small = CameraFrame(0.5, 0.5, 0.05, 0.05)
        assertEquals(Grip.SE, CameraFrames.grip(small, 0.56, 0.56, 0.05, 0.05))
    }

    @Test fun thePreviewShowsTheFramesPartOfThePicture() {
        assertEquals(CameraFrames.PixelRect(384, 324, 1152, 864), CameraFrames.crop(frame, 1920, 1080))
        assertEquals(CameraFrames.PixelRect(0, 0, 1920, 1080), CameraFrames.crop(CameraFrames.WHOLE, 1920, 1080))
        // Never an empty rectangle, never outside the picture.
        assertEquals(CameraFrames.PixelRect(9, 9, 10, 10), CameraFrames.crop(CameraFrame(0.95, 0.95, 0.05, 0.05), 10, 10))
    }

    @Test fun aPictureIsDecodedAtMostAsLargeAsShown() {
        assertEquals(1, CameraFrames.sampleSize(240, 135, 480))
        assertEquals(1, CameraFrames.sampleSize(1600, 900, 1600))
        assertEquals(2, CameraFrames.sampleSize(1920, 1080, 1600))
        assertEquals(4, CameraFrames.sampleSize(3840, 2160, 1600))
        assertEquals(1, CameraFrames.sampleSize(0, 0, 1600))
    }
}
