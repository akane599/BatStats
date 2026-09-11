#!/usr/bin/env bash
set -euo pipefail

collect_device_artifacts() {
  adb pull /sdcard/Android/data/org.mlm.batstats.debug/files/screenshots screenshots || true
  adb logcat -d > device-logcat.txt || true
}
trap collect_device_artifacts EXIT

./gradlew :app:connectedDebugAndroidTest -PenableApkSplits=false --stacktrace
