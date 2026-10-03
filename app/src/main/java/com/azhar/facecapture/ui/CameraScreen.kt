package com.azhar.facecapture.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azhar.facecapture.face.CaptureEngine
import com.azhar.facecapture.face.CaptureSettings
import com.azhar.facecapture.face.CaptureTrigger
import com.azhar.facecapture.face.Direction
import com.azhar.facecapture.face.FacePart
import com.azhar.facecapture.face.FaceSample
import com.azhar.facecapture.face.FrameResult
import com.azhar.facecapture.face.Guidance
import com.azhar.facecapture.face.createFaceDetector
import com.azhar.facecapture.face.toFaceSample
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraScreen"

/** A new prompt has to stay the same this long before the banner switches to it, so it never flickers. */
private const val GUIDANCE_DEBOUNCE_MS = 300L

/** Darkness (or light) has to last this long before the fill light switches, so a passing hand does not flash it. */
private const val FILL_LIGHT_DEBOUNCE_MS = 500L

private const val FLASH_PEAK_ALPHA = 0.85f
private const val FLASH_DURATION_MS = 280

private const val PHOTO_DIRECTORY = "Pictures/FaceCapture"
private const val THUMBNAIL_PX = 256
private const val CAMERA_FAILED_MESSAGE = "Couldn't start the front camera"

private val ShutterSize = 76.dp
private val ThumbnailSize = 56.dp

private val FileTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US)

/** Persists [CaptureSettings] (two booleans) across activity recreation. */
private val CaptureSettingsSaver = listSaver<CaptureSettings, Boolean>(
    save = { listOf(it.blinkEnabled, it.smileEnabled) },
    restore = { CaptureSettings(blinkEnabled = it[0], smileEnabled = it[1]) },
)

/**
 * Front-camera screen: live preview, face contour overlay, prompts for a hidden or out-of-frame face, and
 * auto-capture when the user blinks or smiles (plus a manual shutter).
 *
 * Data flow, all wired up here:
 * `LifecycleCameraController` -> `MlKitAnalyzer` (coordinates already in PreviewView space) -> [CaptureEngine]
 * -> [FrameResult] -> Compose state, and [CaptureTrigger]s -> CameraX `takePicture` into the MediaStore.
 *
 * The analyzer runs on one dedicated thread, which is also the only thread that touches [CaptureEngine].
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // ---- UI state -------------------------------------------------------------------------------------------
    var settings by rememberSaveable(stateSaver = CaptureSettingsSaver) { mutableStateOf(CaptureSettings()) }
    var frame by remember { mutableStateOf<FrameResult?>(null) }
    var lastPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val flash = remember { Animatable(0f) }

    // ---- Camera pipeline (created once, released by the DisposableEffect below) -----------------------------
    val analysisExecutor = remember { newAnalysisExecutor() }
    val detector = remember { createFaceDetector() }
    val engine = remember { CaptureEngine() }
    val analyzerConfig = remember { AnalyzerConfig() }
    val triggers = remember { Channel<CaptureTrigger>(Channel.CONFLATED) }
    val controller = remember { createCameraController(context) }

    // The analyzer thread reads the settings; publish every change to it.
    SideEffect { analyzerConfig.settings = settings }

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    /** Takes a photo for [trigger] (null = manual shutter). Main thread only; ignored while one is in flight. */
    fun takePhoto(trigger: CaptureTrigger?) {
        if (capturing) return
        capturing = true
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                capturing = false
                outputFileResults.savedUri?.let { lastPhoto = it }
                showMessage(if (trigger == null) "Photo saved" else "Captured on ${trigger.name.lowercase()}")
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                Log.e(TAG, "Photo capture failed (error ${exception.imageCaptureError})", exception)
                showMessage(exception.userMessage())
            }
        }
        try {
            controller.takePicture(newPhotoOptions(context, trigger), mainExecutor, callback)
            scope.launch {
                flash.snapTo(FLASH_PEAK_ALPHA)
                flash.animateTo(0f, tween(FLASH_DURATION_MS))
            }
        } catch (e: IllegalStateException) {
            // CameraX refuses to capture before the camera has finished initialising.
            Log.w(TAG, "takePicture called before the camera was ready", e)
            capturing = false
            showMessage("The camera isn't ready yet")
        }
    }

    fun openPhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app can open $uri", e)
            showMessage("No app found to open the photo")
        }
    }

    // ---- Effects --------------------------------------------------------------------------------------------
    DisposableEffect(controller) {
        // The analyzer callback runs on analysisExecutor. ML Kit returns coordinates in PreviewView pixels:
        // COORDINATE_SYSTEM_VIEW_REFERENCED makes CameraX handle the scaling and the front-camera mirroring.
        val analyzer = MlKitAnalyzer(
            listOf(detector),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            analysisExecutor,
        ) { result ->
            val faces = result.getValue(detector)
            val viewSize = analyzerConfig.viewSize
            if (faces == null || viewSize.width == 0 || viewSize.height == 0) {
                result.getThrowable(detector)?.let { Log.w(TAG, "Face detection failed", it) }
                return@MlKitAnalyzer
            }
            // The list is unordered and may hold several faces, of which only the biggest carries contours.
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?.toFaceSample(viewSize.width.toFloat(), viewSize.height.toFloat())
            val outcome = engine.onFrame(face, analyzerConfig.settings, SystemClock.elapsedRealtime())
            frame = outcome // Compose snapshot state may be written from any thread
            outcome.trigger?.let { triggers.trySend(it) } // events must not be lost, so they do not go through `frame`
        }
        // Set the analyzer before binding: binding first would make CameraX reconfigure the camera.
        controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)

        val initialization = controller.initializationFuture
        initialization.addListener(
            {
                runCatching { initialization.get() }
                    .onSuccess {
                        // Binding to a missing camera only logs inside CameraX, so check for it explicitly.
                        if (!controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) cameraFailed = true
                    }
                    .onFailure {
                        Log.e(TAG, "Camera failed to initialise", it)
                        cameraFailed = true
                    }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            // Order matters: stop the frames, then release what the analyzer was using, then its thread.
            controller.unbind()
            detector.close()
            analysisExecutor.shutdown()
        }
    }

    // Auto-capture triggers come from the analyzer thread through the channel and are handled on the main thread.
    LaunchedEffect(triggers) {
        for (trigger in triggers) takePhoto(trigger)
    }

    LaunchedEffect(lastPhoto) {
        thumbnail = lastPhoto?.let { loadThumbnail(context, it) }
    }

    // Camera is paused while stopped: forget stale gestures and the last face so nothing fires on return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        analysisExecutor.execute { engine.reset() }
        frame = null
    }

    // ---- Derived UI state -----------------------------------------------------------------------------------
    val rawGuidance by remember { derivedStateOf { frame?.guidance ?: Guidance.NoFace } }
    val guidance = rememberDebounced(rawGuidance, GUIDANCE_DEBOUNCE_MS)
    val ready = guidance == Guidance.Ready && !cameraFailed
    val banner = if (cameraFailed) {
        BannerState(CAMERA_FAILED_MESSAGE, ready = false)
    } else {
        BannerState(guidanceMessage(guidance, settings), ready = ready)
    }

    // In the dark the screen itself lights the face: white around the oval, at full brightness.
    val fillLight = rememberDebounced(rememberIsDark(), FILL_LIGHT_DEBOUNCE_MS) && !cameraFailed
    FillLightWindowEffect(enabled = fillLight)
    val controlColors = if (fillLight) ControlColors.OnFillLight else ControlColors.OnScrim

    // ---- Layout: preview and overlay fill the screen and share one size; controls respect the system bars ----
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            controller = controller,
            onSizeChanged = { analyzerConfig.viewSize = it },
            modifier = Modifier.fillMaxSize(),
        )
        FaceOverlay(frame = { frame }, ready = ready, fillLight = fillLight, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(topInsets)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GuidanceBanner(banner, Modifier.widthIn(max = 480.dp))
            FaceReadout(frame = { frame }, colors = controlColors)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(bottomInsets)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbarHostState) { data -> Snackbar(data, shape = RoundedCornerShape(24.dp)) }
            Spacer(Modifier.height(8.dp))
            CaptureToggles(settings = settings, onSettingsChange = { settings = it }, colors = controlColors)
            Spacer(Modifier.height(20.dp))
            ShutterRow(
                thumbnail = thumbnail,
                capturing = capturing,
                colors = controlColors,
                onShutter = { takePhoto(null) },
                onOpenPhoto = { lastPhoto?.let(::openPhoto) },
            )
        }

        // White screen flash on capture, above everything else. Read in the draw block, so it only redraws.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { if (flash.value > 0f) drawRect(Color.White, alpha = flash.value) },
        )
    }
}

// ============================================================================================================
// Camera plumbing
// ============================================================================================================

/** Values written on the main thread and read on the analyzer thread, hence volatile. */
private class AnalyzerConfig {
    @Volatile
    var settings: CaptureSettings = CaptureSettings()

    /** Size of the PreviewView, i.e. the coordinate space of the face points. */
    @Volatile
    var viewSize: IntSize = IntSize.Zero
}

private fun createCameraController(context: Context) = LifecycleCameraController(context).apply {
    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
    // ML Kit wants a face of at least ~200 px for contours; MlKitAnalyzer's own 480x360 default is marginal for a
    // selfie, so ask for CameraX's usual analysis size instead.
    imageAnalysisResolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                android.util.Size(640, 480),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
        .build()
}

/**
 * One thread for analysis. Frames that arrive while the executor is being torn down are dropped silently rather
 * than throwing RejectedExecutionException on ML Kit's callback thread.
 */
private fun newAnalysisExecutor(): ExecutorService = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue<Runnable>(),
    { runnable -> Thread(runnable, "face-analysis") },
    ThreadPoolExecutor.DiscardPolicy(),
)

@Composable
private fun CameraPreview(
    controller: LifecycleCameraController,
    onSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                keepScreenOn = true // the user watches the screen without touching it
                this.controller = controller
            }
        },
        modifier = modifier.onSizeChanged(onSizeChanged),
    )
}

// ============================================================================================================
// Saving and showing photos
// ============================================================================================================

private fun newPhotoOptions(context: Context, trigger: CaptureTrigger?): ImageCapture.OutputFileOptions {
    val source = trigger?.name?.lowercase() ?: "manual"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "face_${source}_${LocalDateTime.now().format(FileTimestamp)}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIRECTORY)
    }
    // No metadata: for the front camera the controller mirrors the saved photo so that it matches the preview.
    return ImageCapture.OutputFileOptions
        .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        .build()
}

private fun ImageCaptureException.userMessage(): String = when (imageCaptureError) {
    ImageCapture.ERROR_FILE_IO -> "Couldn't save the photo. Is there enough storage?"
    ImageCapture.ERROR_CAMERA_CLOSED -> "The camera closed before the photo was taken"
    else -> "Couldn't take the photo. Please try again"
}

private suspend fun loadThumbnail(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver
            .loadThumbnail(uri, android.util.Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
            .asImageBitmap()
    }.onFailure { Log.w(TAG, "Couldn't load a thumbnail for $uri", it) }.getOrNull()
}

// ============================================================================================================
// Prompt text
// ============================================================================================================

/** Returns [value], but only after it has stopped changing for [delayMillis]. The first value is immediate. */
@Composable
private fun <T> rememberDebounced(value: T, delayMillis: Long): T {
    var settled by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != settled) {
            delay(delayMillis)
            settled = value
        }
    }
    return settled
}

private fun guidanceMessage(guidance: Guidance, settings: CaptureSettings): String = when (guidance) {
    Guidance.NoFace -> "Fit your face inside the oval"
    is Guidance.Move -> when (guidance.direction) {
        Direction.LEFT -> "Move left"
        Direction.RIGHT -> "Move right"
        Direction.UP -> "Move up"
        Direction.DOWN -> "Move down"
    }
    Guidance.MoveCloser -> "Move closer"
    Guidance.MoveBack -> "Move back a little"
    Guidance.LookAtCamera -> "Look straight at the camera"
    is Guidance.PartsHidden -> hiddenPartsMessage(guidance.parts)
    Guidance.Ready -> when {
        settings.blinkEnabled && settings.smileEnabled -> "Blink or smile to capture"
        settings.blinkEnabled -> "Blink to capture"
        settings.smileEnabled -> "Smile to capture"
        else -> "Auto-capture is off. Tap the shutter"
    }
}

/** "Your mouth is hidden — please uncover it" / "Your left eye and mouth are hidden — please uncover them". */
private fun hiddenPartsMessage(parts: Set<FacePart>): String {
    val labels = FacePart.entries.filter { it in parts }.map { it.label }
    val list = when (labels.size) {
        0 -> return "Part of your face is hidden — please uncover it"
        1 -> labels[0]
        2 -> "${labels[0]} and ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + ", and " + labels.last()
    }
    return if (labels.size == 1) {
        "Your $list is hidden — please uncover it"
    } else {
        "Your $list are hidden — please uncover them"
    }
}

private fun FaceSample.readoutText(): String {
    val eyesOpen = listOfNotNull(leftEyeOpenProbability, rightEyeOpenProbability)
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toFloat()
    return "Smile ${smilingProbability.asPercent()} · Eyes open ${eyesOpen.asPercent()}"
}

private fun Float?.asPercent(): String = if (this == null) "–" else "${(this * 100).roundToInt()}%"

// ============================================================================================================
// UI pieces
// ============================================================================================================

private data class BannerState(val message: String, val ready: Boolean)

/**
 * The prompt at the top of the screen: amber for problems, green when ready. Each state is its own pill so the
 * colours crossfade with the text. TalkBack is given the plain message as a polite live region, which announces
 * every change once instead of reading the two overlapping pills of a crossfade.
 */
@Composable
private fun GuidanceBanner(state: BannerState, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = state,
        modifier = modifier.clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = state.message
        },
        transitionSpec = {
            fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.Center,
        label = "guidanceBanner",
    ) { target ->
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (target.ready) ReadyGreen else WarningAmber,
            contentColor = Color.Black,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (target.ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(text = target.message, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Small live numbers (smile and eyes-open probability) under the banner, shown while a face is tracked.
 * It reads [frame] itself, so the ~30 updates per second recompose only this pill and not the whole screen.
 */
@Composable
private fun FaceReadout(frame: () -> FrameResult?, colors: ControlColors, modifier: Modifier = Modifier) {
    val text by remember(frame) { derivedStateOf { frame()?.face?.readoutText() } }
    text?.let { readout ->
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = colors.container.copy(alpha = 0.45f),
            contentColor = colors.content,
        ) {
            Text(
                text = readout,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Colours of the controls that sit on the area around the oval. That area is a dark scrim over the preview, or solid
 * white while the fill light is on, and the controls have to stay readable on both.
 */
private enum class ControlColors(val content: Color, val container: Color) {
    OnScrim(content = Color.White, container = Color.Black),
    OnFillLight(content = Color.Black, container = Color.White),
}

/**
 * While [enabled], drives the screen to full brightness and gives the system bars dark icons to suit the white fill
 * light. The brightness is overridden for this window only (the system setting is untouched), and both are put back
 * when the fill light goes off or the screen leaves.
 */
@Composable
private fun FillLightWindowEffect(enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)
        val previousBrightness = window.attributes.screenBrightness
        val previousLightStatusBars = insetsController.isAppearanceLightStatusBars
        val previousLightNavigationBars = insetsController.isAppearanceLightNavigationBars
        window.setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true
        onDispose {
            window.setScreenBrightness(previousBrightness)
            insetsController.isAppearanceLightStatusBars = previousLightStatusBars
            insetsController.isAppearanceLightNavigationBars = previousLightNavigationBars
        }
    }
}

private fun Window.setScreenBrightness(brightness: Float) {
    attributes = attributes.apply { screenBrightness = brightness }
}

@Composable
private fun CaptureToggles(
    settings: CaptureSettings,
    onSettingsChange: (CaptureSettings) -> Unit,
    colors: ControlColors,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleChip("Blink", settings.blinkEnabled, colors) { onSettingsChange(settings.copy(blinkEnabled = it)) }
        ToggleChip("Smile", settings.smileEnabled, colors) { onSettingsChange(settings.copy(smileEnabled = it)) }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    controlColors: ControlColors,
    onSelectedChange: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onSelectedChange(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = controlColors.container.copy(alpha = 0.45f),
            labelColor = controlColors.content,
            selectedContainerColor = controlColors.content,
            selectedLabelColor = controlColors.container,
            selectedLeadingIconColor = controlColors.container,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = controlColors.content.copy(alpha = 0.7f),
        ),
    )
}

/** Thumbnail of the last photo (left), shutter (centre), and a spacer that keeps the shutter centred. */
@Composable
private fun ShutterRow(
    thumbnail: ImageBitmap?,
    capturing: Boolean,
    colors: ControlColors,
    onShutter: () -> Unit,
    onOpenPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ThumbnailSize), contentAlignment = Alignment.Center) {
            Crossfade(targetState = thumbnail, label = "lastPhotoThumbnail") { bitmap ->
                if (bitmap != null) PhotoThumbnail(bitmap, borderColor = colors.content, onClick = onOpenPhoto)
            }
        }
        ShutterButton(enabled = !capturing, color = colors.content, onClick = onShutter)
        Spacer(Modifier.size(ThumbnailSize))
    }
}

@Composable
private fun PhotoThumbnail(
    bitmap: ImageBitmap,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = "Last photo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(ThumbnailSize)
            .clip(CircleShape)
            .border(2.dp, borderColor, CircleShape)
            .clickable(onClickLabel = "Open the photo", role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val discScale by animateFloatAsState(if (pressed) 0.85f else 1f, label = "shutterDiscScale")
    Box(
        modifier = modifier
            .size(ShutterSize)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = "Take photo" }
            .border(4.dp, color, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(9.dp)
            .graphicsLayer {
                scaleX = discScale
                scaleY = discScale
            }
            .background(color, CircleShape),
    )
}
