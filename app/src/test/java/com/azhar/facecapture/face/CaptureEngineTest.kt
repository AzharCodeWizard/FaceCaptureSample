package com.azhar.facecapture.face

import com.azhar.facecapture.face.CaptureTrigger.BLINK
import com.azhar.facecapture.face.CaptureTrigger.SMILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureEngineTest {

    private val engine = CaptureEngine()
    private var clockMs = 0L

    // Every face below is Ready (frontal, close, fully visible) unless a test says otherwise.
    private val neutral = faceSample() // eyes open, not smiling
    private val eyesClosed = faceSample(leftEyeOpen = 0.05f)
    private val eyesHalfOpen = faceSample(leftEyeOpen = 0.5f)
    private val smiling = faceSample(smile = 0.95f)

    // ---- Guidance ----

    @Test
    fun `no face means NoFace`() {
        val result = frame(null)

        assertEquals(Guidance.NoFace, result.guidance)
        assertNull(result.face)
        assertNull(result.trigger)
    }

    @Test
    fun `a frontal close fully visible face is Ready`() {
        assertEquals(Guidance.Ready, guidanceOf(neutral))
    }

    @Test
    fun `face too small or too big for the oval means MoveCloser or MoveBack`() {
        assertEquals(Guidance.MoveCloser, guidanceOf(faceSample(widthInOval = 0.58f)))
        assertEquals(Guidance.Ready, guidanceOf(faceSample(widthInOval = 0.62f)))
        assertEquals(Guidance.Ready, guidanceOf(faceSample(widthInOval = 1.08f)))
        assertEquals(Guidance.MoveBack, guidanceOf(faceSample(widthInOval = 1.12f)))
    }

    @Test
    fun `an off-centre face is told which way to move`() {
        assertEquals(Guidance.Move(Direction.LEFT), guidanceOf(faceSample(offsetX = 0.16f)))
        assertEquals(Guidance.Move(Direction.RIGHT), guidanceOf(faceSample(offsetX = -0.16f)))
        assertEquals(Guidance.Move(Direction.UP), guidanceOf(faceSample(offsetY = 0.16f)))
        assertEquals(Guidance.Move(Direction.DOWN), guidanceOf(faceSample(offsetY = -0.16f)))
        assertEquals(Guidance.Ready, guidanceOf(faceSample(offsetX = 0.14f, offsetY = -0.14f)))
    }

    @Test
    fun `the axis that is further off is fixed first`() {
        assertEquals(Guidance.Move(Direction.LEFT), guidanceOf(faceSample(offsetX = 0.3f, offsetY = 0.2f)))
        assertEquals(Guidance.Move(Direction.DOWN), guidanceOf(faceSample(offsetX = 0.2f, offsetY = -0.3f)))
    }

    @Test
    fun `head turned or tilted beyond its limit means LookAtCamera`() {
        assertEquals(Guidance.LookAtCamera, guidanceOf(faceSample(yaw = 19f)))
        assertEquals(Guidance.LookAtCamera, guidanceOf(faceSample(yaw = -19f)))
        assertEquals(Guidance.LookAtCamera, guidanceOf(faceSample(pitch = 21f)))
        assertEquals(Guidance.LookAtCamera, guidanceOf(faceSample(pitch = -21f)))
        assertEquals(Guidance.Ready, guidanceOf(faceSample(yaw = 18f, pitch = 20f)))
        assertEquals(Guidance.Ready, guidanceOf(faceSample(yaw = -18f, pitch = -20f)))
    }

    @Test
    fun `PartsHidden lists exactly the missing parts`() {
        val twoMissing = faceSample(visibleParts = ALL_PARTS - setOf(FacePart.RIGHT_EYE, FacePart.MOUTH))
        assertEquals(
            Guidance.PartsHidden(setOf(FacePart.RIGHT_EYE, FacePart.MOUTH)),
            guidanceOf(twoMissing),
        )
        for (part in FacePart.entries) {
            val onlyOneMissing = faceSample(visibleParts = ALL_PARTS - part)
            assertEquals(Guidance.PartsHidden(setOf(part)), guidanceOf(onlyOneMissing))
        }
        assertEquals(Guidance.PartsHidden(ALL_PARTS), guidanceOf(faceSample(visibleParts = emptySet())))
    }

    @Test
    fun `guidance priority is position then distance then pose then hidden parts`() {
        val noParts = emptySet<FacePart>()

        assertEquals(
            Guidance.Move(Direction.LEFT),
            guidanceOf(faceSample(offsetX = 0.3f, widthInOval = 0.3f, yaw = 40f, visibleParts = noParts)),
        )
        assertEquals(Guidance.MoveCloser, guidanceOf(faceSample(widthInOval = 0.3f, yaw = 40f, visibleParts = noParts)))
        assertEquals(Guidance.MoveBack, guidanceOf(faceSample(widthInOval = 1.5f, yaw = 40f, visibleParts = noParts)))
        assertEquals(Guidance.LookAtCamera, guidanceOf(faceSample(yaw = 40f, visibleParts = noParts)))
        assertEquals(Guidance.PartsHidden(ALL_PARTS), guidanceOf(faceSample(visibleParts = noParts)))
    }

    @Test
    fun `result carries the input face`() {
        assertSame(neutral, frame(neutral).face)

        val turnedAway = faceSample(yaw = 40f)
        assertSame(turnedAway, frame(turnedAway).face)
    }

    // ---- Blink ----

    @Test
    fun `blink fires once on open closed open`() {
        val triggers = triggersFrom(neutral, neutral, eyesClosed, eyesClosed, neutral, neutral, neutral)

        assertEquals(listOf(BLINK), triggers)
    }

    @Test
    fun `blink fires on the frame the eyes reopen`() {
        assertNull(frame(neutral).trigger)
        assertNull(frame(eyesClosed).trigger)
        assertEquals(BLINK, frame(neutral).trigger)
        assertNull(frame(neutral).trigger)
    }

    @Test
    fun `blink fires through half open frames while closing and opening`() {
        assertEquals(listOf(BLINK), triggersFrom(neutral, eyesHalfOpen, eyesClosed, eyesHalfOpen, neutral))
    }

    @Test
    fun `eyes that start closed are not a blink until they have been seen open`() {
        assertEquals(NO_TRIGGERS, triggersFrom(eyesClosed, eyesClosed, neutral, neutral))
        assertEquals(listOf(BLINK), triggersFrom(eyesClosed, neutral))
    }

    @Test
    fun `a wink is not a blink`() {
        val leftWink = faceSample(leftEyeOpen = 0.05f, rightEyeOpen = 0.95f)

        assertEquals(NO_TRIGGERS, triggersFrom(neutral, leftWink, neutral))
    }

    @Test
    fun `eyes exactly at the thresholds count as open and closed`() {
        val atOpenThreshold = faceSample(leftEyeOpen = 0.7f)
        val atClosedThreshold = faceSample(leftEyeOpen = 0.3f)

        assertEquals(listOf(BLINK), triggersFrom(atOpenThreshold, atClosedThreshold, atOpenThreshold))
    }

    @Test
    fun `eyes between the thresholds are neither open nor closed`() {
        assertEquals(NO_TRIGGERS, triggersFrom(eyesHalfOpen, eyesClosed, eyesHalfOpen)) // never seen open
        assertEquals(NO_TRIGGERS, triggersFrom(neutral, eyesHalfOpen, neutral)) // never seen closed
    }

    @Test
    fun `eyes held shut are not a blink`() {
        frame(neutral)
        repeat(40) { assertNull(frame(eyesClosed).trigger) } // 40 frames x 33 ms = 1.3 s
        assertNull(frame(neutral).trigger)

        assertEquals(listOf(BLINK), triggersFrom(eyesClosed, neutral)) // the next real blink still works
    }

    @Test
    fun `blink closure limit is about a second`() {
        assertEquals(BLINK, CaptureEngine().blink(startMs = 0L, closedMs = 900L))
        assertNull(CaptureEngine().blink(startMs = 0L, closedMs = 1100L))
    }

    @Test
    fun `blink completed on a frame that is not Ready does not fire`() {
        for (notReady in notReadyFrames()) {
            engine.reset()
            frame(neutral)
            frame(eyesClosed)

            val reopening = frame(notReady)

            assertNotEquals(Guidance.Ready, reopening.guidance)
            assertNull(reopening.trigger)
            assertNull(frame(neutral).trigger) // the gesture is gone, so Ready again does not fire either
        }
    }

    @Test
    fun `a closure seen while not Ready is not a blink`() {
        for (notReady in notReadyFrames(leftEyeOpen = 0.05f)) {
            engine.reset()
            frame(neutral)

            assertNull(frame(notReady).trigger)
            assertNull(frame(neutral).trigger)
        }
    }

    @Test
    fun `a frame that is not Ready interrupts a blink in progress`() {
        for (notReady in notReadyFrames(leftEyeOpen = 0.05f)) {
            engine.reset()

            assertEquals(NO_TRIGGERS, triggersFrom(neutral, eyesClosed, notReady, eyesClosed, neutral))
            assertEquals(listOf(BLINK), triggersFrom(eyesClosed, neutral)) // detection recovers afterwards
        }
    }

    // ---- Smile ----

    @Test
    fun `smile fires on the second consecutive smiling frame`() {
        assertNull(frame(smiling).trigger)
        assertEquals(SMILE, frame(smiling).trigger)
    }

    @Test
    fun `a single smiling frame does not fire`() {
        assertEquals(NO_TRIGGERS, triggersFrom(neutral, smiling, neutral, neutral))
    }

    @Test
    fun `smiling frames must be consecutive`() {
        val halfSmile = faceSample(smile = 0.5f)

        assertEquals(NO_TRIGGERS, triggersFrom(smiling, halfSmile, smiling, halfSmile, smiling))
    }

    @Test
    fun `a smile probability exactly at the threshold counts`() {
        val atThreshold = faceSample(smile = 0.8f)

        assertEquals(listOf(SMILE), triggersFrom(atThreshold, atThreshold))
    }

    @Test
    fun `a smile probability just below the threshold does not count`() {
        assertEquals(NO_TRIGGERS, hold(faceSample(smile = 0.79f), frames = 10))
    }

    @Test
    fun `a held smile fires only once`() {
        assertEquals(listOf(SMILE), hold(smiling, frames = 300)) // ~10 s, far longer than the cooldown
    }

    @Test
    fun `a held smile that wobbles above the fade threshold still fires only once`() {
        val wobble = faceSample(smile = 0.5f)

        val triggers = List(300) { frame(if (it % 3 == 2) wobble else smiling).trigger }.filterNotNull()

        assertEquals(listOf(SMILE), triggers)
    }

    @Test
    fun `guidance flicker during a held smile does not make it fire again`() {
        val flicker = faceSample(visibleParts = ALL_PARTS - FacePart.MOUTH, smile = 0.95f)
        assertEquals(listOf(SMILE), hold(smiling, frames = 5))

        // A not Ready frame every 10th frame for ~10 s: the smile is the same one, so no second photo.
        val triggers = List(300) { frame(if (it % 10 == 9) flicker else smiling).trigger }.filterNotNull()

        assertEquals(NO_TRIGGERS, triggers)
    }

    @Test
    fun `smile re-arms after the smile fades`() {
        assertEquals(listOf(SMILE), triggersFrom(smiling, smiling, smiling))
        advanceClock(COOLDOWN_ELAPSED_MS)

        assertEquals(NO_TRIGGERS, triggersFrom(smiling, smiling)) // same smile, cooldown over: still no photo
        assertEquals(NO_TRIGGERS, triggersFrom(neutral, neutral)) // the smile fades
        assertEquals(listOf(SMILE), triggersFrom(smiling, smiling)) // a new smile fires
    }

    @Test
    fun `a dip that stays above the fade threshold does not re-arm the smile`() {
        assertEquals(listOf(SMILE), triggersFrom(smiling, smiling))
        advanceClock(COOLDOWN_ELAPSED_MS)

        val dip = faceSample(smile = 0.5f)

        assertEquals(NO_TRIGGERS, triggersFrom(dip, dip, smiling, smiling, smiling))
    }

    @Test
    fun `smiling while not Ready never fires and a not Ready frame resets the streak`() {
        for (notReady in notReadyFrames(smile = 0.95f)) {
            engine.reset()
            assertEquals(NO_TRIGGERS, hold(notReady, frames = 10))

            assertNull(frame(smiling).trigger) // streak of 1
            assertNull(frame(notReady).trigger) // not Ready: the streak is dropped
            assertNull(frame(smiling).trigger) // streak of 1 again
            assertEquals(SMILE, frame(smiling).trigger) // two consecutive Ready frames
        }
    }

    // ---- Cooldown and trigger precedence ----

    @Test
    fun `no trigger fires during the cooldown and triggers resume after it`() {
        assertEquals(BLINK, engine.blink(startMs = 0L)) // fires at 200 ms, cooldown until 2200 ms
        assertNull(engine.blink(startMs = 1000L)) // reopens at 1200 ms
        assertNull(engine.blink(startMs = 1900L)) // reopens at 2100 ms, just before the cooldown ends
        assertEquals(BLINK, engine.blink(startMs = 2200L)) // reopens at 2400 ms
    }

    @Test
    fun `cooldown length comes from the constructor`() {
        val shortCooldown = CaptureEngine(cooldownMs = 500L)

        assertEquals(BLINK, shortCooldown.blink(startMs = 0L))
        assertEquals(BLINK, shortCooldown.blink(startMs = 1000L)) // the default 2 s cooldown would block this
    }

    @Test
    fun `a smile during the cooldown of a blink waits for the cooldown to end`() {
        assertEquals(listOf(BLINK), triggersFrom(neutral, eyesClosed, neutral)) // cooldown until ~2.1 s

        assertEquals(NO_TRIGGERS, hold(smiling, frames = 50)) // ~1.7 s of smiling, still cooling down
        assertEquals(listOf(SMILE), hold(smiling, frames = 50)) // the same smile fires once the cooldown ends
    }

    @Test
    fun `blink wins over a smile completing on the same frame and the held smile does not fire later`() {
        val smilingEyesClosed = faceSample(leftEyeOpen = 0.05f, smile = 0.95f)

        // On the third frame the eyes reopen (blink) and the smile reaches its second frame.
        assertEquals(listOf(BLINK), triggersFrom(neutral, smilingEyesClosed, smiling))
        assertEquals(NO_TRIGGERS, hold(smiling, frames = 300)) // ~10 s, well past the cooldown
    }

    // ---- Settings ----

    @Test
    fun `blink does not fire when blink capture is disabled`() {
        val smileOnly = CaptureSettings(blinkEnabled = false)

        assertEquals(NO_TRIGGERS, triggersFrom(neutral, eyesClosed, neutral, settings = smileOnly))
    }

    @Test
    fun `smile does not fire when smile capture is disabled`() {
        val blinkOnly = CaptureSettings(smileEnabled = false)

        assertEquals(NO_TRIGGERS, hold(smiling, frames = 10, settings = blinkOnly))
    }

    @Test
    fun `nothing fires but guidance still works when both triggers are disabled`() {
        val off = CaptureSettings(blinkEnabled = false, smileEnabled = false)

        assertEquals(NO_TRIGGERS, triggersFrom(neutral, eyesClosed, neutral, smiling, smiling, settings = off))
        assertEquals(Guidance.Ready, frame(neutral, off).guidance)
        assertEquals(Guidance.NoFace, frame(null, off).guidance)
    }

    @Test
    fun `each trigger still fires when the other one is disabled`() {
        assertEquals(listOf(SMILE), hold(smiling, frames = 5, settings = CaptureSettings(blinkEnabled = false)))

        engine.reset()
        val blinkOnly = CaptureSettings(smileEnabled = false)
        assertEquals(listOf(BLINK), triggersFrom(neutral, eyesClosed, neutral, settings = blinkOnly))
    }

    // ---- Unknown probabilities ----

    @Test
    fun `null probabilities never fire`() {
        val unclassified = faceSample(smile = null, leftEyeOpen = null)

        assertEquals(NO_TRIGGERS, hold(unclassified, frames = 100))
    }

    @Test
    fun `null eye probabilities are neither open nor closed`() {
        val unknownEyes = faceSample(leftEyeOpen = null)
        val oneEyeUnknown = faceSample(leftEyeOpen = 0.05f, rightEyeOpen = null)

        assertEquals(NO_TRIGGERS, triggersFrom(unknownEyes, eyesClosed, unknownEyes)) // never seen open
        assertEquals(NO_TRIGGERS, triggersFrom(neutral, unknownEyes, neutral)) // never seen closed
        assertEquals(NO_TRIGGERS, triggersFrom(neutral, oneEyeUnknown, neutral)) // not both closed
    }

    @Test
    fun `a null smile probability is not a smile and breaks the streak`() {
        val unknownSmile = faceSample(smile = null)

        assertEquals(NO_TRIGGERS, hold(unknownSmile, frames = 10))
        assertEquals(NO_TRIGGERS, triggersFrom(smiling, unknownSmile, smiling))
    }

    // ---- Reset ----

    @Test
    fun `reset drops a blink in progress`() {
        frame(neutral)
        frame(eyesClosed)

        engine.reset()

        assertNull(frame(neutral).trigger)
    }

    @Test
    fun `reset clears the cooldown`() {
        assertEquals(listOf(BLINK), triggersFrom(neutral, eyesClosed, neutral))

        engine.reset()

        assertEquals(listOf(BLINK), triggersFrom(neutral, eyesClosed, neutral)) // would be blocked without reset
    }

    @Test
    fun `reset makes a held smile eligible again`() {
        assertEquals(listOf(SMILE), hold(smiling, frames = 10))
        assertEquals(NO_TRIGGERS, hold(smiling, frames = 10))

        engine.reset()

        assertEquals(listOf(SMILE), hold(smiling, frames = 10))
    }

    // ---- Helpers ----

    /** Feeds one frame, one [FRAME_INTERVAL_MS] after the previous one. */
    private fun frame(face: FaceSample?, settings: CaptureSettings = BOTH_ENABLED): FrameResult {
        clockMs += FRAME_INTERVAL_MS
        return engine.onFrame(face, settings, clockMs)
    }

    private fun guidanceOf(face: FaceSample?): Guidance = frame(face).guidance

    /** Feeds [faces] as consecutive frames; returns the triggers that fired, in order. */
    private fun triggersFrom(
        vararg faces: FaceSample?,
        settings: CaptureSettings = BOTH_ENABLED,
    ): List<CaptureTrigger> = faces.mapNotNull { frame(it, settings).trigger }

    /** Holds [face] in front of the camera for [frames] frames; returns the triggers that fired. */
    private fun hold(
        face: FaceSample?,
        frames: Int,
        settings: CaptureSettings = BOTH_ENABLED,
    ): List<CaptureTrigger> = List(frames) { frame(face, settings).trigger }.filterNotNull()

    private fun advanceClock(ms: Long) {
        clockMs += ms
    }

    /**
     * Feeds open eyes at [startMs], closed eyes 100 ms later and open eyes again [closedMs] after that,
     * using explicit timestamps. Returns the trigger fired as the eyes reopen.
     */
    private fun CaptureEngine.blink(startMs: Long, closedMs: Long = 100L): CaptureTrigger? {
        onFrame(neutral, BOTH_ENABLED, startMs)
        onFrame(eyesClosed, BOTH_ENABLED, startMs + 100L)
        return onFrame(neutral, BOTH_ENABLED, startMs + 100L + closedMs).trigger
    }

    /** One frame per non-Ready guidance (NoFace, Move, MoveCloser, MoveBack, LookAtCamera, PartsHidden). */
    private fun notReadyFrames(leftEyeOpen: Float = 0.95f, smile: Float = 0.1f): List<FaceSample?> = listOf(
        null,
        faceSample(offsetX = 0.3f, leftEyeOpen = leftEyeOpen, smile = smile),
        faceSample(widthInOval = 0.3f, leftEyeOpen = leftEyeOpen, smile = smile),
        faceSample(widthInOval = 1.5f, leftEyeOpen = leftEyeOpen, smile = smile),
        faceSample(yaw = 40f, leftEyeOpen = leftEyeOpen, smile = smile),
        faceSample(visibleParts = ALL_PARTS - FacePart.MOUTH, leftEyeOpen = leftEyeOpen, smile = smile),
    )

    private companion object {
        const val FRAME_WIDTH = 1000f
        const val FRAME_HEIGHT = 1600f
        const val FRAME_INTERVAL_MS = 33L // roughly 30 fps
        const val COOLDOWN_ELAPSED_MS = 3_000L // comfortably longer than the default 2 s cooldown

        val ALL_PARTS = FacePart.entries.toSet()
        val BOTH_ENABLED = CaptureSettings()
        val NO_TRIGGERS = emptyList<CaptureTrigger>()

        val OVAL = guideOval(FRAME_WIDTH, FRAME_HEIGHT)

        /**
         * A frontal face centred in the oval at 80% of its width, with every part visible, eyes open and a neutral
         * expression, i.e. guidance Ready and nothing to detect. Offsets move the face centre by a share of the
         * oval's width (x, + = right) or height (y, + = down). The right eye defaults to the left eye's value.
         */
        fun faceSample(
            widthInOval: Float = 0.8f,
            offsetX: Float = 0f,
            offsetY: Float = 0f,
            visibleParts: Set<FacePart> = ALL_PARTS,
            smile: Float? = 0.1f,
            leftEyeOpen: Float? = 0.95f,
            rightEyeOpen: Float? = leftEyeOpen,
            yaw: Float = 0f,
            pitch: Float = 0f,
        ) = FaceSample(
            bounds = squareBox(
                centerX = OVAL.centerX + offsetX * OVAL.width,
                centerY = OVAL.centerY + offsetY * OVAL.height,
                size = OVAL.width * widthInOval, // ML Kit face boxes are about square
            ),
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT,
            visibleParts = visibleParts,
            smilingProbability = smile,
            leftEyeOpenProbability = leftEyeOpen,
            rightEyeOpenProbability = rightEyeOpen,
            headYaw = yaw,
            headPitch = pitch,
            contours = emptyList(),
        )

        fun squareBox(centerX: Float, centerY: Float, size: Float) =
            Box(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2)
    }
}
