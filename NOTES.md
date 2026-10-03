# Notes

Findings, decisions and open items from building Face Capture. The README says what the app does; this file says
why it is built the way it is and how far it has been checked.

## ML Kit face detection (16.1.7, bundled model)

These come from reading the library's bytecode, not from its documentation, so treat them as true for this version.

- **Contours, landmarks and classification can all be enabled together.** The native contour pipeline can't
  classify, so with `CONTOUR_MODE_ALL` ML Kit runs two native detectors per frame: one for contours, one for
  landmarks and classification. It then copies the contours onto the landmark face whose box overlaps. The cost is
  two passes per frame.
- **The result list is unordered and can hold more than one face**, but only one of them carries contours. The app
  picks the face with the largest bounding box instead of the first one.
- **A face can arrive with contours only.** If the landmark detector misses a face that the contour detector found,
  it has no landmarks and no probabilities. The app then reports every part as hidden.
- **`getLeftEyeOpenProbability()` range-checks the smile value instead of its own**, so an unclassified left eye
  can come back as -1 instead of null. `FaceDetection.kt` discards any probability outside 0..1.
- **Classification needs a near-frontal face.** Beyond roughly 18° of yaw the smile and eye probabilities are null.
  The 18° figure is from memory and was not verified against the library. The "look straight at the camera" limit
  is set to match it, so a turned head isn't reported as "eyes hidden".
- **`minFaceSize` is 0.1** (the default). It is a fraction of the analysis image, while the oval is a fraction of
  the view; with `FILL_CENTER` a higher floor would hide small faces before the app could say "move closer".

## Permissions

The app's own manifest declares one permission, `CAMERA`. The merged manifest of the built app has two more,
`INTERNET` and `ACCESS_NETWORK_STATE`. They are added by `com.google.android.datatransport:transport-backend-cct`,
which ML Kit depends on to report usage and diagnostics to Google. You can see where each one comes from in
`app/build/outputs/logs/manifest-merger-debug-report.txt` after a build.

The app doesn't remove them. Camera frames are analysed on the device by the bundled model, and the app's code
never sends anything anywhere. What ML Kit itself reports is described in Google's ML Kit data disclosure.

Saving photos needs no permission: on Android 10+ an app can write to `Pictures/` through MediaStore, which is why
`minSdk` is 29.

## CameraX

- `MlKitAnalyzer` + `COORDINATE_SYSTEM_VIEW_REFERENCED` applies the sensor-to-view matrix to the bounding box,
  landmarks and contour points, including the front-camera mirror.
- `MlKitAnalyzer` defaults to a 480×360 analysis image. The app asks for 640×480, because ML Kit wants a face of
  about 200 px for contours.
- `LifecycleCameraController.initializationFuture` succeeds even when the selected camera doesn't exist; binding to
  a missing camera only logs. The app checks `hasCamera(DEFAULT_FRONT_CAMERA)` itself to show its error message.
- The analyzer is set before binding, so CameraX doesn't have to reconfigure the camera.
- `CameraController.takePicture` mirrors front-camera photos, so the saved photo matches the preview.

## Guidance

- **Order: position, distance, pose, hidden parts.** One instruction at a time, and the first problem wins. When
  both axes are off-centre, the one that misses by more is fixed first.
- **Directions are screen directions.** The front preview is mirrored, so a face that appears left of the oval is
  told "Move right", and that is also the user's own right.
- **The face is measured by ML Kit's bounding box**, which is roughly square and spans about cheek to cheek. A face
  that fills the oval measures around 0.8 of the oval's width, hence the 0.6 to 1.1 window.
- **The prompt is debounced by 300 ms** in the UI so that it doesn't flicker between two states. The engine itself
  has no hysteresis on the oval checks.
- **Gestures reset on any frame that isn't "ready"**, so a blink that completes while the face is half out of the
  oval can't fire.

## Fill light

The ambient light sensor is the input. It sits on the front of the phone, so it measures the light that falls on
the user's face.

Measured on a Nothing phone (model A015, Android 16, under-display `ltr569_als` sensor) in a dark room:

| State | Sensor reading |
|---|---|
| App open, dark surround | 0 to 1 lux |
| Fill light on (white surround, full brightness), phone held at arm's length | 15 lux steady, 23 lux peak |

So the fill light raises its own input. Three things stop that from turning into a flashing screen:

1. **Thresholds far apart:** dark below 10 lux, bright from 60 lux.
2. **A latch:** if darkness returns within 3 seconds of "brightness", that brightness was the fill light's own
   (the phone is very close to a face or a wall). The fill light then stays on, so the screen flashes at most once.
3. **A 500 ms debounce** in the UI, so a hand passing over the sensor doesn't flash the screen.

Each time the screen resumes, detection starts over with the fill light off, so the first reading is taken without
the app's own light.

Brightness is overridden on the app's window only (`WindowManager.LayoutParams.screenBrightness`). While the fill
light is on, the controls and the system-bar icons switch to dark so they stay visible on white.

## What has been tested

| Area | How | Status |
|---|---|---|
| Guidance, blink, smile, cooldown, settings | 46 JVM unit tests (`CaptureEngineTest`) | Pass |
| Darkness thresholds and latch | 7 JVM unit tests (`DarknessDetectorTest`) | Pass |
| Launch, permission prompt, camera start | On the A015 phone | Works |
| Oval, "fit your face" and "ready" prompts, blink and smile capture | On the A015 phone | Works |
| Fill light switching on and holding steady in a dark room | On the A015 phone, with sensor readings | Works |
| "Move left / right / up / down / closer / back" prompts on a device | | Not observed (unit tests only) |
| The fill-light latch on a real device | | Not tested (unit tests only) |
| "Part hidden" prompts with a covered eye or mouth | | Not tested on a device |
| TalkBack announcements of the prompt | | Not tested |
| Other phones, tablets, foldables, devices without a light sensor | | Not tested |
| Emulator | The only AVD available had no front camera | Not tested |

On the A015, logcat shows a `NullPointerException` from `CameraDeviceImpl.getThermalInfo` each time the camera
session is created. It is thrown and caught inside the phone's own camera framework and doesn't affect the app.

## Planned

- **In-app gallery.** A grid of the app's own photos grouped by day, multi-select share and delete, and a
  full-screen viewer with zoom. It will read `Pictures/FaceCapture` through MediaStore, which needs no storage
  permission on Android 10+ because an app can always read the media it created.
- **String resources** for the UI text, so it can be translated.
- **A direction arrow** in the prompt next to "Move left / right / up / down".
