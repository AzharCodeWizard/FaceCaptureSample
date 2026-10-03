package com.azhar.facecapture.face

import kotlin.math.abs

/**
 * Turns analyzed camera frames into on-screen [Guidance] and auto-capture [CaptureTrigger]s.
 * Guidance steers the face into the [guideOval]: centred, at the right distance, frontal and uncovered.
 *
 * Triggers only fire while guidance is [Guidance.Ready]; every other frame drops the gestures in
 * progress, so a blink "seen" while part of the face is hidden can never fire.
 *  - **Blink**: eyes clearly open, then clearly closed, then open again; fires as the eyes reopen.
 *    Eyes held shut for longer than a blink are ignored.
 *  - **Smile**: a strong smile on consecutive frames. A smile that was already being held when a
 *    photo was taken must fade before it can fire again, so holding one smile yields one photo.
 *
 * After any trigger nothing fires for [cooldownMs]. At most one trigger fires per frame (blink wins).
 *
 * Pure Kotlin (no android.*), so it runs in JVM unit tests.
 * **Not thread-safe**: call [onFrame] and [reset] from one thread, e.g. the camera analyzer's executor.
 *
 * @param cooldownMs how long after a trigger further triggers are suppressed.
 */
class CaptureEngine(private val cooldownMs: Long = 2000L) {

    private var blinkPhase: BlinkPhase = BlinkPhase.Idle
    private var smileState = SmileState.NOT_SMILING
    private var consecutiveSmileFrames = 0
    private var cooldownEndsAtMs = Long.MIN_VALUE

    /**
     * Evaluates one analyzed frame.
     *
     * @param face the tracked face, or null if none was detected.
     * @param nowMs frame time in milliseconds on a monotonic clock; must not go backwards between calls.
     */
    fun onFrame(face: FaceSample?, settings: CaptureSettings, nowMs: Long): FrameResult {
        val guidance = guidanceFor(face)
        val trigger = if (face != null && guidance == Guidance.Ready) {
            triggerFor(face, settings, nowMs)
        } else {
            dropGesturesInProgress()
            null
        }
        return FrameResult(guidance = guidance, face = face, trigger = trigger)
    }

    /** Forgets every gesture and the cooldown, as if the engine had just been created. */
    fun reset() {
        dropGesturesInProgress()
        smileState = SmileState.NOT_SMILING
        cooldownEndsAtMs = Long.MIN_VALUE
    }

    /**
     * Checks run in priority order: the first problem found is the one the user is told about.
     * Position comes first (centre the face in the oval), then distance, then pose, then hidden parts.
     */
    private fun guidanceFor(face: FaceSample?): Guidance {
        if (face == null) return Guidance.NoFace
        val oval = guideOval(face.frameWidth, face.frameHeight)
        directionToOvalCenter(face.bounds, oval)?.let { return Guidance.Move(it) }
        val widthInOval = face.bounds.width / oval.width
        if (widthInOval < MIN_FACE_WIDTH_IN_OVAL) return Guidance.MoveCloser
        if (widthInOval > MAX_FACE_WIDTH_IN_OVAL) return Guidance.MoveBack
        if (abs(face.headYaw) > MAX_HEAD_YAW_DEGREES || abs(face.headPitch) > MAX_HEAD_PITCH_DEGREES) {
            return Guidance.LookAtCamera
        }
        val hiddenParts = FacePart.entries.toSet() - face.visibleParts
        return if (hiddenParts.isEmpty()) Guidance.Ready else Guidance.PartsHidden(hiddenParts)
    }

    /**
     * The way to move so the face centre lands near the oval centre, or null if it already does.
     * When both axes are off, the one that misses by more (relative to the tolerance) is fixed first.
     */
    private fun directionToOvalCenter(face: Box, oval: Box): Direction? {
        // In tolerances: |offset| > 1 is off-centre. Positive means the face is right of / below the oval centre.
        val offsetX = (face.centerX - oval.centerX) / (oval.width * MAX_CENTER_OFFSET_IN_OVAL)
        val offsetY = (face.centerY - oval.centerY) / (oval.height * MAX_CENTER_OFFSET_IN_OVAL)
        return when {
            abs(offsetX) <= 1f && abs(offsetY) <= 1f -> null
            abs(offsetX) >= abs(offsetY) -> if (offsetX > 0f) Direction.LEFT else Direction.RIGHT
            else -> if (offsetY > 0f) Direction.UP else Direction.DOWN
        }
    }

    private fun triggerFor(face: FaceSample, settings: CaptureSettings, nowMs: Long): CaptureTrigger? {
        // Both detectors advance on every Ready frame, even while their trigger is disabled or cooling down.
        val blinked = trackBlink(face, nowMs)
        val smiled = trackSmile(face)
        val trigger = when {
            nowMs < cooldownEndsAtMs -> null
            blinked && settings.blinkEnabled -> CaptureTrigger.BLINK
            smiled && settings.smileEnabled -> CaptureTrigger.SMILE
            else -> null
        }
        if (trigger != null) {
            cooldownEndsAtMs = nowMs + cooldownMs
            // A smile held through this capture already has its photo; it must fade before firing again.
            if (smileState == SmileState.SMILING) smileState = SmileState.SMILING_CAPTURED
        }
        return trigger
    }

    /** Advances the blink state machine; true on the frame where the eyes reopen after a genuine blink. */
    private fun trackBlink(face: FaceSample, nowMs: Long): Boolean {
        val eyes = eyeStateOf(face)
        var phase = blinkPhase
        if (phase is BlinkPhase.EyesClosed && nowMs - phase.sinceMs > MAX_BLINK_CLOSURE_MS) {
            phase = BlinkPhase.Idle // eyes held shut (or frames stalled), not a blink
        }
        blinkPhase = when {
            eyes == EyeState.OPEN -> BlinkPhase.EyesOpen
            eyes == EyeState.CLOSED && phase == BlinkPhase.EyesOpen -> BlinkPhase.EyesClosed(nowMs)
            else -> phase // unclear eyes (half closed, winking, unclassified) neither advance nor end a blink
        }
        return phase is BlinkPhase.EyesClosed && eyes == EyeState.OPEN
    }

    private fun eyeStateOf(face: FaceSample): EyeState {
        val left = face.leftEyeOpenProbability ?: return EyeState.UNCLEAR
        val right = face.rightEyeOpenProbability ?: return EyeState.UNCLEAR
        return when {
            minOf(left, right) >= EYES_OPEN_MIN_PROBABILITY -> EyeState.OPEN
            maxOf(left, right) <= EYES_CLOSED_MAX_PROBABILITY -> EyeState.CLOSED
            else -> EyeState.UNCLEAR
        }
    }

    /** Advances smile tracking; true while a smile that has not had its photo yet has lasted long enough. */
    private fun trackSmile(face: FaceSample): Boolean {
        val probability = face.smilingProbability
        val strong = probability != null && probability >= SMILE_MIN_PROBABILITY
        val faded = probability != null && probability < SMILE_FADED_BELOW_PROBABILITY
        consecutiveSmileFrames = if (strong) consecutiveSmileFrames + 1 else 0
        smileState = when {
            faded -> SmileState.NOT_SMILING
            strong && smileState == SmileState.NOT_SMILING -> SmileState.SMILING
            else -> smileState // in-between or unknown probabilities are hysteresis: the smile carries on
        }
        return smileState == SmileState.SMILING && consecutiveSmileFrames >= SMILE_MIN_CONSECUTIVE_FRAMES
    }

    /**
     * Ends gestures in progress. [smileState] deliberately survives: guidance flickering must not
     * make a smile that was already photographed eligible again.
     */
    private fun dropGesturesInProgress() {
        blinkPhase = BlinkPhase.Idle
        consecutiveSmileFrames = 0
    }

    private sealed interface BlinkPhase {
        /** No open eyes seen yet, so a closure now cannot be a blink. */
        data object Idle : BlinkPhase

        /** Eyes were seen open; closing them starts a blink. */
        data object EyesOpen : BlinkPhase

        /** Eyes closed at [sinceMs] after being open; reopening soon enough completes the blink. */
        data class EyesClosed(val sinceMs: Long) : BlinkPhase
    }

    /** Both eyes clearly open, both clearly closed, or anything else (including unknown). */
    private enum class EyeState { OPEN, CLOSED, UNCLEAR }

    /** Smile hysteresis: starts at a strong smile and only ends once the probability has faded. */
    private enum class SmileState {
        NOT_SMILING,
        SMILING,

        /** A photo was taken during this smile; it must fade before it can trigger another. */
        SMILING_CAPTURED,
    }

    private companion object {
        // Face box width as a share of the oval width. ML Kit's box spans about cheek to cheek, so a face that
        // fills the oval measures around 0.8.
        const val MIN_FACE_WIDTH_IN_OVAL = 0.6f
        const val MAX_FACE_WIDTH_IN_OVAL = 1.1f

        // How far the face centre may sit from the oval centre: this share of the oval's width / height.
        const val MAX_CENTER_OFFSET_IN_OVAL = 0.15f

        // ML Kit only classifies eyes and smile on near-frontal faces (about +-18 degrees of yaw); beyond
        // that their probabilities are null, so the user should be told to face the camera, not "parts hidden".
        const val MAX_HEAD_YAW_DEGREES = 18f
        const val MAX_HEAD_PITCH_DEGREES = 20f

        const val EYES_OPEN_MIN_PROBABILITY = 0.7f // both eyes at least this open
        const val EYES_CLOSED_MAX_PROBABILITY = 0.3f // both eyes at most this open
        const val MAX_BLINK_CLOSURE_MS = 1000L // a longer closure is eyes held shut, not a blink

        const val SMILE_MIN_PROBABILITY = 0.8f
        const val SMILE_MIN_CONSECUTIVE_FRAMES = 2
        const val SMILE_FADED_BELOW_PROBABILITY = 0.3f
    }
}
