package com.azhar.facecapture.face

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Smallest face ML Kit reports, as a fraction of the analysis-image width (ML Kit's default). Keep it below
 * CaptureEngine's "move closer" threshold: that one is a fraction of the view width, which FILL_CENTER makes larger
 * than the same face's fraction of the image, so a higher floor would hide faces before "move closer" could show.
 */
private const val MIN_FACE_SIZE = 0.1f

/** A landmark closer to the frame edge than this fraction of the shorter frame side counts as cut off. */
private const val EDGE_MARGIN_FRACTION = 0.01f

/**
 * Detector with contours + landmarks + classification (smile / eyes open). The caller owns it and must close() it.
 *
 * In face-detection 16.1.7 the native contour pipeline cannot classify, so with CONTOUR_MODE_ALL ML Kit runs two
 * native detectors (contours only, and landmarks + classification) and copies the contours onto the landmark face
 * whose box overlaps: all three outputs arrive on one [Face], at the cost of two passes per frame. The result list is
 * unordered and may hold several faces (only the most prominent one gets contours), so pick the largest.
 */
fun createFaceDetector(): FaceDetector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setMinFaceSize(MIN_FACE_SIZE)
        .build(),
)

/**
 * Maps an ML Kit face whose coordinates are already in PreviewView space to a [FaceSample].
 * Part names follow ML Kit (the subject's own left/right), not necessarily the viewer's left/right on screen.
 *
 * A part is visible when its landmark(s) exist and lie inside the frame, inset by a small margin.
 * Limitation: ML Kit can still report landmarks for parts hidden by a hand, mask or hair, so as a heuristic the eyes
 * and mouth also need a non-null open / smile probability (a covered eye or mouth often cannot be classified). Those
 * probabilities are also null for a strongly turned head, so judge head pose before trusting "hidden".
 */
fun Face.toFaceSample(frameWidth: Float, frameHeight: Float): FaceSample {
    val smile = smilingProbability.validProbability()
    val leftEye = leftEyeOpenProbability.validProbability()
    val rightEye = rightEyeOpenProbability.validProbability()

    val margin = EDGE_MARGIN_FRACTION * minOf(frameWidth, frameHeight)
    fun inFrame(vararg landmarkTypes: Int) = landmarkTypes.all { type ->
        val p = getLandmark(type)?.position
        p != null && p.x in margin..(frameWidth - margin) && p.y in margin..(frameHeight - margin)
    }

    val visibleParts = buildSet {
        if (leftEye != null && inFrame(FaceLandmark.LEFT_EYE)) add(FacePart.LEFT_EYE)
        if (rightEye != null && inFrame(FaceLandmark.RIGHT_EYE)) add(FacePart.RIGHT_EYE)
        if (inFrame(FaceLandmark.NOSE_BASE)) add(FacePart.NOSE)
        if (smile != null &&
            inFrame(FaceLandmark.MOUTH_BOTTOM, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT)
        ) {
            add(FacePart.MOUTH)
        }
        if (inFrame(FaceLandmark.LEFT_CHEEK)) add(FacePart.LEFT_CHEEK)
        if (inFrame(FaceLandmark.RIGHT_CHEEK)) add(FacePart.RIGHT_CHEEK)
    }

    val box = boundingBox
    return FaceSample(
        bounds = Box(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        visibleParts = visibleParts,
        smilingProbability = smile,
        leftEyeOpenProbability = leftEye,
        rightEyeOpenProbability = rightEye,
        headYaw = headEulerAngleY,
        headPitch = headEulerAngleX,
        contours = allContours.map { contour -> contour.points.map { Point(it.x, it.y) } },
    )
}

/**
 * Keeps only real probabilities. ML Kit 16.1.7's getLeftEyeOpenProbability() range-checks the smile value instead of
 * the eye's own, so an unclassified left eye can leak out as -1 instead of null.
 */
private fun Float?.validProbability(): Float? = this?.takeIf { it in 0f..1f }
