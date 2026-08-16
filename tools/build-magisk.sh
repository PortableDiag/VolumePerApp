#!/usr/bin/env bash
#
# Packages the Magisk module zip.
#
# Takes the APK you point it at, drops it into the module tree, and zips the
# result. It does not build the APK — that is gradle's job, and keeping them
# separate means you can package a release APK signed by the vault without this
# script ever seeing a key.
#
# Usage:
#   tools/build-magisk.sh [path/to/app.apk]
#
# With no argument it prefers the signed release APK and falls back to debug,
# saying which one it used.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/dist"
MODULE="$ROOT/magisk"

APK="${1:-}"
if [[ -z "$APK" ]]; then
  for candidate in \
      "$ROOT/dist/VolumePerApp-release.apk" \
      "$ROOT/app/build/outputs/apk/release/app-release.apk" \
      "$ROOT/app/build/outputs/apk/debug/app-debug.apk"; do
    if [[ -f "$candidate" ]]; then APK="$candidate"; break; fi
  done
fi

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "error: no APK found. Build one first:" >&2
  echo "  ./gradlew :app:assembleRelease" >&2
  exit 1
fi

VERSION=$(grep -oP 'versionName "\K[^"]+' "$ROOT/app/build.gradle")
VERSION_CODE=$(grep -oP 'versionCode \K[0-9]+' "$ROOT/app/build.gradle")

echo "APK      : $APK"
echo "version  : $VERSION ($VERSION_CODE)"

# Keep module.prop's version in step with the app's, so a module in a Magisk
# list is identifiable without unzipping it.
sed -i "s/^version=.*/version=v$VERSION/"      "$MODULE/module.prop"
sed -i "s/^versionCode=.*/versionCode=$VERSION_CODE/" "$MODULE/module.prop"

mkdir -p "$MODULE/system/priv-app/VolumePerApp" "$OUT_DIR"
cp "$APK" "$MODULE/system/priv-app/VolumePerApp/VolumePerApp.apk"

ZIP="$OUT_DIR/VolumePerApp-magisk-v$VERSION.zip"
rm -f "$ZIP"

# -x on the build artefacts so a stale copy in the tree cannot ship.
( cd "$MODULE" && zip -q -r "$ZIP" . -x '.*' -x '*/.*' )

echo "module   : $ZIP"
unzip -l "$ZIP" | sed 's/^/           /'

cat <<'NOTE'

Flash it in Magisk Manager -> Modules -> Install from storage, then reboot.

To check it took, without opening the app:
  adb shell pm path com.volumeperapp.app      # should be /system/priv-app/...
  adb shell dumpsys package com.volumeperapp.app | grep MODIFY_AUDIO_ROUTING
NOTE
