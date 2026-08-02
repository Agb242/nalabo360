# Photo Sphere

Android app scaffold for capturing and stitching 360° photo spheres.

This repository currently contains the **base project configuration**: Gradle
setup, manifest permissions, runtime permission handling, and a working CameraX
capture screen that saves frames through MediaStore. The stitching pipeline
itself is not implemented yet.

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
| `WRITE_EXTERNAL_STORAGE` | runtime, `maxSdkVersion="28"` | Saving on pre-scoped-storage devices |
| `READ_EXTERNAL_STORAGE` | runtime, `maxSdkVersion="32"` | Reading spheres this app did not create |
| `READ_MEDIA_IMAGES` | runtime (API 33+) | Same, on Android 13+ |

`HIGH_SAMPLING_RATE_SENSORS` is a normal permission — it is granted at install
time, and requesting it at runtime always returns "denied". Declaring it is
sufficient.

Saving uses MediaStore (`Pictures/PhotoSphere/`) on every API level, so from
API 29 up no storage permission is requested at all.

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
├── camera/CaptureScreen.kt      # CameraX preview + shutter
├── storage/SphereImageStore.kt  # MediaStore writes + EXIF stamping
└── ui/theme/                    # Material 3 theme
```

## Not implemented yet

- Sensor-driven capture guidance (target reticles, coverage map)
- OpenCV `Stitcher` pass over the captured frames
- XMP **GPano** metadata on the stitched equirectangular output. This is what
  makes Google Photos and other viewers render an image as a sphere —
  `ExifInterface` writes EXIF only and cannot write XMP, so this needs a
  separate writer.
