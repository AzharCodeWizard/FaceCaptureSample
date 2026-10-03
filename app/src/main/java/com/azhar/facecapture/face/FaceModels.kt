package com.azhar.facecapture.face

/**
 * Shared contract between the ML Kit layer (FaceSampleMapper / CaptureEngine)
 * and the UI layer (CameraScreen). Pure Kotlin so CaptureEngine is unit-testable on the JVM.
 * All coordinates are in PreviewView (view) space, already mirrored for the front camera.
 */

data class Point(val x: Float, val y: Float)

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * The oval the user fits their face into, in PreviewView px for a [frameWidth] x [frameHeight] preview.
 * The overlay draws it and CaptureEngine measures the face against it, so this is the one definition of both.
 */
fun guideOval(frameWidth: Float, frameHeight: Float): Box {
    val height = minOf(frameWidth * OVAL_MAX_WIDTH_FRACTION * OVAL_ASPECT, frameHeight * OVAL_MAX_HEIGHT_FRACTION)
    val width = height / OVAL_ASPECT
    val centerX = frameWidth / 2
    val centerY = frameHeight * OVAL_CENTER_Y_FRACTION
    return Box(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
}

private const val OVAL_ASPECT = 1.3f // height / width, roughly a face
private const val OVAL_MAX_WIDTH_FRACTION = 0.72f
private const val OVAL_MAX_HEIGHT_FRACTION = 0.5f
private const val OVAL_CENTER_Y_FRACTION = 0.45f // a little above the middle, clear of the bottom controls

/**
 * A direction on screen. The front-camera preview is mirrored, so it is also the user's own left / right:
 * moving to your left moves your face to the left of the screen.
 */
enum class Direction { LEFT, RIGHT, UP, DOWN }

enum class FacePart(val label: String) {
    LEFT_EYE("left eye"),
    RIGHT_EYE("right eye"),
    NOSE("nose"),
    MOUTH("mouth"),
    LEFT_CHEEK("left cheek"),
    RIGHT_CHEEK("right cheek"),
}

/** One detected face, reduced to what the capture logic and overlay need. */
data class FaceSample(
    val bounds: Box,
    val frameWidth: Float,
    val frameHeight: Float,
    /** Parts whose ML Kit landmark was detected AND lies inside the frame. */
    val visibleParts: Set<FacePart>,
    /** ML Kit classification probabilities; null when ML Kit could not classify (e.g. face turned). */
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    /** Head Euler angles in degrees (Y = yaw, X = pitch). */
    val headYaw: Float,
    val headPitch: Float,
    /** Face contour polylines for drawing (face oval, eyes, brows, lips, nose). */
    val contours: List<List<Point>>,
)

/** What the user should be told right now. */
sealed interface Guidance {
    data object NoFace : Guidance

    /** The face is off-centre in the oval; moving [direction] brings it back. */
    data class Move(val direction: Direction) : Guidance

    /** The face is too small for the oval. */
    data object MoveCloser : Guidance

    /** The face is too big for the oval. */
    data object MoveBack : Guidance
    data object LookAtCamera : Guidance
    data class PartsHidden(val parts: Set<FacePart>) : Guidance
    /** Face is centred in the oval, fully visible and frontal; auto-capture triggers are armed. */
    data object Ready : Guidance
}

enum class CaptureTrigger { BLINK, SMILE }

/** Which auto-capture triggers the user has enabled. */
data class CaptureSettings(
    val blinkEnabled: Boolean = true,
    val smileEnabled: Boolean = true,
)

/** Result of feeding one analyzed frame to CaptureEngine. */
data class FrameResult(
    val guidance: Guidance,
    /** The tracked face (ML Kit contour mode tracks only the most prominent face). */
    val face: FaceSample?,
    /** Non-null exactly once per detected blink/smile gesture (after cooldown). */
    val trigger: CaptureTrigger?,
)
