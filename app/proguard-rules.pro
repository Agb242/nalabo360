# OpenCV's Java classes are reached from native code via JNI, so they must keep
# their names. Without this the release build crashes on the first Mat access.
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX ships its own consumer rules; nothing extra is required here.
