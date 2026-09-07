# Reliability review

Base: `claude/shizuku-errors-apk-workflow-40q6np` at
`a8655692f0482d74f6b35b864a9ed5d26dd3f459`.
Changes: `codex/reliability-and-data-integrity`.

## Implemented

- **Continuous monitoring:** use `specialUse` with a declared battery-monitoring
  purpose. The former `dataSync` classification subjects background monitoring to
  Android 15's six-hour budget. Boot startup now uses `goAsync()` and a bounded
  settings read; denied starts retain the tap-to-start fallback.
- **Notification stability:** repeated service-start intents preserve the running
  notification. Basic battery sampling starts before privileged-access probing.
- **Drain timing:** interval durations use `elapsedRealtime()`, so manual clock
  changes or automatic time corrections do not distort measured drain rates.
- **Shell failures:** check process exit codes; detect errors following partial
  output; reject oversized dumps instead of parsing truncated data; start the
  legacy helper timeout before its blocking read. A Shizuku death also completes
  a pending bind promptly.
- **Refresh accuracy:** secondary device-idle or power queries no longer hide a
  failed primary battery dump or make its stale snapshot appear freshly collected.
- **Outage overhead:** failed background battery-dump polling backs off from 15
  seconds to the configured normal polling interval, rather than retrying every
  five seconds indefinitely. Successful collection restores the initial delay.
- **Import/export:** JSON and CSV restores use one database transaction, with
  batched sample inserts. Malformed CSV rows fail rather than silently converting
  invalid booleans to `false`. Missing/empty CSV sources fail. Concurrent data
  operations are blocked. Session exports honor overlapping date ranges and no
  longer silently stop at 10,000 sessions.
- **Build usability:** Codex branch pushes run the APK workflow. Test reports are
  retained; APK signatures are verified and SHA-256 checksums accompany downloads.
  Debug signing is reported from the APK itself, independently of release secrets.

The foreground-service declaration follows Android's documented
[service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
and [timeout behavior](https://developer.android.com/develop/background-work/services/fgs/timeout).
A Play Store release requires the matching special-use declaration/review in Play
Console; this code change does not establish Play approval.

## Recommended next work

1. **React to backend changes while monitoring.** The service chooses advanced or
   heuristic tracking once at startup. Subscribe to Shizuku availability/grants and
   switch modes deliberately, retaining the current session and recording any gap.
2. **Share one battery-dump collector.** `BstatsCollector`, `AdvancedDrainTracker`,
   and `DetailedStatsCollector` can independently request the same checkin dump.
   Coalesce concurrent reads and share a short-lived parsed snapshot. A manual
   refresh/reset should invalidate it. Measure refresh latency and the monitor's
   own CPU/battery cost before and after the change.
3. **Persist the drain ledger and measurement provenance.** Advanced drain totals
   currently reset when tracking restarts. Persist committed intervals, distinguish
   a process restart from a device reboot, and mark unobserved gaps. Show whether
   charge came from a hardware counter or a percentage/capacity estimate. The
   deep-sleep/awake mAh split is proportional estimation, not separate energy
   measurements, even though the time split is measured.
4. **Protect history through future upgrades and restores.** Add explicit Room
   migrations before changing the schema; the current destructive fallback can
   erase history. Add a restore preview with duplicate handling and a policy for
   imported unfinished sessions. Re-imported samples currently receive new IDs.
5. **Add session comparison and a diagnostic export.** Compare two selected
   discharge windows using screen-on/off time and normalized drain rates. Export
   the selected raw dump with collection time/backend to make OEM parser issues
   reproducible. Complete localization of hard-coded status and data-operation text.

## Verification

The added JVM tests cover malformed CSV rows, absent measurements, trailing empty
session fields, shell errors after partial output, exact byte limits, and multibyte
output limits. Existing parser/drain tests remain in the suite. GitHub Actions is
the build/test gate; local Gradle distribution downloads were unavailable.

Device verification is still needed for boot startup on Android 14–17, overnight
monitoring on Samsung, Shizuku stopping during a dump and reconnecting, and a
malformed CSV after multiple batches rolling back without changing stored history.
The special-use classification does not prevent OEM background-process eviction.
