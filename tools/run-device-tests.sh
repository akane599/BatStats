#!/usr/bin/env bash
set -euo pipefail

collect_device_artifacts() {
  adb pull /sdcard/Android/data/org.mlm.batstats.debug/files/screenshots screenshots || true
  adb logcat -d > device-logcat.txt || true
}
trap collect_device_artifacts EXIT

# The API 26 Google APIs image bundles a broken Messages RCS service: it crashes
# over the app and holds the background broadcast queue for 60 seconds. Isolate
# this unrelated image component only on disposable CI emulators, never phones.
if [[ "${CI:-false}" == "true" &&
      "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" &&
      "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == "26" ]]; then
  if [[ "$(adb shell pm path com.google.android.apps.messaging | tr -d '\r')" == package:* ]]; then
    adb shell am force-stop --user 0 com.google.android.apps.messaging
    adb shell pm disable-user --user 0 com.google.android.apps.messaging
  fi
fi

./gradlew :app:connectedDebugAndroidTest -PenableApkSplits=false --stacktrace
