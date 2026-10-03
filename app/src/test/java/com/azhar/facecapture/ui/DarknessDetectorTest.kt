package com.azhar.facecapture.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarknessDetectorTest {

    private val detector = DarknessDetector(darkBelowLux = 10f, brightAboveLux = 60f, feedbackWindowMs = 3_000L)
    private var clockMs = 0L

    @Test
    fun `starts out not dark`() {
        assertFalse(detector.isDark)
    }

    @Test
    fun `turns dark only below the dark threshold`() {
        assertFalse(lux(200f))
        assertFalse(lux(10f))
        assertTrue(lux(9f))
    }

    @Test
    fun `stays dark while the reading is between the thresholds`() {
        lux(0f)

        // The fill light it switched on adds some light of its own; that must not switch it off again.
        assertTrue(lux(10f))
        assertTrue(lux(30f))
        assertTrue(lux(59f))
    }

    @Test
    fun `stops being dark at the bright threshold and then needs real darkness again`() {
        lux(0f)

        assertFalse(lux(60f))
        assertFalse(lux(30f)) // between the thresholds, coming from bright: still not dark
    }

    @Test
    fun `darkness returning long after the light came on is ordinary darkness`() {
        lux(0f)
        lux(300f) // the room light is switched on
        assertTrue(lux(0f, afterMs = 60_000L)) // and off again a minute later

        assertFalse(lux(300f)) // the next time it gets bright, the fill light goes off as usual
    }

    @Test
    fun `darkness returning right after brightness means the brightness was the fill light and latches dark`() {
        lux(0f) // dark: the fill light comes on
        assertFalse(lux(200f, afterMs = 700L)) // its own light reflects off something close
        assertTrue(lux(0f, afterMs = 700L)) // the fill light went off, and it is dark again

        // No more switching, whatever the fill light now adds.
        assertTrue(lux(200f, afterMs = 700L))
        assertTrue(lux(1_000f, afterMs = 60_000L))
    }

    @Test
    fun `a new detector starts without the latch`() {
        lux(0f)
        lux(200f, afterMs = 700L)
        lux(0f, afterMs = 700L) // latched

        val fresh = DarknessDetector(darkBelowLux = 10f, brightAboveLux = 60f, feedbackWindowMs = 3_000L)

        assertTrue(fresh.onLux(0f, nowMs = 0L))
        assertFalse(fresh.onLux(200f, nowMs = 100L))
    }

    /** Feeds one reading [afterMs] after the previous one. */
    private fun lux(lux: Float, afterMs: Long = 100L): Boolean {
        clockMs += afterMs
        return detector.onLux(lux, clockMs)
    }
}
