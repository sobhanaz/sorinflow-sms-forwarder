#!/bin/bash
# Installs a release APK on the phone attached via adb.
#   scripts/install-latest.sh            -> the rolling "Latest build (main)" pre-release
#   scripts/install-latest.sh v3.1.0     -> a versioned release
# Needs the GitHub CLI (gh) and adb on PATH.
set -euo pipefail
REPO=${REPO:-sobhanaz/sorinflow-sms-forwarder}
TAG=${1:-latest}
TMP=$(mktemp -d)
gh release download "$TAG" --repo "$REPO" -p '*.apk' -D "$TMP"
APK=$(ls "$TMP"/*.apk | head -1)
echo "Installing $(basename "$APK") ..."
adb install -r "$APK"
adb shell dumpsys package ir.sorinflow.smsforwarder | grep -m1 versionName
