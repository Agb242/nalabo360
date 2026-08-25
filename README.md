# Nalabo360

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![iOS CI](https://github.com/n30dyn4m1c/360-photo-app/actions/workflows/ios.yml/badge.svg)](https://github.com/n30dyn4m1c/360-photo-app/actions/workflows/ios.yml)
[![Android CI](https://github.com/n30dyn4m1c/360-photo-app/actions/workflows/android.yml/badge.svg)](https://github.com/n30dyn4m1c/360-photo-app/actions/workflows/android.yml)

**Guided capture and stitching of 360° photo spheres — one Kotlin Multiplatform codebase running on Android and iOS.**

The app covers **guided capture** — a viewfinder with a target alignment overlay
that walks the user around the sphere and fires the shutter by itself whenever
the camera settles on the next frame — **stitching**: a pure-Kotlin stitcher
joins the captured frames into a 2:1 equirectangular image — and
**publishing**: GPano XMP metadata is injected so viewers open the result as a
pannable 360 photo, and a result screen offers it to the gallery and the share
sheet.

Everything above runs from one shared source set
(`composeApp/src/commonMain`). Android ships it inside an APK with CameraX
behind the viewfinder; iOS compiles it into `Nalabo360Kit.framework`, wrapped
by a one-file Swift host that CI assembles into an unsigned `.ipa`, installable
for free via Sideloadly or AltStore — no paid Apple Developer account (see
[`docs/installation-iphone-gratuit.md`](docs/installation-iphone-gratuit.md)).

## Requirements

| Tool | Version |
| --- | --- |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 (via wrapper) |
| Kotlin | 2.2.10 |
| JDK | 17 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Xcode + macOS | iOS builds only (`iosApp/build-ipa.sh`, or the CI runner) |

`minSdk` is 26 so the project can rely on adaptive launcher icons and modern
camera2 behaviour without legacy fallbacks. CameraX itself supports API 21+, so
lowering it is possible — you would need to add pre-API-26 launcher icon PNGs.

## Build

You need the Android SDK (compileSdk 35 + build-tools 35) and a JDK 17. The
simplest route is Android Studio, which supplies both: open the project folder,
let it sync, and it writes `local.properties` for you. From the command line:

```bash
# Point the build at your SDK (or let Android Studio create this file).
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :composeApp:assembleDebug
```

`local.properties` is machine-specific and git-ignored — it is the one file you
must create yourself before a fresh clone will build.

The build produces one universal APK (~20 MB — the OpenCV AAR that used to
force per-ABI splits is gone from the dependency graph) at
`composeApp/build/outputs/apk/debug/`. Install to a connected device with

```bash
./gradlew :composeApp:installDebug    # or: adb install -r composeApp-debug.apk
```

Debug builds are signed with the local debug keystore and carry the
`.debug` application id suffix, so they sideload and can sit alongside a release
install. `assembleRelease` produces an **unsigned** APK — add a `signingConfigs`
block with your own keystore before using it for anything installable.

### Without a local toolchain

CI publishes ready-to-install artifacts on every push:

- **`.github/workflows/android.yml`** runs the shared test suite and assembles
  the debug APK, uploaded as the `nalabo360-debug-apk` artifact — download,
  unzip, `adb install`, no local SDK needed.
- **`.github/workflows/ios.yml`** builds the unsigned `.ipa` on a macOS runner
  (`nalabo360-unsigned-ipa`). It installs for free through Sideloadly or
  AltStore; the whole procedure lives in
  [`docs/installation-iphone-gratuit.md`](docs/installation-iphone-gratuit.md).

Both workflows also run on demand from the Actions tab.

## Test

```bash
./gradlew :composeApp:jvmTest      # the shared suite, on the desktop JVM — JDK only
./gradlew :composeApp:lintDebug    # Android lint
```

The unit tests cover the parts of the pipeline that are pure Kotlin: the sphere
target plan, the projection maths, the alignment gate, the equirectangular fit,
the stitch status mapping and the GPano XMP splice. Everything that needs real
hardware — the camera, the rotation vector sensor, the capture loop — is
verified on a device. The **Orientation debug** button in the top-right of debug
builds exists for exactly that: it puts the live sensor readout on screen so a
leaked or stopped listener is visible immediately.

An emulator is enough to check that the app launches, the permission gate works
and the UI renders, but not to capture a sphere: the emulated camera and
synthetic sensors will not produce frames that stitch. Guided capture needs a
physical device with a gyroscope.

## Dependencies

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

- **Compose Multiplatform + Material 3** — runtime, foundation, material3,
  animation, material-icons, ui: one shared UI for every target
- **Kotlinx coroutines / datetime / okio** — concurrency, timestamps and file
  I/O through multiplatform APIs
- **CameraX** *(Android only)* — `camera-core`, `camera-camera2`,
  `camera-lifecycle`, `camera-view` (all pinned to one version; mixing
  versions breaks binding)
- **ExifInterface** *(Android only)* — `androidx.exifinterface:exifinterface`

There is no native vision library any more: the entire stitching pipeline is
pure shared Kotlin (see [Stitching](#stitching)), which is what makes the iOS
target possible at all.

## Permissions

Declared in
[`AndroidManifest.xml`](composeApp/src/androidMain/AndroidManifest.xml):

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

`RequirePermissions` — declared once in the shared
[`ui/AppRoot.kt`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/ui/AppRoot.kt),
implemented per platform (an Android actual beside
[`MainActivity`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/MainActivity.kt),
an AVFoundation authorization check on iOS) — gates the capture UI. On Android
it:

- requests the set once on first composition,
- distinguishes a plain denial (shows a rationale + retry) from
  "don't ask again" (deep-links to the app's settings page) using
  `shouldShowRequestPermissionRationale`,
- re-checks the grant on resume, so returning from Settings recovers without a
  restart.

The grant is read back from the system rather than inferred from the launcher's
result map, and that is load-bearing. When the dialog is dismissed **without an
answer** — swiped away, interrupted by an incoming call, screen locked —
`RequestMultiplePermissions` delivers an *empty* map, and
`emptyMap().values.all { it }` is vacuously `true`: read straight from the map, a
cancellation counts as a grant and drops the user onto the capture screen with no
camera permission, where the bind fails and the viewfinder is simply black.

That same cancellation must not be routed to the "permanently denied" branch
either. `shouldShowRequestPermissionRationale` reads `false` after a cancel, so
trusting it there would send someone who never answered the dialog off to the
Settings app to fix a setting they had not touched. Only an explicit denial the
system will no longer surface a dialog for earns that trip.

## Project layout

```
composeApp/src/                                  # one module, four source sets
├── commonMain/kotlin/com/n30dyn4m1c/photosphere/  # everything shared
│   ├── AppRoot.kt, PlatformUi.kt, Strings.kt      # navigation, expect surfaces,
│   │                                              #   the one string table
│   ├── camera/        # target plan, projection, alignment gate, capture
│   │                  #   profiles, SphereCaptureScreen (expect)
│   ├── sensor/        # OrientationSensor contract + fusion maths
│   ├── result/        # PanoramaResultScreen: preview, export, share, restart
│   ├── stitching/     # the stitcher, pure Kotlin: renderer, multiband blender,
│   │                  #   seam solver, lens model, pivot, exposure compensation
│   ├── metadata/      # GPanoXmpInjector — XMP spliced into the JPEG header
│   ├── storage/       # SphereCache — session directories
│   └── ui/theme/      # Color, Theme, Type, Shape
├── androidMain/kotlin/…               # MainActivity, CameraX capture loop +
│                                      #   auto-shutter, orientation tracker,
│                                      #   MediaStore export, EXIF stamping
├── iosMain/kotlin/…                   # AVFoundation capture, CoreMotion
│                                      #   attitude, Photos export, MainViewController
├── jvmMain/kotlin/…                   # desktop stubs so the suite runs anywhere
└── commonTest/kotlin/…                # the shared test suite (:composeApp:jvmTest)

iosApp/                               # Swift host, Info.plist, build-ipa.sh
docs/installation-iphone-gratuit.md   # free iPhone installs (Sideloadly/AltStore)
.github/workflows/                    # android.yml (APK) · ios.yml (unsigned .ipa)
```

## Interface

Everything the user looks at sits on top of either a live viewfinder or a
finished photograph, and that one fact settles most of the design decisions.
The palette, type scale and shape ladder live in
[`ui/theme/`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/ui/theme/) and are
defined once each — the two signal colours in particular were previously hex
literals repeated across three files, which is how a design drifts.

### Dark always, and no dynamic colour

`PhotoSphereTheme` takes no `darkTheme` or `dynamicColor` parameter. Both were
removed deliberately:

- **Dark in every configuration.** A light chrome around a viewfinder throws
  light back at the user in exactly the situation where they are judging
  exposure, and it tints the photograph it surrounds. The capture screen is
  pinned to black regardless, so following the system into a light scheme only
  ever produced a light result screen bolted onto a black capture screen.
- **Material You off.** Dynamic colour repaints the interface from the user's
  wallpaper. Guided capture is built on exactly two signals — accent for
  "captured", active for "aim here next" — and they may not survive that
  repaint as two colours that contrast with each other, let alone against an
  arbitrary scene.

Because the app is dark in every configuration, `MainActivity` also pins both
system bars to **light icons** via `SystemBarStyle.dark(...)`. Left to the
default they follow the system's day/night setting, and a phone in light mode
paints dark status-bar icons over a black viewfinder.

`res/values/colors.xml` holds `sphere_background`, the window background the
platform paints before Compose takes over. It must stay in step with
`SphereBackground` in `Color.kt` or every cold start flashes the wrong dark.

### The two signals

| Token | Meaning |
| --- | --- |
| `SphereAccent` (green) | aligned, locked, captured, complete — the "yes" |
| `SphereActive` (amber) | the live target the reticle is being sent to |

They are warm/cool opposites so the two never trade places at a glance, and
both are bright enough to hold against a blown-out sky or a dark room. They are
shared by the HUD and by `TargetOverlayColors`, so the marker on the overlay and
the progress bar in the pill are the same green by construction.

### Chrome over a moving image

The HUD is one translucent material (`GlassSurface` / `GlassSurfaceDim`) rather
than a handful of similar blacks, and gradient scrims run along **both** the top
and the bottom of the frame — the guidance line and the finish button sit over
live scene just as the progress pill does.

Motion is used only where it carries meaning: the finish button arrives on an
animation because the moment it appears is the moment the run stops being
all-or-nothing; the progress bar slides so a landing frame registers in
peripheral vision; the guidance line and the stitch stage crossfade so a change
is noticeable without being read at that instant.

### Accessibility

The progress pill is collapsed to a single spoken sentence
(`capture_progress_description`) — left to itself it handed a screen reader
"12", "/ 48", "frames", a bare progress bar and "1 of 3 bands" as five unrelated
announcements. Controls that are conditionally inert, such as the capture-scope
selector once the first frame has locked the plan in, pass `enabled` to the
component rather than checking it inside `onClick`, so a disabled control is not
announced as actionable.

Guided capture itself — aiming a reticle at a marker — remains unusable without
sight; see the open issues.

## Device orientation

[`OrientationTracker`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/sensor/OrientationTracker.kt)
listens to `TYPE_ROTATION_VECTOR` — the *fused* sensor, so the attitude is
absolute, north-referenced and drift-free — and publishes it as a
`StateFlow<OrientationData>` of yaw, pitch and roll in degrees.

The angles are read straight off the device→world rotation matrix, so they
describe **where the rear camera points**, not the screen:

1. **The camera basis.** `getRotationMatrixFromVector` gives the matrix mapping
   device axes to the world frame (X east, Y north, Z up). The lens looks along
   the device's -Z axis, so that is the camera's forward. Yaw is its compass
   bearing, elevation its height above the horizon (reported as a pitch that is
   **negative above the horizon**, matching the capture plan and the stitcher),
   and roll is the image's tilt about the forward axis.
2. **Display rotation.** Which chassis axis counts as the image's "up" depends
   on how the display is turned, so the tracker maps the display rotation onto
   the device axis that points at the top of the screen and measures the roll
   against it. Portrait and landscape then both report the same absolute
   attitude — turning a landscape phone "up" reads as pitch, never as roll.

This extraction is the *inverse* of the axis construction
[`SphereProjection`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereProjection.kt)
and [`CameraBasis`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/SphericalGeometry.kt)
build their camera bases with, so a yaw/pitch/roll handed to the stitcher places
a frame exactly where the sensor held the camera. (It used to go through a
`remapCoordinateSystem` + `getOrientation` shortcut that reported a level pan as
*roll* and pinned yaw near zero; every frame of a ring then landed on the same
longitude, each rotated differently — the "aligned but at odd angles" failure —
so it is pinned down by unit tests against `CameraBasis` now.)

The ranges follow the camera-basis construction: yaw and roll are `atan2`-derived
and span -180°..180°, pitch is `asin`-derived and spans -90°..90°. Pitch is
**negative when the camera is aimed above the horizon**.

One orientation per session. `MainActivity` is locked to portrait in the
manifest, so the display rotation cannot change mid-run. That matters beyond
convenience: the target plan, the sensor's angles and each frame's EXIF
orientation all describe the same display rotation, and a run that crossed a
rotation would carry frames in two incompatible conventions — nothing would
stitch them. The tracker still reads the display rotation properly (the angles
are correct for any rotation), but with the screen locked it is constant for
the whole session.

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

[`PhotoSphereCameraScreen`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/camera/PhotoSphereCameraScreen.kt)
binds a CameraX `Preview` and `ImageCapture` to the activity lifecycle and runs
the loop that turns device attitude into frames. The user never presses a
shutter: they move the reticle onto the next marker and hold.

**The target list.** [`SphereTargetPlan`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
lays its rings out to match the device's lens. The yaw gap on the equator is the
horizontal field of view minus a 35% overlap target, so a wide-angle phone takes
fewer, larger frames and a narrow one takes more, smaller ones — every frame
covers roughly the same slice of the sphere. Ring elevations step up and down
from the horizon by the same fraction of the *vertical* field of view, and the
outermost ring always reaches the poles, so no lens leaves an uncovered cap at
the top or bottom. Yaw spacing widens by `1 / cos(elevation)` so neighbouring
frames stay a constant *angular* distance apart instead of bunching up as the
rings shrink. The 35% overlap is what the feature-based pose refinement needs:
it leaves every frame well inside its neighbours' view, and is the margin that
absorbs a field-of-view estimate that runs high and a degree or two of sensor
drift (see [Stitching](#stitching)). Rings are swept in alternating directions,
and the whole plan is rotated to start at whatever bearing the user is already
facing — capture opens with the reticle on the first marker rather than asking
for magnetic north.

The plan is laid out against 90% of the reported field of view, not all of it
(`FIELD_OF_VIEW_SAFETY_FACTOR`). The overlap is only ever as good as the field
of view it is measured against, and that number is read off the bound camera's
own intrinsic calibration rather than a guess; overestimating it spaces the
targets too far apart, and the overlap is the first thing to be eaten. Spending
a few extra frames is the cheaper mistake.

**One plan, inferred coverage.** A toggle chooses how much of the sphere the
plan walks: [`SphereCaptureScope.Ring`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
lays out just the horizon — a regular pano that goes all the way around, like a
ring — while [`SphereCaptureScope.Sphere`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereTarget.kt)
adds rings out to both poles. Either way coverage is inferred from the frames as
they land, and the run can be stitched at any point — three overlapping frames
are already a panorama. Stop early for a partial capture (black where it was
never shot), or walk the whole plan for a full one. The HUD counts bands so "the
horizon is closed" is a visible moment rather than a guess.

**Where a marker goes on screen.** [`SphereProjection`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereProjection.kt)
rotates a target's direction out of the world frame into the camera's own frame
and divides through by depth — the same rectilinear projection the lens
performs, so a marker lands on the pixel its target will actually occupy. The
focal length comes from the camera's reported optics
([`CameraOptics`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/camera/CameraOptics.android.kt)),
preferring the device's own intrinsic calibration and falling back to typical
phone values if the camera will not describe itself. Roll is included, so
tilting the phone turns the markers with the scene; it is deliberately excluded
from the *distance* measure, since turning the phone in its own plane does not
change where it is aimed.

**The overlay, Photo Sphere style.** [`TargetOverlay`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/camera/TargetOverlay.kt)
draws the reticle the way Google Camera's Photo Sphere and Street View do: a
large ring fixed at the centre of the viewfinder, sized to the display rather
than a fine crosshair, that warms from white toward green as the aim closes on
the active target and fills a dwell arc around its rim while the aim is held
inside it. Targets are circles rather than footprints: a hollow one is "still to
cover", a filled green one is "done", and the live target pulses with a dashed
guide line back to the reticle — collapsing to a chevron on the border when it
is off-screen, so the user always knows which way to turn.

**The trigger.** [`AlignmentGate`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/AlignmentGate.kt)
fires once the aim has been within **2°** of the active target continuously for
**300 ms**. The dwell is what keeps a frame from being taken mid-swing: at a
normal pan rate the reticle crosses a 2° window in far less than 300 ms, so only
a deliberate stop trips it. Any sample outside the window clears the timer
outright. The shutter also refuses to fire while the fused sensor reports its
readings as unreliable, and the attitude stamped onto each frame is the mean
over the dwell rather than a single sample — both take the jitter out of the
pose the stitcher starts from. On success the shutter sound and a haptic tick
fire together, the marker turns green, and focus animates onto the next target.

**Tap to focus, locked for the session.** Phone lenses have a fixed physical
aperture, so there is no f-stop to dial — focus is what changes, and it was
being parked at infinity, which reads as blur on any scene with something nearer
than the horizon. The session now runs tap-to-focus instead: tap anywhere on the
viewfinder to aim the lens at that part of the scene (a focus square appears and
turns green when it locks), and the lens holds that distance for the whole run —
exactly like a regular camera's tap-to-focus lock, and exactly what a sphere
needs, because no frame re-focuses mid-sweep. Until the first tap the lens holds
the centre of the first scene it saw. AE and AWB stay locked for consistency, so
the only thing a tap ever moves is focus.

**Frames** are written full-resolution to the session's cache directory with the
capture attitude stamped into EXIF `UserComment`, giving the stitcher a starting
guess at where each frame belongs. Stale sessions are cleared when a new run
starts. A frame that came out blurred, or one shot while something moved through
the scene, is dropped with the **undo** button in the top-left corner (the
Street View camera's control): the last frame is deleted and its target becomes
the active one again, so it can simply be re-shot.

**Device tuning.** [`SphereDeviceProfile`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/camera/SphereDeviceProfile.kt)
is where a specific phone is tuned, decided once per device. On a Samsung
Galaxy S23 it is tuned for sharpness:

- **Capture with the 50 MP main camera.** The main lens is the sharpest on the
  phone — better glass, OIS, a far larger sensor than the ultrawide — so a
  sphere shot on it is noticeably crisper. It costs more frames (~33 instead of
  the ultrawide's ~11), which is the price of sharpness.
- **Cap the stills at 12 MP.** Even though the main sensor is 50 MP, the stitch
  reads ~2000 px per frame; capturing full-size is pure waste — slower writes,
  a slower per-target burst, more heat.
- **Stitch at 2000 → 6144.** Frames are decoded at a 2000 px long edge and the
  sphere is rendered up to 6144 wide (Google's Photo Sphere standard is 5376),
  so the main camera's detail reaches the finished photo instead of being
  thrown away at 4096. Resampling uses a Lanczos kernel and a gentle masked
  unsharp mask lifts the finished canvas.
- **Burst three shots per target**, so a sharp survivor is more likely.

On a phone without a wider lens the same code path falls back to the default
back camera, so the tuning is specific without being fragile.

**Which lens the numbers describe.** The field of view is re-read from the
camera CameraX actually bound, using its camera2 id and the resolution of the
stream it settled on, rather than from a guess at what *would* bind. That
matters on a phone with three rear lenses: the plan's spacing, the overlay's
markers and the angle each stitched frame is taken to cover are all scaled by
this one number, and describing the ultrawide while streaming the main lens
spaces every target roughly twice too far apart — frames that do not overlap at
all, and a run that fails at the end with nothing to show for it. Three things
guard against it, all in
[`CameraOptics`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/camera/CameraOptics.android.kt):

- **Two independent estimates, reconciled.** One from `LENS_INTRINSIC_CALIBRATION`
  against the *pre-correction* active array, one from physical sensor size over
  focal length. The calibration is the more precise and wins when the geometry
  agrees with it; past a 25% disagreement it is discarded, because a focal
  length in millimetres over a sensor size in millimetres has no crop to be
  quoted against and therefore no way to be off by a factor.
- **The right focal length out of a logical multi-camera**, which lists one per
  physical lens. The main lens is picked by its diagonal field of view landing
  nearest ~78°; profiles that asked for the widest lens take the shortest focal
  length instead. With no sensor size to compare against, the *longest* focal
  length wins — guessing narrow costs frames, guessing wide costs the run.
- **The stream's aspect ratio, not the sensor array's.** Camera2 derives a
  differently shaped stream by cropping, so a 16:9 preview off a 4:3 sensor sees
  a quarter less across one axis. Preview and `ImageCapture` are both pinned to
  4:3 so one field of view describes the viewfinder and the stills alike —
  without that, the overlay draws the angles of a frame the viewfinder never
  shows.

The geometry, the target plan and the trigger rule are pure Kotlin, and are
covered by local unit tests in
[`composeApp/src/commonTest/kotlin/com/n30dyn4m1c/photosphere/camera/`](composeApp/src/commonTest/kotlin/com/n30dyn4m1c/photosphere/camera).

## Stitching

**The buffer.** [`ImageBufferManager`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/storage/ImageBufferManager.kt)
is what capture and stitching pass frames through. Each entry is a file in the
session's cache directory plus the yaw/pitch/roll the device held when it was
shot; the pixels stay on disk, because a sphere held as decoded bitmaps is
several hundred megabytes of heap. It owns the session id, so `clear()` (frames
consumed by a successful stitch) and `cancelSession()` (start over — the whole
session directory goes) are the only two ways frames leave.

**Why not OpenCV's `Stitcher`.** Because there isn't one. The stitching module
is absent from every prebuilt OpenCV for Android: `org.opencv:opencv` ships Java
bindings for core, imgproc, features2d, calib3d and the rest, but no
`org.opencv.stitching` package — and `libopencv_java4.so` contains none of the
pipeline's native symbols either, so a JNI shim would not link. Independently
built AARs are the same. `cv::Stitcher` is, in practice, a desktop API, and the
local OpenCV SDK does not change that.

**What this is instead.** Guided capture already knows where the camera was
pointing for every frame — the shutter only fires when the device is held on a
known target — which turns the expensive half of stitching, solving for each
camera's rotation, into something already measured. What is left is a
reprojection. (An earlier iteration also refined poses and focal lengths from
ORB feature matches on top of OpenCV; that stage did not survive the port to
pure shared Kotlin — the sensor's fused attitude feeds the renderer directly
now.) What the pipeline does carry through the model:
- Which pairs count as overlapping is a *directional* test rather than a single
  distance threshold: the second frame's aim is projected into the first's
  camera frame and must land within one field of view on both axes. A portrait
  frame is far taller than it is wide, so two aims 55° apart can overlap
  vertically but not at all horizontally — a distance-only rule would waste a
  match on them.
- The lens's radial distortion is carried through the whole model. The camera's
  `LENS_DISTORTION` coefficients ([`RadialDistortion`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/LensModel.kt))
  describe how the lens bends the pinhole projection, so the renderer samples
  the pixel the lens really recorded and the footprint walks the true (distorted)
  border — frame edges, where seams live, land where they should instead of
  bowing. The coefficients act on coordinates normalized by the *focal length*
  (`x_i = (x − c_x) / f_x`, the OpenCV convention), which is the one thing
  `LENS_DISTORTION` changed about the `LENS_RADIAL_DISTORTION` key it replaced —
  that older key normalized against the sensor array's farthest edge instead.
  Reading one through the other's convention overstates `k1` by `(f / halfEdge)²`
  and `k3` by the sixth power of the same, which is a correction several times
  larger than the distortion it is meant to remove.
- [`ExposureCompensation`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/ExposureCompensation.kt)
  fits per-frame brightness gains against the overlap graph — a frame shot into
  the sun and the frame beside it recorded against it no longer meet at a
  brightness cliff inside the cross-fade.
- [`PivotModel`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/PivotModel.kt)
  accounts for the fact that nobody turns the phone around its own lens. A
  panorama assumes every frame was shot from one point; holding a phone up and
  swivelling your *body* puts the camera on the end of a lever a third of a
  metre long, so between one frame and the one 90° later the lens has moved half
  a metre sideways. That is translation, not rotation, and it is what shows up
  as near objects refusing to line up while the far ones sit fine.

  The geometry is simple enough to correct for, because the camera is not just
  anywhere: it is at `leverArm × forward`, always ahead of the pivot along its
  own optical axis, which is what holding a phone out in front of you and
  turning does. Everything the pipeline projects is a direction from the pivot,
  so a scene point at a nominal distance is `sceneDistance × direction` and the
  ray the camera saw it along is that minus the lever arm. Only the *ratio* of
  the two lengths survives the subtraction, so neither has to be measured
  precisely — and because the lever points down the optical axis it cancels out
  of both lateral components, leaving the renderer's inner loop one subtraction
  heavier and nothing else.

  The default lever arm is 0.35 m against a nominal 10 m scene. The scene
  distance is deliberately long rather than typical: over-correcting bends
  frames apart just as surely as parallax bends them together, and this takes
  most of the error out of a close scene while adding under a degree to a
  distant one.

[`SphericalGeometry`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/SphericalGeometry.kt)
holds the maths: a `CameraBasis` built from a frame's yaw/pitch/roll, the
mapping between canvas pixels and directions on the sphere, and the *footprint* —
the block of canvas a frame can reach. The footprint is found by walking the
frame's border rather than its four corners, since at a wide field of view the
middle of an edge reaches a higher latitude than either corner does, and it
handles the two cases that break a naive bounding box: a frame straddling the
±180° seam comes back as one unwrapped range, and a frame containing a pole opens
out to the full width, because every longitude passes underneath it.

[`EquirectangularRenderer`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/EquirectangularRenderer.kt)
does the painting. Every output pixel is a direction; for each frame that
direction is rotated into the frame's own axes and divided through by depth,
which gives the pixel that looked at it (through the distortion model), and
`Imgproc.remap` samples it. The exposure gains are applied before the blend, so
they land inside it. Overlaps resolve to a multi-band (Laplacian pyramid)
blend — see [`MultibandBlender`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/MultibandBlender.kt).
Each frame and its feather mask are split into bands, and each band is
cross-faded with a mask sized to the band: fine detail fades across a few
pixels, broad illumination across the whole overlap, so a seam is faded at
every scale by a transition narrower than the detail that scale carries. A
pixel only one frame reached still comes out at full strength, because its
mask divides back out.

**Seam carving** is the alternative to the wide cross-fade, toggled by the
debug **Seam** button next to Dist. [`SeamFinder`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/SeamFinder.kt)
asks which single frame should paint each pixel, instead of letting every
overlapping frame contribute, and the renderer then paints near-hard selections
that fade only a few pixels across each cut — the sharpness a cross-fade
spends. The question is posed as an energy over the overlap:

- The **data term** for a pixel and a frame is how far the frame's sample sits
  from the mean of every frame covering that pixel — the frame closest to what
  the overlap actually shows wins the interior.
- The **seam cost** for cutting between two neighbouring pixels with different
  frames is how differently the two frames see those pixels, so a cut through a
  region where the frames agree is cheap and a cut through a ghost is not. The
  cost is a metric (a frame that does not cover a pixel is filled with that
  pixel's mean colour, which keeps the triangle inequality intact), so every
  expansion move is submodular and its exact min-cut can never raise the
  energy.

The energy is minimized with α-expansion — [`SeamSolver`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/SeamSolver.kt)
— where each label in turn offers every pixel "keep your frame or switch to
this one", and each binary subproblem is an s-t min-cut over the pixel grid
(Dinic's max-flow). The whole solver is pure Kotlin, exercised on the JVM
against an exhaustive search on tiny grids. The seam is decided at a reduced
resolution (~512 columns across the canvas) because a cut only needs locating
to within a few output pixels, and the graph-cut's cost scales with that grid
rather than with the full canvas; [`SeamFeather`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/stitching/SeamWeights.kt)
then turns the label map into the narrow cross-fade the renderer looks up — the
winner holds weight 1 everywhere, and the loser's contribution ramps in from
half on the boundary to nothing a few pixels away. The multi-band machinery is
left to handle that narrow transition, so coarse illumination still meets
softly where the seam runs, while fine detail keeps both copies — the point of
carving instead of blending.

The result is equirectangular *by construction* rather than by cropping
something else into shape: a pixel's row is its latitude, so an incomplete sphere
is black exactly where it was not shot, at the right elevation. The accuracy
ceiling is the orientation sensor itself: the fused attitude is drift-free but
not perfect, and residual error shows up as soft doubling in the overlaps
rather than as a failed stitch.

**Memory.** A 4096-wide canvas needs 100 MB of float accumulator if it is held
at once, which is exactly the allocation that ends a stitch on a mid-range
phone. Every level of the blend is built in horizontal bands instead, so only
the band being accumulated exists in float, and the coarse levels that carry
the wide cross-fade live in mats a small fraction of the canvas — the peak is
tens of megabytes rather than a hundred, the same order as the unsharp mask
that runs after it. The canvas is also never rendered wider than the frames
justify — a 1024 px frame across 66° carries about 15.5 px per degree, so a
full turn is worth ~5600 px, and rendering beyond that would only interpolate.

**The field of view is self-checked.** The angles handed to the stitcher describe
the upright frame as captured on the portrait-locked display, and `stitchPhotos`
verifies them against the first decoded frame: a lens has square pixels, so if
the horizontal and vertical angles imply focal lengths that disagree by more
than a real lens could, the pair is swapped and a warning is logged. It is a
safety net for the one mis-description that would silently bend every frame out
of shape — there is no error message that would rescue a run whose frames were
all projected through the wrong axis.

Failures come back as a failed `Result` carrying a `StitchException` with a
`StitchStatus`: too little of the sphere covered, a frame that would not decode,
out of memory.

**In the UI.** A **Finish & stitch** button appears in
`PhotoSphereCameraScreen` once three frames are buffered, and a modal with a
progress indicator covers the screen while the work runs. Both long stages report
real progress — one frame at a time while decoding, one band at a time while
rendering. Cancelling takes effect at the next band boundary. On success the
sphere is written to the cache as a GPano-tagged JPEG, the buffered frames are
deleted, and the result screen takes over; on failure the frames are kept,
because the usual fix is to capture a few more and try again.

Three is the floor, not the target. Frames are placed from their measured pose
rather than by searching for a chain of matches, so the pipeline has no
minimum-frames requirement of its own — what more frames buy is coverage, and
how much coverage is enough is the user's call to make on the result screen. A
partial capture comes back as an equirectangular image that is black where the
sphere was never shot, at the right elevation.

## The finished sphere

**GPano metadata.** [`GPanoXmpInjector`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/metadata/GPanoXmpInjector.kt)
is what makes the output a *360 photo* rather than a wide picture: Google
Photos, Facebook and friends switch to a sphere viewer on an XMP packet in the
`GPano` namespace, and `ExifInterface` writes EXIF only. It walks the JPEG's
marker segments, drops any XMP already present, splices an `APP1` segment in
after JFIF/Exif, and copies every remaining byte — tables, frame header and the
entropy-coded scan — through verbatim. **Nothing is decoded or re-encoded**, so
tagging costs no image quality on a file that has already been through one
generation of JPEG on the way in. Eight properties are written:
`UsePanoramaViewer`, `ProjectionType`, `FullPano{Width,Height}Pixels` and the
four `CroppedArea*` values, which cover the whole image because the renderer
draws straight into a full 2:1 canvas and leaves what the capture never reached
black. It is plain byte-level work on an okio `Path`, so it is covered by local unit
tests that assert the image data comes out byte-identical.

**The result screen.** [`PanoramaResultScreen`](composeApp/src/commonMain/kotlin/com/n30dyn4m1c/photosphere/result/PanoramaResultScreen.kt)
shows the sphere flat — the black wedges at the poles are the parts the run
never reached, and they are worth seeing before deciding to keep it — over three
actions: **Export to gallery**, **Share**, and **New photo**. Nothing has been
published at this point, which is deliberate: a run that came out badly should
not have to be deleted out of the camera roll afterwards. "New photo" discards
the cached JPEG and returns to capture with an empty buffer; the system back
gesture does the same thing.

Because that cached JPEG is the only copy until an export lands, both routes out
**confirm first** while the sphere is unsaved — "New photo" sits directly beside
"Share", and a stray tap on it (or a back gesture) would otherwise throw away
several minutes of standing in one spot turning around, silently. Once the photo
has been written to the gallery the question stops being worth asking and the
prompt no longer appears.

**Export.** [`MediaExporter`](composeApp/src/androidMain/kotlin/com/n30dyn4m1c/photosphere/storage/MediaExporter.kt)
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
(`androidMain/res/xml/file_paths.xml` exposes `cacheDir/spheres/` and nothing else) with a
read grant attached.

## Status

The codebase is now Kotlin Multiplatform: one shared source set builds the
Android APK, the unsigned iOS `.ipa` (free install via Sideloadly/AltStore) and
a desktop JVM target that runs the test suite anywhere. Pipeline features that
were once listed here as outstanding have landed:

- **Multi-band blending** — overlaps resolve to a Laplacian pyramid blend that
  fades fine detail over a few pixels and broad illumination over the whole
  overlap.
- **Seam carving** — instead of blending every overlap, a graph-cut finds the
  assignment of each pixel to the frame that agrees best and cuts there, fading
  only a few pixels across it.

Gone along the way: the OpenCV dependency and the ORB pose/focal refinement
that rode on it — the stitcher is pure shared Kotlin placing frames from their
measured poses (see [Stitching](#stitching)).

What is still outstanding is tracked in
[the issue tracker](https://github.com/n30dyn4m1c/360-photo-app/issues). The
larger items at the time of writing: guided capture cannot be driven without
sight, the result screen previews the sphere flat rather than as a pannable
view, there are no instrumented UI tests, the strings are English-only, and the
activity's hard portrait lock is ignored from Android 16 onward.

## Test builds

Installable builds are published as workflow artifacts rather than committed to
the repository — committing one per rebuild would grow the history by tens of
megabytes every time. Grab `nalabo360-debug-apk` from the Android workflow or
`nalabo360-unsigned-ipa` from the iOS one.

To build your own, see [Build](#build): `./gradlew :composeApp:assembleDebug`
writes `composeApp-debug.apk`, and `iosApp/build-ipa.sh` packs the unsigned
`.ipa` on a Mac.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
