package com.azhar.facecapture.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.azhar.facecapture.face.FaceSample
import com.azhar.facecapture.face.FrameResult
import com.azhar.facecapture.face.Point
import com.azhar.facecapture.face.guideOval
import kotlin.math.hypot

/** Status colours shared by the prompt banner and the face overlay, so the two always agree. */
internal val ReadyGreen = Color(0xFF34C759)
internal val WarningAmber = Color(0xFFFFB300)

/** How much the preview outside the oval is dimmed, so the oval reads as "put your face here". */
private const val SCRIM_ALPHA = 0.5f

/**
 * Draws the face guide over the camera preview: the [guideOval] with everything outside it dimmed (or turned into a
 * white fill light), plus the tracked face's contours.
 *
 * The canvas must have exactly the size and position of the PreviewView: the oval is computed from the canvas size
 * and [FaceSample] coordinates are already in PreviewView pixels (scaled and mirrored for the front camera by ML Kit),
 * so this draws exactly what CaptureEngine measures.
 *
 * @param frame latest analysis result. It is a lambda so the state behind it is read while drawing: a new frame
 *   (up to ~30 per second) invalidates only this canvas and never triggers a recomposition.
 * @param ready whether to use the "ready" colour (green) for the oval and contours instead of the warning colour
 *   (amber). The caller decides, so the overlay can follow the same debounced guidance as the prompt banner.
 * @param fillLight whether the area around the oval is solid white instead of dimmed, so that the screen lights the
 *   user's face in the dark.
 */
@Composable
fun FaceOverlay(
    frame: () -> FrameResult?,
    ready: Boolean,
    fillLight: Boolean,
    modifier: Modifier = Modifier,
) {
    // Keep the States themselves (no `by`) so the colours are read inside the draw block as well.
    val color = animateColorAsState(if (ready) ReadyGreen else WarningAmber, label = "faceOverlayColor")
    val scrim = animateColorAsState(
        if (fillLight) Color.White else Color.Black.copy(alpha = SCRIM_ALPHA),
        label = "faceOverlayScrim",
    )
    val ovalPath = remember { Path() }
    val contourPath = remember { Path() }

    Canvas(modifier) {
        val oval = guideOval(size.width, size.height)
        val ovalRect = Rect(oval.left, oval.top, oval.right, oval.bottom)
        ovalPath.rewind()
        ovalPath.addOval(ovalRect)
        clipPath(ovalPath, clipOp = ClipOp.Difference) {
            drawRect(scrim.value)
        }
        drawOval(
            color = color.value,
            topLeft = ovalRect.topLeft,
            size = ovalRect.size,
            style = Stroke(width = 3.dp.toPx()),
        )

        val face = frame()?.face ?: return@Canvas
        drawContours(face, color.value, contourPath)
    }
}

private fun DrawScope.drawContours(face: FaceSample, color: Color, path: Path) {
    val contourColor = color.copy(alpha = 0.6f)
    val contourStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (points in face.contours) {
        when {
            // Single-point contours (the cheeks) have no line to draw; show them as dots.
            points.size == 1 -> {
                drawCircle(contourColor, radius = 2.dp.toPx(), center = Offset(points[0].x, points[0].y))
            }
            points.size > 1 -> {
                path.rewind()
                path.moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                if (points.isClosedLoop()) path.close()
                drawPath(path, contourColor, style = contourStroke)
            }
        }
    }
}

/**
 * ML Kit reports closed contours (face oval, eyes) as plain point lists whose ends nearly meet, and open ones (brows,
 * lips, nose) as lists whose ends are far apart. Treat a long list whose end-to-end gap is at most two average
 * segments as a loop, so that the oval and the eyes are drawn without a gap.
 */
private fun List<Point>.isClosedLoop(): Boolean {
    if (size < MIN_POINTS_FOR_LOOP) return false
    var length = 0f
    for (i in 1 until size) length += hypot(this[i].x - this[i - 1].x, this[i].y - this[i - 1].y)
    val averageSegment = length / (size - 1)
    val gap = hypot(first().x - last().x, first().y - last().y)
    return gap <= 2f * averageSegment
}

// The shortest loops ML Kit produces (the eyes) have 16 points; the longest open contour (a lip) has 11.
private const val MIN_POINTS_FOR_LOOP = 12
