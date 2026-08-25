# OpenCV is no longer part of the dependency graph (the stitching pipeline is
# pure Kotlin and shared with iOS), so the old JNI keep rules are gone too.
#
# CameraX and Compose ship their own consumer rules; nothing extra is required
# here.
