# BatStats reliability and usability overhaul

Source: `claude/shizuku-errors-apk-workflow-40q6np`, commit `7b97fc28c81f28c8ff649e27a5858c0a919a416c`.
Work branch: `codex/batstats-ux-reliability-overhaul`.

This review covers the Android application, database and transfer paths, battery and privileged collectors, services, notifications, widgets, settings, navigation, charts, and build workflows. It incorporates the useful fixes from the earlier unmerged reliability branch while retaining the newer base's features. It cannot establish the absence of every possible bug or replace testing on real hardware.

## Reliability and data corrections

- Use the declared `specialUse` foreground-service type for user-enabled continuous monitoring; `dataSync` is subject to inappropriate time limits and boot restrictions for this job. Keep boot work alive with `goAsync`, respect auto-start, and provide a recovery notification on failure.
- Route the drain screen's start/pause control through the monitoring service. Basic screen-state accounting now works without root, Shizuku or ADB grants. A notification's drain destination opens the correct screen without overwriting ordinary app-launch intents.
- Serialize sample/session boundaries, ignore callbacks from stopped monitoring generations, start automatic sessions on the first reading, and prevent duplicate open manual sessions.
- Treat Android's unsupported current/charge-counter sentinels as missing readings. Preserve unknown values in cards, charts and widgets; show battery protection's plugged-but-not-charging state accurately.
- Bound shell output, reject truncated/nonzero/permission-denied output, time out stuck commands and binding, and propagate Shizuku service death to pending work. Preserve the actual ADB denial reason, require DUMP for dumpsys, and account for development-granted Usage Stats access and its app-op.
- Read protected battery, CPU, thermal and wakelock files through root rather than through the app UID after a successful root probe.
- Establish a baseline before attributing per-UID energy; handle battery-stat resets, ignore non-finite energy, and commit a poll's package deltas atomically. Combine system sources in history without adding foreground estimates to them.
- Share the checkin cache among collectors. Parse CPU suspension using battery realtime minus battery uptime rather than Doze mode time. Use monotonic clocks for drain segments, handle counter availability changes, and exclude snapshots from cancelled/reset runs.
- Restrict foreground-only estimates to screen-on discharge, stop attributing paused/stopped activities, and handle revoked usage access.
- Clear history off the UI thread after stopping monitoring. Sample counts reflect retained/imported rows.
- Stream JSON exports and CSV transfers in batches. Validate imports in one transaction, deduplicate sample timestamps, reject conflicting open sessions, honor export selections and overlapping date ranges, retain the automatic-session flag, and clean up incomplete CSV exports. JSON imports are limited to 32 MB; CSV rows are bounded and the archive is streamed. Older nine-column session CSVs remain readable.
- Correct the release workflow's application ID and fail APK verification when no installable debug artifact was produced.

## Interface and performance

- A quieter dashboard prioritizes level, charging state, current, monitoring controls, sensor readings and session actions. Removed continuous decorative animations.
- Searchable per-app history with source labels, date ranges, total shares, system-app filters, useful empty states and app settings actions. Time windows advance while the screen is open.
- Labeled bottom navigation on phones and a navigation rail on wider layouts. Consume navigation insets once to avoid wasted space; detail/export screens use the available height. Access indicators and actions wrap on narrow displays.
- Searchable settings and session history, visible access/setup actions, destructive-action confirmations, busy states and actionable errors. Session search includes records older than the previous 100-row limit.
- SQL aggregation bounds dashboard and session charts to approximately 300 points. Session completion reads aggregate/endpoint queries, and closed-session charts no longer reload on every live battery reading.
- Scrollable session/export/access screens; charts use timestamps, retain missing readings and honor Fahrenheit. English and Turkish copy added; other existing locales use English fallback for the new strings pending translation.

## Verification

Run with JDK 21 and the repository's Gradle wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease -PenableApkSplits=false
./gradlew :app:connectedDebugAndroidTest -PenableApkSplits=false
```

Automated device checks are defined in `.github/workflows/device-tests.yml` for API 26 and API 36; API 36 also uses 1.3x text. Tests exercise navigation, search, export selection, monitoring start/pause, notification routing, real battery dumps with ADB grants, import deduplication/rollback, session conflicts, date overlap and bounded SQL charts. Screenshots and reports are retained as workflow artifacts. See the pull request for the final run results.

## Hardware checks still needed

On the Galaxy S25 Ultra / One UI, check charge protection while plugged in, screen-off drain across an actual suspend, unplug/replug, service behavior after reboot and app dismissal, and Shizuku loss/restart (including Android Auto). Check root sysfs data on a rooted device and ADB-only behavior with explicit grants. Emulator results cannot validate OEM fuel gauges, doze residency, process killing or real battery savings.

Per-app energy remains Android's battery-accounting estimate, shared UIDs cannot be split exactly among packages, and assigning drain between sleep/awake states is an estimate. No measured FPS, memory or battery-life improvement is claimed without device profiling. Release APKs remain unsigned when no release keystore is supplied; debug APKs are installable under `org.mlm.batstats.debug`.

Platform references: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [Compose testing](https://developer.android.com/develop/ui/compose/testing), [Android dump permission checks](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/com/android/internal/util/DumpUtils.java).
