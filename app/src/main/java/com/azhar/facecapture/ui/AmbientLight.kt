package com.azhar.facecapture.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Decides from ambient-light readings whether it is too dark for the camera to see a face.
 *
 * Darkness switches on the white fill light, and that light reaches the sensor too (straight through the display, and
 * reflected off the user's face). Two things keep the fill light from switching itself off and on in a loop:
 *  - The thresholds are far apart: it takes much more light to end darkness than the fill light normally adds.
 *  - If darkness returns right after it ended, the light that ended it was the fill light's own (e.g. the phone is
 *    very close to a face or a wall). From then on it stays dark, so the screen flashes at most once.
 *
 * Not thread-safe; call from one thread. Make a new one to start over.
 */
internal class DarknessDetector(
    private val darkBelowLux: Float = DARK_BELOW_LUX,
    private val brightAboveLux: Float = BRIGHT_ABOVE_LUX,
    private val feedbackWindowMs: Long = FEEDBACK_WINDOW_MS,
) {
    var isDark = false
        private set

    /** Set once the fill light's own light has been mistaken for brightness; darkness is then permanent. */
    private var latchedDark = false
    private var brightenedAtMs: Long? = null

    /**
     * Feeds one light-sensor reading; returns whether it is dark now.
     *
     * @param nowMs time of the reading in milliseconds on a monotonic clock.
     */
    fun onLux(lux: Float, nowMs: Long): Boolean {
        if (isDark) {
            if (!latchedDark && lux >= brightAboveLux) {
                isDark = false
                brightenedAtMs = nowMs
            }
        } else if (lux < darkBelowLux) {
            isDark = true
            val brightenedAt = brightenedAtMs
            latchedDark = brightenedAt != null && nowMs - brightenedAt < feedbackWindowMs
        }
        return isDark
    }

    private companion object {
        const val DARK_BELOW_LUX = 10f // a room lit only by a screen or a street lamp

        // A normally lit room. Measured on a Nothing A015: the fill light itself adds 15 to 25 lux at arm's length.
        const val BRIGHT_ABOVE_LUX = 60f

        // Longer than the fill light takes to switch off and the sensor to notice (debounce + a few readings).
        const val FEEDBACK_WINDOW_MS = 3_000L
    }
}

/**
 * Whether the surroundings are dark, according to the ambient light sensor. It sits on the front of the phone beside
 * the camera, so it measures the light that falls on the user's face. Always false on a device without one.
 *
 * Listens only while the screen is resumed, and starts over on every resume: it reports "not dark" while paused, so
 * the first reading after coming back is taken without the fill light's own contribution.
 */
@Composable
internal fun rememberIsDark(): Boolean {
    val context = LocalContext.current
    var isDark by remember { mutableStateOf(false) }

    LifecycleResumeEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val detector = DarknessDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                isDark = detector.onLux(event.values[0], SystemClock.elapsedRealtime())
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (lightSensor != null) {
            // Events arrive on the main thread. A new listener is sent the current reading straight away.
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onPauseOrDispose {
            sensorManager.unregisterListener(listener)
            isDark = false
        }
    }
    return isDark
}
