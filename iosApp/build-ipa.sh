#!/usr/bin/env bash
#
# Builds an UNSIGNED .ipa for free on-device installs — no paid Apple Developer
# account, no committed Xcode project. Run this on macOS (CI or a local Mac):
#
#   ./iosApp/build-ipa.sh
#
# What it does:
#   1. Links the Kotlin Multiplatform dynamic framework (Gradle, device arch).
#   2. Compiles iosApp/main.swift with swiftc and links it against that
#      framework — this replaces what an .xcodeproj would do.
#   3. Hand-assembles Payload/Nalabo360.app (binary + Info.plist + embedded
#      framework) and ad-hoc signs it so the bundle is structurally valid.
#   4. Zips it into iosApp/build/Nalabo360-unsigned.ipa.
#
# Sideloadly / AltStore re-sign everything with a personal free Apple ID at
# install time; see docs/installation-iphone-gratuit.md.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

APP_NAME="Nalabo360"
BUNDLE_ID="com.n30dyn4m1c.nalabo360"
DEPLOYMENT_TARGET="15.0"

FRAMEWORK_NAME="Nalabo360Kit.framework"
FRAMEWORK_DIR="$ROOT/composeApp/build/bin/iosArm64/releaseFramework"
FRAMEWORK="$FRAMEWORK_DIR/$FRAMEWORK_NAME"

BUILD_DIR="$ROOT/iosApp/build"
APP_DIR="$BUILD_DIR/Payload/$APP_NAME.app"

step() { printf '\n==> %s\n' "$1"; }

step "1/5 Linking $FRAMEWORK_NAME (iosArm64, release)"
"$ROOT/gradlew" -p "$ROOT" :composeApp:linkReleaseFrameworkIosArm64 --no-daemon
[ -d "$FRAMEWORK" ] || { echo "Framework not found at: $FRAMEWORK" >&2; exit 1; }

step "2/5 Compiling main.swift and linking against $FRAMEWORK_NAME"
rm -rf "$BUILD_DIR"
mkdir -p "$APP_DIR/Frameworks"
xcrun -sdk iphoneos swiftc \
    -O \
    -target "arm64-apple-ios$DEPLOYMENT_TARGET" \
    -F "$FRAMEWORK_DIR" \
    -framework Nalabo360Kit \
    -Xlinker -rpath -Xlinker @executable_path/Frameworks \
    -o "$APP_DIR/$APP_NAME" \
    "$ROOT/iosApp/main.swift"

step "3/5 Assembling $APP_NAME.app"
cp "$ROOT/iosApp/Info.plist" "$APP_DIR/Info.plist"
cp -R "$FRAMEWORK" "$APP_DIR/Frameworks/"

step "4/5 Ad-hoc signing (Sideloadly/AltStore replace every signature at install)"
codesign --force --sign - --timestamp=none \
    "$APP_DIR/Frameworks/$FRAMEWORK_NAME"
codesign --force --sign - --timestamp=none "$APP_DIR"
codesign --verify --deep "$APP_DIR"

step "5/5 Packing the unsigned .ipa"
(cd "$BUILD_DIR" && zip -qry "$APP_NAME-unsigned.ipa" Payload)

IPA="$BUILD_DIR/$APP_NAME-unsigned.ipa"
echo
echo "Done: $IPA ($(du -h "$IPA" | cut -f1))"
echo "Install it for free with Sideloadly or AltStore — see docs/installation-iphone-gratuit.md."
