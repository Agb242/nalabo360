# Photo Sphere

Android app scaffold for capturing and stitching 360° photo spheres.

The app covers **guided capture** — a CameraX viewfinder with a target alignment
overlay that walks the user around the sphere and fires the shutter by itself
whenever the camera settles on the next frame — **stitching**: OpenCV joins the
captured frames into a 2:1 equirectangular image — and **publishing**: GPano XMP
metadata is injected so viewers open the result as a pannable 360 photo, and a
result screen offers it to the gallery and the share sheet.

## Requirements

| Tool | Version |
| --- | --- |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.14.3 (via wrapper) |
| Kotlin | 2.0.21 |
| JDK | 17 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

`minSdk` is 26 so the project can rely on adaptive launcher icons and modern
camera2 behaviour without legacy fallbacks. CameraX itself supports API 21+, so
lowering it is possible — you would need to add pre-API-26 launcher icon PNGs.

## Build

```bash
# Point the build at your SDK (or let Android Studio create this file).
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug
```

The ABI split in `app/build.gradle.kts` produces one APK per ABI
(`arm64-v8a`, `armeabi-v7a`, `x86_64`) instead of a single universal one,
because the OpenCV AAR carries native libraries for every ABI. Install with:

```bash
./gradlew :app:installArm64-v8aDebug
```

## Dependencies

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

- **Jetpack Compose + Material 3** — `androidx.compose:compose-bom`,
  `material3`, `activity-compose`, `lifecycle-runtime-compose`
- **CameraX** — `camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view` (all pinned to one version; mixing versions breaks binding)
- **ExifInterface** — `androidx.exifinterface:exifinterface`
- **OpenCV** — `org.opencv:opencv` (see below)

### OpenCV

**Option A — Maven artifact (what this project uses).**

OpenCV has published its official Android SDK to Maven Central since 4.9.0, so
nothing needs to be downloaded by hand:

```kotlin
implementation(libs.opencv)   // org.opencv:opencv:4.12.0
```

Note the coordinates: the group is `org.opencv` and the artifact is `opencv`
(not `opencv-android`, which is an unofficial mirror). The AAR is ~120 MB
because it bundles native libraries for all four ABIs — the ABI split above
keeps the installed APK closer to ~30 MB.

**Option B — local SDK module.**

Use this if you need a custom OpenCV build (extra contrib modules, a smaller
build with only `stitching` and `features2d`, or a version not on Maven).

1. Download the Android SDK from <https://opencv.org/releases/> and unpack it to
   `third_party/opencv-android-sdk/`.
2. Uncomment the two `include`/`projectDir` lines at the bottom of
   [`settings.gradle.kts`](settings.gradle.kts).
3. In `app/build.gradle.kts`, replace `implementation(libs.opencv)` with
   `implementation(project(":opencv"))`.
4. The bundled SDK module often pins an old `compileSdk`/AGP combination. If the
   sync fails, edit `third_party/opencv-android-sdk/sdk/build.gradle` to match
   the values in `app/build.gradle.kts`.

Either way, the native library is loaded once in
[`PhotoSphereApplication`](app/src/main/java/com/n30dyn4m1c/photosphere/PhotoSphereApplication.kt)
via `OpenCVLoader.initLocal()`. The old "OpenCV Manager" APK is not involved.
`proguard-rules.pro` keeps `org.opencv.**` so release builds survive the JNI
lookups.

## Permissions

Declared in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

| Permission | Type | Why |
| --- | --- | --- |
| `CAMERA` | runtime | Frame capture |
| `HIGH_SAMPLING_RATE_SENSORS` | **normal** (install-time, API 31+) | >200 Hz gyro/accel sampling to track device attitude between frames |
| `VIBRATE` | **normal** (install-time) | Haptic tick confirming an automatic capture |
| `WRITE_EXTERNAL_STORAGE` | runtime, `maxSdkVersion="28"` | Saving on pre-scoped-storage devices |
| `READ_EXTERNAL_STORAGE` | runtime, `maxSdkVersion="32"` | Reading spheres this app did not create |
| `READ_MEDIA_IMAGES` | runtime (API 33+) | Same, on Android 13+ |

`HIGH_SAMPLING_RATE_SENSORS` is a normal permission — it is granted at install
time, and requesting it at runtime always returns "denied". Declaring it is
sufficient.

Frames captured during a run are written to the app's own cache
(`cacheDir/sphere_sessions/<session>/`), which needs no permission at all —
they are stitcher input, not photos the user asked to keep. The finished sphere
lands beside them in `cacheDir/spheres/` and stays private until the user asks
for it. MediaStore (`Pictures/360Panoramas/`) is reserved for that moment; it
works on every API level, so from API 29 up no storage permission is requested
either.

### Runtime handling

`RequirePermissions` in
[`MainActivity.kt`](app/src/main/java/com/n30dyn4m1c/photosphere/MainActivity.kt)
gates the capture UI. It:

- requests the set once on first composition,
- distinguishes a plain denial (shows a rationale + retry) from
  "don't ask again" (deep-links to the app's settings page) using
  `shouldShowRequestPermissionRationale`,
- re-checks the grant on resume, so returning from Settings recovers without a
  restart.

## Project layout

```
app/src/main/java/com/n30dyn4m1c/photosphere/
├── MainActivity.kt              # entry point + runtime permission gate
├── PhotoSphereApplication.kt    # OpenCV native init
├── camera/
│   ├── PhotoSphereCameraScreen.kt # CameraX preview + the capture loop
│   ├── TargetOverlay.kt         # reticle, target markers, dwell arc
│   ├── SphereTarget.kt          # the sphere's target list
│   ├── SphereProjection.kt      # attitude + target -> screen position
│   ├── AlignmentGate.kt         # 2° / 300 ms shutter rule
│   ├── CameraOptics.kt          # field of view from the camera's optics
│   └── CaptureFeedback.kt       # shutter sound + haptic tick
├── sensor/
│   ├── OrientationTracker.kt    # rotation vector -> yaw/pitch/roll StateFlow
│   ├── OrientationState.kt      # lifecycle-aware Compose bindings
│   └── OrientationDebugScreen.kt# live readout for on-device verification
├── stitching/
│   ├── PhotoSphereStitcher.kt   # OpenCV Stitcher pass, statuses, progress
│   └── EquirectangularFit.kt    # placing a panorama in a 2:1 canvas
├── metadata/
│   └── GPanoXmpInjector.kt      # GPano XMP into the JPEG header, no re-encode
├── result/
│   └── PanoramaResultScreen.kt  # preview, export to gallery, share, start over
├── storage/
│   ├── SphereImageStore.kt      # cache sessions, the finished sphere, EXIF
│   ├── MediaExporter.kt         # MediaStore write into Pictures/360Panoramas
│   └── ImageBufferManager.kt    # this run's frames + their capture attitude
└── ui/theme/                    # Material 3 theme
```

## Device orientation

[`OrientationTracker`](app/src/main/java/com/n30dyn4m1c/photosphere/sensor/OrientationTracker.kt)
listens to `TYPE_ROTATION_VECTOR` — the *fused* sensor, so the attitude is
absolute, north-referenced and drift-free — and publishes it as a
`StateFlow<OrientationData>` of yaw, pitch and roll in degrees.

Each event is converted with `getRotationMatrixFromVector`, then run through
`remapCoordinateSystem` before the angles are read out:

1. **Display rotation.** The sensor frame is bolted to the chassis, so the matrix
   is rotated into the frame the user is actually looking at. Without it, tilting
   a landscape phone upwards would register as roll.
2. **Reference frame.** `OrientationReference.Screen` gives Android's own
   convention, where "level" means the screen lying flat and face up. That is
   the wrong pose for this app: holding the phone upright to shoot the horizon
   parks it at pitch ≈ -90°, which is exactly the gimbal-lock singularity where
   yaw and roll collapse into each other. The default
   `OrientationReference.Camera` adds an `AXIS_X`/`AXIS_Z` remap so the angles
   describe **where the rear camera points** — yaw is the bearing of the frame
   being captured, pitch is 0° at the horizon — and the singularity moves to
   straight up/down. The two remaps compose safely: every display remap is a
   rotation about the device Z axis, which leaves the camera axis (-Z) alone.

Angle ranges follow `SensorManager.getOrientation`: yaw and roll are
`atan2`-derived and span -180°..180°, pitch is `asin`-derived and spans
-90°..90°. Pitch is **negative when the camera is aimed above the horizon**.

Events are delivered on a private `HandlerThread`, so a high sampling rate never
competes with the UI. The tracker holds an OS listener registration and must be
lifecycle-driven — `startListening()` when the screen is visible,
`stopListening()` when it is not, or the gyro stays powered in the background.
From Compose that is already wired up:

```kotlin
val orientation by rememberOrientationData()   // starts on STARTED, stops on STOP
Text("yaw ${orientation.yawDegrees}")
```

Debug builds carry an **Orientation debug** button in the top-right corner of
`MainActivity`, which swaps the capture UI for a live readout (artificial
horizon, the three angles, sensor accuracy, and a sample counter that freezes if
the listener is ever leaked or stopped early). The same screen has
`@Preview`s for Android Studio. `BuildConfig.DEBUG` gates the button, so R8
strips it from release builds.

## Guided capture

[`PhotoSphereCameraScreen`](app/src/main/java/com/n30dyn4m1c/photosphere/camera/PhotoSphereCameraScreen.kt)
binds a CameraX `Preview` and `ImageCapture` to the activity lifecycle and runs
the loop that turns device attitude into frames. The user never presses a
shutter: they move the reticle onto the next marker and hold.

**The target list.** [`SphereTargetPlan`](app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
lays out 44 targets on rings at 0°, ±30° and ±60° of elevation. Yaw spacing is
30° at the equator and widens by `1 / cos(elevation)` above and below it, so
frames stay a constant *angular* distance apart instead of bunching up as the
rings shrink. Rings are swept in alternating directions, and the whole plan is
rotated to start at whatever bearing the user is already facing — capture opens
with the reticle on the first marker rather than asking for magnetic north.

**Where a marker goes on screen.** [`SphereProjection`](app/src/main/java/com/n30dyn4m1c/photosphere/camera/SphereProjection.kt)
rotates a target's direction out of the world frame into the camera's own frame
and divides through by depth — the same rectilinear projection the lens
performs, so a marker lands on the pixel its target will actually occupy. The
focal length comes from the camera's reported optics
([`CameraOptics`](app/src/main/java/com/n30dyn4m1c/photosphere/camera/CameraOptics.kt)),
falling back to typical phone values if the camera will not describe itself.
Roll is included, so tilting the phone turns the markers with the scene; it is
deliberately excluded from the *distance* measure, since turning the phone in
its own plane does not change where it is aimed.

**The trigger.** [`AlignmentGate`](app/src/main/java/com/n30dyn4m1c/photosphere/camera/AlignmentGate.kt)
fires once the aim has been within **2°** of the active target continuously for
**300 ms**. The dwell is what keeps a frame from being taken mid-swing: at a
normal pan rate the reticle crosses a 2° window in far less than 300 ms, so only
a deliberate stop trips it. Any sample outside the window clears the timer
outright. On success the shutter sound and a haptic tick fire together, the
marker turns green, and focus animates onto the next target.

**Frames** are written full-resolution to the session's cache directory with the
capture attitude stamped into EXIF `UserComment`, giving the stitcher a starting
guess at where each frame belongs. Stale sessions are cleared when a new run
starts.

The geometry, the target plan and the trigger rule are pure Kotlin, and are
covered by local unit tests in
[`app/src/test/java/com/n30dyn4m1c/photosphere/camera/`](app/src/test/java/com/n30dyn4m1c/photosphere/camera).

## Stitching

**The buffer.** [`ImageBufferManager`](app/src/main/java/com/n30dyn4m1c/photosphere/storage/ImageBufferManager.kt)
is what capture and stitching pass frames through. Each entry is a file in the
session's cache directory plus the yaw/pitch/roll the device held when it was
shot; the pixels stay on disk, because a sphere held as decoded bitmaps is
several hundred megabytes of heap. It owns the session id, so `clear()` (frames
consumed by a successful stitch) and `cancelSession()` (start over — the whole
session directory goes) are the only two ways frames leave.

**The stitch.** [`PhotoSphereStitcher`](app/src/main/java/com/n30dyn4m1c/photosphere/stitching/PhotoSphereStitcher.kt)
runs OpenCV's `Stitcher` in `PANORAMA` mode on `Dispatchers.Default`. That mode
warps onto a sphere rather than a plane, which is what makes the result
equirectangular geometry in the first place. Frames are decoded subsampled to
1024 px on the long edge and rotated upright from their EXIF before they become
`Mat`s — OpenCV holds every frame plus its warped copy at once, so full
resolution is a reliable way to run a phone out of memory.

Failures come back as a failed `Result` carrying a `StitchException` with a
`StitchStatus`. The first four statuses are OpenCV's own return codes, so a
`ERR_NEED_MORE_IMAGES` ("not enough overlap") and a `ERR_HOMOGRAPHY_EST_FAIL`
("the camera moved between frames instead of turning on the spot") reach the
user as different sentences; the rest cover this pipeline's own failures —
native library missing, unreadable frame, out of memory.

**The 2:1 canvas.** OpenCV returns the bounding box of whatever was warped onto
the sphere, black where the capture did not reach.
[`EquirectangularFit`](app/src/main/java/com/n30dyn4m1c/photosphere/stitching/EquirectangularFit.kt)
crops that to its content and centres it in the smallest 2:1 canvas that holds
it. It deliberately does not stretch: the ±60° capture plan covers 360°×120°, and
scaling that to fill a 2:1 frame would put every pixel at the wrong latitude.
Uncovered poles stay black. The geometry is pure integer arithmetic and is unit
tested.

**In the UI.** A **Finish & stitch** button appears in
`PhotoSphereCameraScreen` once six frames are buffered — enough for a stitch to
have something to register — and a modal with a progress indicator covers the
screen while the work runs. Frame decoding reports real progress; everything
inside OpenCV's `stitch` call is one opaque block, so that stage shows an
indeterminate spinner rather than a bar that lies. Cancelling takes effect at the
next stage boundary, since the native call cannot be interrupted partway. On
success the sphere is written to the cache as a GPano-tagged JPEG, the buffered
frames are deleted, and the result screen takes over; on failure the frames are
kept, because the usual fix is to capture a few more and try again.

## The finished sphere

**GPano metadata.** [`GPanoXmpInjector`](app/src/main/java/com/n30dyn4m1c/photosphere/metadata/GPanoXmpInjector.kt)
is what makes the output a *360 photo* rather than a wide picture: Google
Photos, Facebook and friends switch to a sphere viewer on an XMP packet in the
`GPano` namespace, and `ExifInterface` writes EXIF only. It walks the JPEG's
marker segments, drops any XMP already present, splices an `APP1` segment in
after JFIF/Exif, and copies every remaining byte — tables, frame header and the
entropy-coded scan — through verbatim. **Nothing is decoded or re-encoded**, so
tagging costs no image quality on a file that has already been through one
generation of JPEG on the way in. Eight properties are written:
`UsePanoramaViewer`, `ProjectionType`, `FullPano{Width,Height}Pixels` and the
four `CroppedArea*` values, which cover the whole image because
`EquirectangularFit` has already letterboxed the covered band into a full 2:1
canvas. It is plain `java.io`, so it is covered by local unit tests that assert
the image data comes out byte-identical.

**The result screen.** [`PanoramaResultScreen`](app/src/main/java/com/n30dyn4m1c/photosphere/result/PanoramaResultScreen.kt)
shows the sphere flat — the black wedges at the poles are the parts the run
never reached, and they are worth seeing before deciding to keep it — over three
actions: **Export to gallery**, **Share**, and **New photo**. Nothing has been
published at this point, which is deliberate: a run that came out badly should
not have to be deleted out of the camera roll afterwards. "New photo" discards
the cached JPEG and returns to capture with an empty buffer; the system back
gesture does the same thing.

**Export.** [`MediaExporter`](app/src/main/java/com/n30dyn4m1c/photosphere/storage/MediaExporter.kt)
copies the file into `Pictures/360Panoramas` through MediaStore. From API 29 the
row is inserted with `IS_PENDING = 1`, the bytes are streamed into the URI
MediaStore hands back, and `IS_PENDING` is cleared only once the copy finishes,
so a half-written JPEG is never visible in the gallery. On API 26–28 there is no
`RELATIVE_PATH` and no pending flag, so the file is written into the public
Pictures directory and the row points at it with `DATA` — that path is why
`WRITE_EXTERNAL_STORAGE` is requested on exactly those versions. Either way it
is a byte copy, not a re-encode, which is what keeps the GPano packet intact.

**Share** sends the same JPEG through `Intent.ACTION_SEND`. The cache is
private, and a `file://` URI would throw `FileUriExposedException` on anything
since API 24, so it travels as a `content://` URI from the app's `FileProvider`
(`res/xml/file_paths.xml` exposes `cacheDir/spheres/` and nothing else) with a
read grant attached.

## Not implemented yet

- Using the captured yaw/pitch/roll to seed the stitcher's camera estimates.
  `ImageBufferManager` records it and EXIF carries it, but OpenCV's Java
  bindings do not expose the camera parameters that would let it be fed in.
