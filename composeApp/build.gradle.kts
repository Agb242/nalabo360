import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // Android remains a full application target: MainActivity is the entry point
    // and everything under src/androidMain ships inside the APK.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS device + simulator targets produce Nalabo360Kit.framework, which the
    // CI macOS runner links into an unsigned .ipa (see .github/workflows/ios.yml).
    // On non-macOS hosts these targets are simply skipped thanks to
    // `kotlin.native.ignoreDisabledTargets=true`, so Android/JVM builds work anywhere.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Nalabo360Kit"
            // Dynamic framework: the CI assembles Payload/*.app/Frameworks itself,
            // and Sideloadly/AltStore re-signs the whole bundle at install time.
            isStatic = false
        }
    }

    // Desktop JVM: never shipped, but it runs the common unit tests on any
    // machine (`./gradlew :composeApp:jvmTest`) with nothing but a JDK — no
    // Android SDK, no Xcode. It also keeps the shared UI honest: if someone
    // sneaks an androidx import into commonMain, this compilation fails first.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.animation)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.exifinterface)

            // CameraX
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
    }
}

android {
    namespace = "com.n30dyn4m1c.photosphere"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.n30dyn4m1c.nalabo360"
        // 26 keeps adaptive launcher icons, ART, and the modern camera2 pipeline
        // available without legacy fallbacks. CameraX itself supports 21+.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // OpenCV is gone from the dependency graph, so there is no need for ABI
    // splits any more: one universal APK carries every device.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG drives the pipeline's verbose debug logging.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
