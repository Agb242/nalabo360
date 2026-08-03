pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // OpenCV's official Android SDK (org.opencv:opencv) is published here.
        mavenCentral()
    }
}

rootProject.name = "PhotoSphere"
include(":app")

// If you opt for the local OpenCV SDK module instead of the Maven artifact,
// uncomment both lines below (see README.md -> "Option B").
// include(":opencv")
// project(":opencv").projectDir = file("third_party/opencv-android-sdk/sdk")
