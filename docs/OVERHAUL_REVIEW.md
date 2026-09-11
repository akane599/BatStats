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

## Follow-up: misleading activity labels and monitor notification

A device screenshot exposed a semantic error in the earlier overhaul: the phone stayed screen-on for the entire session, but the activity card attributed part of that time and energy to “Idle.” That label came from a 200 mA instantaneous-current threshold, not a measurement of user interaction. The interval's average drain could also exceed the threshold used to classify its starting sample.

The correction removes the Active/Idle split entirely. Screen-on and screen-off remain the measured discharge buckets. One screen-off sleep card shows CPU deep-sleep and awake durations, each as a share of recorded screen-off time. It shows an explanatory empty state when no screen-off time has occurred. Sleep and awake do not receive allocated mAh or drain rates, and the current-state title no longer claims that the CPU is currently in deep sleep based on a previous interval. Doze state remains distinct from CPU suspension.

Additional measurement corrections:

- A missing charge counter, a zero counter at a nonempty/unknown battery level, or an increase caused by gauge recalibration leaves the affected energy total and rate unavailable. A partial energy sum is not divided by a complete observation duration. Valid measured zero drain remains zero; insufficient rate windows remain unavailable.
- A common ledger accounts for polling and screen/power boundaries. Monotonic elapsed time drives session duration; stopping monitoring freezes it, and resetting a paused session leaves it at zero. Powered and unknown-power intervals do not contribute to discharge buckets.
- Unknown battery levels display an em dash and no level arc. History excludes invalid levels. An empty session no longer implies 100% screen-off time. Session totals use rows that accommodate larger values and text.
- Detailed Stats distinguishes missing summary counters from recorded zero, does not display a screen-off rate without screen-off duration, and does not display unsupported capacity as 0 mAh. Its state card describes the last observed system state; failed individual state reads clear the old result instead of presenting it under a fresh overall update time.
- External power is separate from charging status. Omitted system-state fields remain unknown. Numeric charging statuses and multiple Doze fields on one line are parsed explicitly.
- Removed the invented battery-health gauge derived solely from cycle count. The root card retains factual cycle counts and gauge-reported full/design capacity, with the ratio labeled accordingly.
- Corrected the Doze checkin columns previously called maintenance duration/count: Android defines these as its broader full-idling interval and number of idling periods. They are now labeled as such, and explicit zero counters remain visible. Android battery-accounting scope and capacity-based percentages are explained on the overview.

The monitor notification now has one owner and one foreground-notification ID. Its Pause action stops monitoring, and its tap opens the appropriate app destination. Minimal, compact and detailed styles use the current battery/power state; missing readings do not become 0% or zero current. Detailed drain content separates screen-on/off averages from CPU awake/sleep durations and identifies the last reading. Basic drain presentation works without root, Shizuku or ADB access, and notification refreshes do not add privileged dump polling. Updates remain silent; notification visibility preferences select the visible presentation or the quiet foreground-service fallback. Historical averages are not described as instantaneous current or current CPU sleep.

The follow-up adds regression cases for all-screen-on sessions, missing/reset counters, valid zero drain, pause/reset timing, absent system fields, plugged-but-not-charging status, Doze semantics and notification behavior. Validation of these corrections is pending; the earlier results below are historical.

## Verification

Historical baseline (`a0223bd`): the earlier overhaul recorded 121 passing unit tests, eight passing device tests on each API 26 and API 36 emulator, successful debug/release builds, and lint with zero errors. Those results apply to that earlier commit, not to the screenshot-driven corrections above.

Current follow-up: build, lint, unit-test and device-test results are pending. Final results and the exact tested commit will be recorded in the pull request after the checks finish.

Run with JDK 21 and the repository's Gradle wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease -PenableApkSplits=false
./gradlew :app:connectedDebugAndroidTest -PenableApkSplits=false
```

Automated device checks are defined in `.github/workflows/device-tests.yml` for API 26 and API 36; API 36 also uses 1.3x text. Tests exercise navigation, search, export selection, monitoring start/pause, notification routing, real battery dumps with ADB grants, import deduplication/rollback, session conflicts, date overlap and bounded SQL charts. Screenshots and reports are retained as workflow artifacts. See the pull request for the final run results.

## Hardware checks still needed

On the Galaxy S25 Ultra / One UI, check charge protection while plugged in, screen-off drain across an actual suspend, unplug/replug, service behavior after reboot and app dismissal, and Shizuku loss/restart (including Android Auto). Check root sysfs data on a rooted device and ADB-only behavior with explicit grants. Emulator results cannot validate OEM fuel gauges, doze residency, process killing or real battery savings.

Per-app energy remains Android's battery-accounting estimate, and shared UIDs cannot be split exactly among packages. Screen-on/off energy depends on the device's charge counter; CPU sleep/awake durations do not establish separate energy measurements. No measured FPS, memory or battery-life improvement is claimed without device profiling. Release APKs remain unsigned when no release keystore is supplied; debug APKs are installable under `org.mlm.batstats.debug`.

Platform references: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [Compose testing](https://developer.android.com/develop/ui/compose/testing), [Android dump permission checks](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/com/android/internal/util/DumpUtils.java), [Android battery accounting and full-idling definitions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryStats.java).
