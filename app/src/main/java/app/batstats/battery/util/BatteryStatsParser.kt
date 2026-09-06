package app.batstats.battery.util

import kotlin.math.roundToLong

/**
 * Comprehensive parser for `dumpsys batterystats --checkin` and related commands.
 * Extracts detailed per-app and system-wide battery statistics.
 */
object BatteryStatsParser {

    data class FullSnapshot(
        val capturedAt: Long = System.currentTimeMillis(),
        val statsSinceCharged: Boolean = true,
        val batteryRealtimeMs: Long = 0L,
        val screenOnTimeMs: Long = 0L,
        val screenOffDischargePercent: Float = 0f,
        val screenOnDischargePercent: Float = 0f,
        val estimatedCapacityMah: Int = 0,
        val apps: List<AppPowerStats> = emptyList(),
        val wakelocks: List<WakelockStats> = emptyList(),
        val kernelWakelocks: List<KernelWakelockStats> = emptyList(),
        val alarms: List<AlarmStats> = emptyList(),
        val jobs: List<JobStats> = emptyList(),
        val syncs: List<SyncStats> = emptyList(),
        val network: List<NetworkStats> = emptyList(),
        val sensors: List<SensorStats> = emptyList(),
        val signalStrength: List<SignalStrengthStats> = emptyList(),
        val wifiSignal: List<WifiSignalStats> = emptyList(),
        val bluetooth: BluetoothStats? = null,
        val doze: DozeStats? = null,
        val cpuFrequency: List<CpuFrequencyStats> = emptyList(),
        val processStats: List<ProcessStats> = emptyList(),
        /**
         * How many uid -> package mappings the dump carried. Well below [apps] size means
         * the dump was produced by a caller that cannot see most packages, so rows fall
         * back to "uid:NNNNN" - see the ADB note in DetailedStatsScreen.
         */
        val mappedPackages: Int = 0
    )

    data class AppPowerStats(
        val uid: Int,
        val packageName: String,
        val powerMah: Double,
        val cpuTimeMs: Long = 0L,
        val cpuPowerMah: Double = 0.0,
        val wakeLockTimeMs: Long = 0L,
        val wakeLockPowerMah: Double = 0.0,
        val mobilePowerMah: Double = 0.0,
        val wifiPowerMah: Double = 0.0,
        val gpsPowerMah: Double = 0.0,
        val sensorPowerMah: Double = 0.0,
        val cameraPowerMah: Double = 0.0,
        val flashlightPowerMah: Double = 0.0,
        val audioPowerMah: Double = 0.0,
        val videoPowerMah: Double = 0.0,
        val bluetoothPowerMah: Double = 0.0,
        val screenPowerMah: Double = 0.0,
        val proportionalSmearMah: Double = 0.0,
        val foregroundTimeMs: Long = 0L,
        val foregroundServiceTimeMs: Long = 0L,
        val backgroundTimeMs: Long = 0L,
        val cachedTimeMs: Long = 0L,
        val topTimeMs: Long = 0L,
        val mobileRxBytes: Long = 0L,
        val mobileTxBytes: Long = 0L,
        val wifiRxBytes: Long = 0L,
        val wifiTxBytes: Long = 0L,
        val mobileRxPackets: Long = 0L,
        val mobileTxPackets: Long = 0L,
        val wifiRxPackets: Long = 0L,
        val wifiTxPackets: Long = 0L,
        val gpsTimeMs: Long = 0L,
        val sensorTimeMs: Long = 0L,
        val cameraTimeMs: Long = 0L,
        val flashlightTimeMs: Long = 0L,
        val audioTimeMs: Long = 0L,
        val videoTimeMs: Long = 0L,
        val bluetoothScanTimeMs: Long = 0L,
        val bluetoothUnoptimizedScanTimeMs: Long = 0L,
        /** Filled from the human-readable dump; see [parseEstimatedPowerUse]. */
        val powerByState: List<UidPowerState> = emptyList()
    )

    /**
     * Power attributed to an app while it sat in one process state.
     *
     * Android 12 replaced BatterySipper's per-component split (cpu/wifi/gps/...) with
     * BatteryUsageStats, which attributes an app's drain by process state instead. The
     * per-component numbers now only exist device-wide, so this is the only per-app
     * breakdown current releases actually report.
     */
    data class UidPowerState(
        val state: String,
        val label: String,
        val powerMah: Double,
        /** 0 when the dump prints the power without a duration. */
        val durationMs: Long
    )

    data class WakelockStats(
        val uid: Int,
        val packageName: String,
        val tag: String,
        val type: WakelockType,
        val count: Int,
        val totalTimeMs: Long,
        val maxTimeMs: Long = 0L,
        val backgroundTimeMs: Long = 0L,
        val backgroundCount: Int = 0
    )

    enum class WakelockType { PARTIAL, FULL, WINDOW, DRAW }

    data class KernelWakelockStats(
        val name: String,
        val count: Int,
        val totalTimeMs: Long,
        val activeCount: Int = 0,
        val maxTimeMs: Long = 0L,
        val lastChangeMs: Long = 0L,
        val preventSuspendTimeMs: Long = 0L
    )

    data class AlarmStats(
        val uid: Int,
        val packageName: String,
        val tag: String,
        val count: Int,
        val wakeups: Int,
        val totalTimeMs: Long,
        val backgroundCount: Int = 0,
        val backgroundTimeMs: Long = 0L
    )

    data class JobStats(
        val uid: Int,
        val packageName: String,
        val jobName: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundCount: Int = 0,
        val backgroundTimeMs: Long = 0L
    )

    data class SyncStats(
        val uid: Int,
        val packageName: String,
        val authority: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundCount: Int = 0,
        val backgroundTimeMs: Long = 0L
    )

    data class NetworkStats(
        val uid: Int,
        val packageName: String,
        val mobileRxBytes: Long,
        val mobileTxBytes: Long,
        val wifiRxBytes: Long,
        val wifiTxBytes: Long,
        val btRxBytes: Long = 0L,
        val btTxBytes: Long = 0L,
        val mobileActiveTimeMs: Long = 0L,
        val mobileActiveCount: Int = 0
    )

    data class SensorStats(
        val uid: Int,
        val packageName: String,
        val sensorHandle: Int,
        val sensorName: String,
        val count: Int,
        val totalTimeMs: Long,
        val backgroundTimeMs: Long = 0L,
        val backgroundCount: Int = 0
    )

    data class SignalStrengthStats(
        val level: Int, // 0 (none) to 4 (great)
        val durationMs: Long,
        val percentOfTotal: Float
    )

    data class WifiSignalStats(
        val level: Int, // 0 (none) to 4 (great)
        val durationMs: Long,
        val percentOfTotal: Float
    )

    data class BluetoothStats(
        val idleTimeMs: Long,
        val rxTimeMs: Long,
        val txTimeMs: Long,
        val powerMah: Double,
        val scanTimeMs: Long = 0L
    )

    data class DozeStats(
        val idleModeTimeMs: Long,
        val idleModeCount: Int,
        val deepIdleTimeMs: Long,
        val deepIdleCount: Int,
        val lightIdleTimeMs: Long,
        val lightIdleCount: Int,
        val maintenanceTimeMs: Long,
        val maintenanceCount: Int
    )

    data class CpuFrequencyStats(
        val cluster: Int,
        val frequency: Long,
        val timeMs: Long,
        val percentOfTotal: Float
    )

    data class ProcessStats(
        val uid: Int,
        val packageName: String,
        val processName: String,
        val userTimeMs: Long,
        val systemTimeMs: Long,
        val foregroundTimeMs: Long,
        val starts: Int
    )

    fun parseCheckin(raw: String): FullSnapshot {
        val lines = raw.lineSequence()
        val uidToPkg = mutableMapOf<Int, String>()
        val appStats = mutableMapOf<Int, AppPowerStats>()
        val wakelocks = mutableListOf<WakelockStats>()
        val kernelWakelocks = mutableListOf<KernelWakelockStats>()
        val alarms = mutableListOf<AlarmStats>()
        val jobs = mutableListOf<JobStats>()
        val syncs = mutableListOf<SyncStats>()
        val network = mutableListOf<NetworkStats>()
        val sensors = mutableListOf<SensorStats>()
        val signalStrength = mutableListOf<SignalStrengthStats>()
        val wifiSignal = mutableListOf<WifiSignalStats>()
        var bluetooth: BluetoothStats? = null
        var doze: DozeStats? = null
        val cpuFreq = mutableListOf<CpuFrequencyStats>()
        val processStats = mutableListOf<ProcessStats>()
        val uidTimes = mutableMapOf<Int, UidTimes>()

        var batteryRealtimeMs = 0L
        var screenOnTimeMs = 0L
        var screenOffDischarge = 0f
        var screenOnDischarge = 0f
        var estCapacity = 0

        lines.forEach { line ->
            val parts = splitCheckinLine(line)
            if (parts.size < 4) return@forEach

            try {
                when {
                    // UID mapping: 9,0,i,uid,<uid>,<package>
                    parts.getOrNull(2) == "i" && parts.getOrNull(3) == "uid" && parts.size >= 6 -> {
                        val uid = parts[4].toIntOrNull() ?: return@forEach
                        uidToPkg[uid] = parts[5]
                    }

                    // Power use item: 9,<uid>,l,pwi,<type>,<mAh>,...
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "pwi" -> {
                        parsePowerUseItem(parts, uidToPkg, appStats)
                    }

                    // Wakelock: 9,<uid>,l,wl,<name>,<type>,<count>,<time>...
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "wl" -> {
                        parseWakelock(parts, uidToPkg, wakelocks)
                    }

                    // Kernel wakelock: 9,0,l,kwl,<name>,<count>,<time>...
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "kwl" -> {
                        parseKernelWakelock(parts, kernelWakelocks)
                    }

                    // Alarm: 9,<uid>,l,wua,<tag>,<count>,<time>,<wakeups>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "wua" -> {
                        parseAlarm(parts, uidToPkg, alarms)
                    }

                    // Job: 9,<uid>,l,jb,<job>,<count>,<time>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "jb" -> {
                        parseJob(parts, uidToPkg, jobs)
                    }

                    // Sync: 9,<uid>,l,sy,<authority>,<count>,<time>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "sy" -> {
                        parseSync(parts, uidToPkg, syncs)
                    }

                    // Network: 9,<uid>,l,nt,<rxB>,<txB>,...
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "nt" -> {
                        parseNetwork(parts, uidToPkg, network)
                    }

                    // Sensor: 9,<uid>,l,sr,<handle>,<count>,<time>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "sr" -> {
                        parseSensor(parts, uidToPkg, sensors)
                    }

                    // CPU: 9,<uid>,l,cpu,<userMs>,<systemMs>,<legacyPower>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "cpu" -> {
                        val uid = parts[1].toIntOrNull()
                        if (uid != null) {
                            val user = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                            val system = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                            uidTimes.getOrPut(uid) { UidTimes() }.cpuMs += user + system
                        }
                    }

                    // Foreground activity timer: 9,<uid>,l,fg,<timeMs>,<count>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "fg" -> {
                        val uid = parts[1].toIntOrNull()
                        if (uid != null) {
                            uidTimes.getOrPut(uid) { UidTimes() }.fgTimerMs +=
                                parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        }
                    }

                    // Foreground service timer: 9,<uid>,l,fgs,<timeMs>,<count>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "fgs" -> {
                        val uid = parts[1].toIntOrNull()
                        if (uid != null) {
                            uidTimes.getOrPut(uid) { UidTimes() }.fgsTimerMs +=
                                parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        }
                    }

                    // Process state times:
                    // 9,<uid>,l,st,<top>,<fgService>,<foreground>,<background>,
                    //              <topSleeping>,<heavyWeight>,<cached>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "st" -> {
                        parseStateTimes(parts, uidTimes)
                    }

                    // Signal strength: 9,0,l,sgt,<time0>,<time1>,<time2>,<time3>,<time4>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "sgt" -> {
                        parseSignalStrength(parts, signalStrength)
                    }

                    // WiFi signal: 9,0,l,wsgt,<time0>,<time1>,<time2>,<time3>,<time4>
                    // (the checkin tag is "wsgt"; "wsg" never matched anything)
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "wsgt" -> {
                        parseWifiSignal(parts, wifiSignal)
                    }

                    // Bluetooth controller: 9,0,l,ble,<idle>,<rx>,<tx>,<power>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "ble" -> {
                        bluetooth = parseBluetooth(parts)
                    }

                    // Battery discharge:
                    // 9,0,l,dc,<low>,<high>,<screenOnAmount>,<screenOffAmount>,...
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "dc" -> {
                        parts.getOrNull(6)?.toFloatOrNull()?.let { screenOnDischarge = it }
                        parts.getOrNull(7)?.toFloatOrNull()?.let { screenOffDischarge = it }
                    }

                    // Battery core: 9,0,l,bt,startCount,battRealtime,battUptime,...
                    parts[2] == "l" && parts[3] == "bt" -> {
                        batteryRealtimeMs = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                    }

                    // Misc: 9,0,l,m,screenOnTime,phoneOnTime,... (also carries Doze totals)
                    parts[2] == "l" && parts[3] == "m" -> {
                        screenOnTimeMs = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        doze = parseDoze(parts) ?: doze
                    }

                    // Power summary: 9,0,l,pws,capacity,computed,minDrained,maxDrained
                    parts[2] == "l" && parts[3] == "pws" -> {
                        // Capacity is formatted as mAh and may carry decimals.
                        estCapacity = parts.getOrNull(4)?.toDoubleOrNull()?.roundToLong()?.toInt() ?: 0
                    }

                    // Process stats: 9,<uid>,l,pr,<process>,<user>,<sys>,<fg>,<starts>
                    parts.getOrNull(2) == "l" && parts.getOrNull(3) == "pr" -> {
                        parseProcess(parts, uidToPkg, processStats)
                    }
                }
            } catch (_: Exception) {
                // Skip malformed lines
            }
        }

        // The lists below are rendered with LazyColumn item keys, and Compose throws when a
        // key repeats. A checkin dump can legitimately repeat a (uid, tag) pair - one line
        // per wakelock type, per user profile - so merge duplicates instead of emitting them.
        val mergedWakelocks = wakelocks
            .mergeBy({ it.uid to it.tag }) { a, b ->
                a.copy(
                    count = a.count + b.count,
                    totalTimeMs = a.totalTimeMs + b.totalTimeMs,
                    maxTimeMs = maxOf(a.maxTimeMs, b.maxTimeMs),
                    backgroundTimeMs = a.backgroundTimeMs + b.backgroundTimeMs,
                    backgroundCount = a.backgroundCount + b.backgroundCount
                )
            }
            .sortedByDescending { it.totalTimeMs }

        val mergedNetwork = network
            .mergeBy({ it.uid }) { a, b ->
                a.copy(
                    mobileRxBytes = a.mobileRxBytes + b.mobileRxBytes,
                    mobileTxBytes = a.mobileTxBytes + b.mobileTxBytes,
                    wifiRxBytes = a.wifiRxBytes + b.wifiRxBytes,
                    wifiTxBytes = a.wifiTxBytes + b.wifiTxBytes,
                    mobileActiveTimeMs = a.mobileActiveTimeMs + b.mobileActiveTimeMs,
                    mobileActiveCount = a.mobileActiveCount + b.mobileActiveCount
                )
            }
            .sortedByDescending {
                it.mobileRxBytes + it.mobileTxBytes + it.wifiRxBytes + it.wifiTxBytes
            }

        val mergedSensors = sensors
            .mergeBy({ it.uid to it.sensorHandle }) { a, b ->
                a.copy(count = a.count + b.count, totalTimeMs = a.totalTimeMs + b.totalTimeMs)
            }
            .sortedByDescending { it.totalTimeMs }

        // Package names are resolved here rather than while parsing: the uid -> package
        // lines are not guaranteed to precede the rows that reference them, and a row read
        // before the mapping arrived would keep a "uid:NNNNN" placeholder for good.
        fun named(uid: Int) = uidToPkg[uid] ?: "uid:$uid"

        return FullSnapshot(
            capturedAt = System.currentTimeMillis(),
            batteryRealtimeMs = batteryRealtimeMs,
            screenOnTimeMs = screenOnTimeMs,
            screenOffDischargePercent = screenOffDischarge,
            screenOnDischargePercent = screenOnDischarge,
            estimatedCapacityMah = estCapacity,
            mappedPackages = uidToPkg.size,
            apps = joinPerUidDetail(
                appStats.values, mergedWakelocks, mergedNetwork, mergedSensors, uidTimes
            ).map { it.copy(packageName = named(it.uid)) },
            wakelocks = mergedWakelocks.map { it.copy(packageName = named(it.uid)) },
            kernelWakelocks = kernelWakelocks
                .mergeBy({ it.name }) { a, b ->
                    a.copy(count = a.count + b.count, totalTimeMs = a.totalTimeMs + b.totalTimeMs)
                }
                .sortedByDescending { it.totalTimeMs },
            alarms = alarms
                .mergeBy({ it.uid to it.tag }) { a, b ->
                    a.copy(
                        count = a.count + b.count,
                        wakeups = a.wakeups + b.wakeups,
                        totalTimeMs = a.totalTimeMs + b.totalTimeMs
                    )
                }
                .sortedByDescending { it.count }
                .map { it.copy(packageName = named(it.uid)) },
            jobs = jobs
                .mergeBy({ it.uid to it.jobName }) { a, b ->
                    a.copy(count = a.count + b.count, totalTimeMs = a.totalTimeMs + b.totalTimeMs)
                }
                .sortedByDescending { it.totalTimeMs }
                .map { it.copy(packageName = named(it.uid)) },
            syncs = syncs
                .mergeBy({ it.uid to it.authority }) { a, b ->
                    a.copy(count = a.count + b.count, totalTimeMs = a.totalTimeMs + b.totalTimeMs)
                }
                .sortedByDescending { it.totalTimeMs }
                .map { it.copy(packageName = named(it.uid)) },
            network = mergedNetwork.map { it.copy(packageName = named(it.uid)) },
            sensors = mergedSensors.map { it.copy(packageName = named(it.uid)) },
            signalStrength = signalStrength,
            wifiSignal = wifiSignal,
            bluetooth = bluetooth,
            doze = doze,
            cpuFrequency = cpuFreq,
            processStats = processStats
                .mergeBy({ it.uid to it.processName }) { a, b ->
                    a.copy(
                        userTimeMs = a.userTimeMs + b.userTimeMs,
                        systemTimeMs = a.systemTimeMs + b.systemTimeMs,
                        foregroundTimeMs = a.foregroundTimeMs + b.foregroundTimeMs,
                        starts = a.starts + b.starts
                    )
                }
                .sortedByDescending { it.userTimeMs + it.systemTimeMs }
                .map { it.copy(packageName = named(it.uid)) }
        )
    }

    /**
     * Folds the per-uid detail back onto the app rows.
     *
     * The checkin dump reports an app's wakelocks, network counters and sensor usage on
     * their own lines, which this parser collects into separate top-level lists. Nothing
     * ever joined them back onto [AppPowerStats], so every one of those fields on the Apps
     * tab rendered its data-class default of zero even though the numbers were right there.
     */
    private fun joinPerUidDetail(
        apps: Collection<AppPowerStats>,
        wakelocks: List<WakelockStats>,
        network: List<NetworkStats>,
        sensors: List<SensorStats>,
        uidTimes: Map<Int, UidTimes>
    ): List<AppPowerStats> {
        if (apps.isEmpty()) return emptyList()

        val wakelockMsByUid = wakelocks.groupingBy { it.uid }.fold(0L) { acc, w -> acc + w.totalTimeMs }
        val networkByUid = network.associateBy { it.uid }
        val gpsMsByUid = sensors.asSequence()
            .filter { it.sensorHandle == GPS_SENSOR_HANDLE }
            .groupingBy { it.uid }.fold(0L) { acc, s -> acc + s.totalTimeMs }
        val sensorMsByUid = sensors.asSequence()
            .filter { it.sensorHandle != GPS_SENSOR_HANDLE }
            .groupingBy { it.uid }.fold(0L) { acc, s -> acc + s.totalTimeMs }

        return apps.map { app ->
            val net = networkByUid[app.uid]
            val times = uidTimes[app.uid]
            app.copy(
                cpuTimeMs = times?.cpuMs ?: 0L,
                wakeLockTimeMs = wakelockMsByUid[app.uid] ?: 0L,
                gpsTimeMs = gpsMsByUid[app.uid] ?: 0L,
                sensorTimeMs = sensorMsByUid[app.uid] ?: 0L,
                topTimeMs = times?.topMs ?: 0L,
                // The process-state line is the complete picture; the standalone fg/fgs
                // timers are only a fallback for dumps that omit it.
                foregroundTimeMs = times?.let { if (it.hasState) it.fgMs else it.fgTimerMs } ?: 0L,
                foregroundServiceTimeMs =
                    times?.let { if (it.hasState) it.fgsMs else it.fgsTimerMs } ?: 0L,
                backgroundTimeMs = times?.bgMs ?: 0L,
                cachedTimeMs = times?.cachedMs ?: 0L,
                mobileRxBytes = net?.mobileRxBytes ?: 0L,
                mobileTxBytes = net?.mobileTxBytes ?: 0L,
                wifiRxBytes = net?.wifiRxBytes ?: 0L,
                wifiTxBytes = net?.wifiTxBytes ?: 0L
            )
        }.sortedByDescending { it.powerMah }
    }

    /** Per-uid timers gathered from the cpu / fg / fgs / st checkin lines. */
    private class UidTimes {
        var cpuMs = 0L
        var fgTimerMs = 0L
        var fgsTimerMs = 0L
        var topMs = 0L
        var fgsMs = 0L
        var fgMs = 0L
        var bgMs = 0L
        var cachedMs = 0L
        var hasState = false
    }

    /**
     * Process-state times. The column count has grown across Android releases, so only
     * "top" (the first column) is read unconditionally; the rest need the 7-state layout
     * that current releases emit.
     */
    private fun parseStateTimes(parts: List<String>, uidTimes: MutableMap<Int, UidTimes>) {
        val uid = parts[1].toIntOrNull() ?: return
        val times = uidTimes.getOrPut(uid) { UidTimes() }
        times.topMs += parts.getOrNull(4)?.toLongOrNull() ?: 0L
        if (parts.size < 11) return
        times.hasState = true
        times.fgsMs += parts.getOrNull(5)?.toLongOrNull() ?: 0L
        times.fgMs += parts.getOrNull(6)?.toLongOrNull() ?: 0L
        times.bgMs += parts.getOrNull(7)?.toLongOrNull() ?: 0L
        times.cachedMs += parts.getOrNull(10)?.toLongOrNull() ?: 0L
    }

    /** Collapses entries that share a key, preserving first-seen order. */
    private inline fun <T, K> List<T>.mergeBy(key: (T) -> K, combine: (T, T) -> T): List<T> {
        if (size < 2) return this
        val merged = LinkedHashMap<K, T>(size)
        for (item in this) {
            val k = key(item)
            val existing = merged[k]
            merged[k] = if (existing == null) item else combine(existing, item)
        }
        return merged.values.toList()
    }

    private fun parsePowerUseItem(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        appStats: MutableMap<Int, AppPowerStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val type = parts.getOrNull(4) ?: return
        val mah = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0

        // 9,<uid>,l,pwi,<label>,<mAh>,<shouldHide>,<screenMah>,<smearMah>
        // Only rows labelled "uid" are per-app; the rest are device-wide component totals
        // (scrn, cpu, cell, gnss, ...) reported against uid 0.
        if (type == "uid") {
            val pkg = uidToPkg[uid] ?: "uid:$uid"
            // Column 7 is the screen share, and it checks out: screen plus the process-state
            // figures sums to the total. Column 8 does not - on a real device it came back
            // larger than the app's own total - so it is left alone until it can be
            // explained rather than surfaced as a number nobody can act on.
            val screen = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0
            val existing = appStats[uid] ?: AppPowerStats(uid = uid, packageName = pkg, powerMah = 0.0)
            appStats[uid] = existing.copy(
                powerMah = existing.powerMah + mah,
                screenPowerMah = existing.screenPowerMah + screen
            )
        }
    }

    private fun parseWakelock(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        wakelocks: MutableList<WakelockStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val tag = parts.getOrNull(4) ?: return

        // Find partial and background-partial blocks in a version-independent way.
        // Each block is "<timeMs>,<marker>,<count>,...", so the earliest a marker can appear
        // is index 6 (4 = tag, 5 = time). Starting at 4 would match a wakelock *named* "p".
        val pIndex = parts.indexOfFrom(6) { it == "p" }
        val bpIndex = parts.indexOfFrom(6) { it == "bp" }

        val partialTimeMs = if (pIndex > 0) {
            parts.getOrNull(pIndex - 1)?.toLongOrNull() ?: 0L
        } else 0L
        val partialCount = if (pIndex >= 0) {
            parts.getOrNull(pIndex + 1)?.toIntOrNull() ?: 0
        } else 0

        val bgPartialTimeMs = if (bpIndex > 0) {
            parts.getOrNull(bpIndex - 1)?.toLongOrNull() ?: 0L
        } else 0L
        val bgPartialCount = if (bpIndex >= 0) {
            parts.getOrNull(bpIndex + 1)?.toIntOrNull() ?: 0
        } else 0

        wakelocks.add(
            WakelockStats(
                uid = uid,
                packageName = pkg,
                tag = tag,
                type = WakelockType.PARTIAL,
                count = partialCount,
                totalTimeMs = partialTimeMs,
                backgroundTimeMs = bgPartialTimeMs,
                backgroundCount = bgPartialCount
            )
        )
    }

    private fun parseKernelWakelock(
        parts: List<String>,
        kernelWakelocks: MutableList<KernelWakelockStats>
    ) {
        val name = parts.getOrNull(4) ?: return
        val timeMs = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val count = parts.getOrNull(6)?.toIntOrNull() ?: 0

        kernelWakelocks.add(
            KernelWakelockStats(
                name = name,
                count = count,
                totalTimeMs = timeMs
            )
        )
    }

    private fun parseAlarm(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        alarms: MutableList<AlarmStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val tag = parts.getOrNull(4) ?: return
        val count = parts.getOrNull(5)?.toIntOrNull() ?: 0
        val timeMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val wakeups = parts.getOrNull(7)?.toIntOrNull() ?: 0

        alarms.add(
            AlarmStats(
                uid = uid,
                packageName = pkg,
                tag = tag,
                count = count,
                totalTimeMs = timeMs,
                wakeups = wakeups
            )
        )
    }

    private fun parseJob(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        jobs: MutableList<JobStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val jobName = parts.getOrNull(4) ?: return
        val count = parts.getOrNull(5)?.toIntOrNull() ?: 0
        val timeMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L

        jobs.add(
            JobStats(
                uid = uid,
                packageName = pkg,
                jobName = jobName,
                count = count,
                totalTimeMs = timeMs
            )
        )
    }

    private fun parseSync(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        syncs: MutableList<SyncStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val authority = parts.getOrNull(4) ?: return
        val count = parts.getOrNull(5)?.toIntOrNull() ?: 0
        val timeMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L

        syncs.add(
            SyncStats(
                uid = uid,
                packageName = pkg,
                authority = authority,
                count = count,
                totalTimeMs = timeMs
            )
        )
    }

    private fun parseNetwork(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        network: MutableList<NetworkStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"

        val mobileRx = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val mobileTx = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val wifiRx = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val wifiTx = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        val mobileActiveTime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
        val mobileActiveCount = parts.getOrNull(13)?.toIntOrNull() ?: 0

        network.add(
            NetworkStats(
                uid = uid,
                packageName = pkg,
                mobileRxBytes = mobileRx,
                mobileTxBytes = mobileTx,
                wifiRxBytes = wifiRx,
                wifiTxBytes = wifiTx,
                mobileActiveTimeMs = mobileActiveTime,
                mobileActiveCount = mobileActiveCount
            )
        )
    }

    private fun parseSensor(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        sensors: MutableList<SensorStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val handle = parts.getOrNull(4)?.toIntOrNull() ?: return
        val timeMs = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val count = parts.getOrNull(6)?.toIntOrNull() ?: 0

        sensors.add(
            SensorStats(
                uid = uid,
                packageName = pkg,
                sensorHandle = handle,
                sensorName = getSensorName(handle),
                count = count,
                totalTimeMs = timeMs
            )
        )
    }

    private fun parseSignalStrength(
        parts: List<String>,
        signalStrength: MutableList<SignalStrengthStats>
    ) {
        val times = (4..8).mapNotNull { parts.getOrNull(it)?.toLongOrNull() }
        if (times.size < 5) return
        val total = times.sum().toFloat().coerceAtLeast(1f)
        times.forEachIndexed { level, time ->
            signalStrength.add(
                SignalStrengthStats(
                    level = level,
                    durationMs = time,
                    percentOfTotal = time / total
                )
            )
        }
    }

    private fun parseWifiSignal(
        parts: List<String>,
        wifiSignal: MutableList<WifiSignalStats>
    ) {
        val times = (4..8).mapNotNull { parts.getOrNull(it)?.toLongOrNull() }
        if (times.size < 5) return
        val total = times.sum().toFloat().coerceAtLeast(1f)
        times.forEachIndexed { level, time ->
            wifiSignal.add(
                WifiSignalStats(
                    level = level,
                    durationMs = time,
                    percentOfTotal = time / total
                )
            )
        }
    }

    private fun parseBluetooth(parts: List<String>): BluetoothStats? {
        val idle = parts.getOrNull(4)?.toLongOrNull() ?: return null
        val rx = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val tx = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val power = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0
        return BluetoothStats(
            idleTimeMs = idle,
            rxTimeMs = rx,
            txTimeMs = tx,
            powerMah = power
        )
    }

    /**
     * Doze totals ride along on the MISC_DATA (`m`) line; there is no dedicated checkin tag
     * for them. Layout, counting the leading `9,<uid>,l,m` as indices 0..3:
     *
     * ```
     *  13 deviceIdleModeFullTime      (deep Doze)   17 mobileRadioActiveCount
     *  14 deviceIdleModeFullCount                   18 mobileRadioActiveUnknownTime
     *  15 deviceIdlingTime            (maintenance) 19 deviceLightIdleModeTime
     *  16 deviceIdlingCount                         20 deviceLightIdleModeCount
     * ```
     */
    private fun parseDoze(parts: List<String>): DozeStats? {
        if (parts.size <= 20) return null

        val deepTime = parts.getOrNull(13)?.toLongOrNull() ?: return null
        val deepCount = parts.getOrNull(14)?.toIntOrNull() ?: 0
        val maintTime = parts.getOrNull(15)?.toLongOrNull() ?: 0L
        val maintCount = parts.getOrNull(16)?.toIntOrNull() ?: 0
        val lightTime = parts.getOrNull(19)?.toLongOrNull() ?: 0L
        val lightCount = parts.getOrNull(20)?.toIntOrNull() ?: 0

        if (deepTime == 0L && lightTime == 0L && deepCount == 0 && lightCount == 0) return null

        return DozeStats(
            idleModeTimeMs = deepTime + lightTime,
            idleModeCount = deepCount + lightCount,
            deepIdleTimeMs = deepTime,
            deepIdleCount = deepCount,
            lightIdleTimeMs = lightTime,
            lightIdleCount = lightCount,
            maintenanceTimeMs = maintTime,
            maintenanceCount = maintCount
        )
    }

    /**
     * Splits a checkin line on commas, honouring the double quotes that Android puts around
     * names which may themselves contain commas (wakelock tags, kernel wakelock names, ...).
     * A plain `split(',')` shifts every following column for those lines.
     */
    internal fun splitCheckinLine(line: String): List<String> {
        if ('"' !in line) return line.split(',')

        val fields = ArrayList<String>(16)
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
            i++
        }
        fields.add(field.toString())
        return fields
    }

    private fun parseProcess(
        parts: List<String>,
        uidToPkg: Map<Int, String>,
        processStats: MutableList<ProcessStats>
    ) {
        val uid = parts[1].toIntOrNull() ?: return
        val pkg = uidToPkg[uid] ?: "uid:$uid"
        val process = parts.getOrNull(4) ?: return
        val userMs = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val sysMs = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val fgMs = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        val starts = parts.getOrNull(8)?.toIntOrNull() ?: 0

        processStats.add(
            ProcessStats(
                uid = uid,
                packageName = pkg,
                processName = process,
                userTimeMs = userMs,
                systemTimeMs = sysMs,
                foregroundTimeMs = fgMs,
                starts = starts
            )
        )
    }

    /** batterystats reports GPS as a pseudo-sensor with this handle. */
    private const val GPS_SENSOR_HANDLE = -10000

    private fun getSensorName(handle: Int): String = when (handle) {
        GPS_SENSOR_HANDLE -> "GPS"
        else -> "Sensor #$handle"
    }

    private inline fun List<String>.indexOfFrom(start: Int, predicate: (String) -> Boolean): Int {
        for (i in start until size) if (predicate(this[i])) return i
        return -1
    }

    /**
     * Shell command that yields the input for [parseEstimatedPowerUse].
     *
     * The full `dumpsys batterystats` output is enormous, so the filtering is done on the
     * device: only the per-UID power lines come back.
     */
    const val POWER_USE_COMMAND =
        "dumpsys batterystats | grep -E '^[[:space:]]*UID ' | head -n 400"

    // "  UID u0a285: 219 fg: 99.9 (22m 19s 961ms) bg: 2.80 (15m 40s 873ms) cached: 42.3 (...)"
    private val UID_POWER_LINE =
        Regex("""^\s*UID\s+(\S+?):\s*([0-9.]+(?:[eE][+-]?\d+)?)\s*(.*)$""")

    // Each "<state>: <mAh>" pair, with the duration in brackets when the dump includes one.
    // "fgs" precedes "fg" so the longer name wins rather than relying on backtracking.
    private val UID_POWER_STATE =
        Regex("""\b(fgs|fg|bg|cached)\s*:\s*([0-9.]+(?:[eE][+-]?\d+)?)(?:\s*\(([^)]*)\))?""")

    private val USER_APP_UID = Regex("""^u(\d+)a(\d+)$""")

    private val DURATION_PART = Regex("""(\d+)\s*(ms|h|m|s)""")

    private fun stateLabel(state: String): String = when (state) {
        "fg" -> "Foreground"
        "fgs" -> "Foreground service"
        "bg" -> "Background"
        "cached" -> "Cached"
        else -> state
    }

    /**
     * Parses the per-UID rows of the `Estimated power use (mAh)` section into a
     * uid -> process-state breakdown.
     */
    fun parseEstimatedPowerUse(raw: String): Map<Int, List<UidPowerState>> {
        val result = LinkedHashMap<Int, List<UidPowerState>>()
        raw.lineSequence().forEach { line ->
            val match = UID_POWER_LINE.find(line) ?: return@forEach
            val uid = parseUidToken(match.groupValues[1]) ?: return@forEach
            val states = UID_POWER_STATE.findAll(match.groupValues[3])
                .map { entry ->
                    val state = entry.groupValues[1]
                    UidPowerState(
                        state = state,
                        label = stateLabel(state),
                        powerMah = entry.groupValues[2].toDoubleOrNull() ?: 0.0,
                        durationMs = parseHumanDuration(entry.groupValues[3])
                    )
                }
                .filter { it.powerMah > 0.0 || it.durationMs > 0L }
                .toList()
            if (states.isNotEmpty()) result[uid] = states
        }
        return result
    }

    /** Folds a [parseEstimatedPowerUse] result onto an already-parsed checkin snapshot. */
    fun applyPowerStates(
        snapshot: FullSnapshot,
        byUid: Map<Int, List<UidPowerState>>
    ): FullSnapshot {
        if (byUid.isEmpty()) return snapshot
        return snapshot.copy(
            apps = snapshot.apps.map { app ->
                byUid[app.uid]?.let { app.copy(powerByState = it) } ?: app
            }
        )
    }

    /** "u0a285" -> 10285, "1000" -> 1000. */
    private fun parseUidToken(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val match = USER_APP_UID.matchEntire(token) ?: return null
        val user = match.groupValues[1].toIntOrNull() ?: return null
        val appId = match.groupValues[2].toIntOrNull() ?: return null
        return user * 100_000 + 10_000 + appId
    }

    /** "4h 0m 31s 150ms" -> 14431150. Returns 0 for an empty or unrecognised string. */
    private fun parseHumanDuration(text: String): Long {
        if (text.isBlank()) return 0L
        var total = 0L
        DURATION_PART.findAll(text).forEach { part ->
            val value = part.groupValues[1].toLongOrNull() ?: return@forEach
            total += when (part.groupValues[2]) {
                "ms" -> value
                "s" -> value * 1_000L
                "m" -> value * 60_000L
                "h" -> value * 3_600_000L
                else -> 0L
            }
        }
        return total
    }

    data class DeviceIdleInfo(
        val currentState: String,
        val lightState: String,
        val deepEnabled: Boolean,
        val lightEnabled: Boolean,
        val screenOnTime: Long,
        val screenOffTime: Long,
        val whitelistedApps: List<String>,
        val tempWhitelistedApps: List<String>
    )

    fun parseDeviceIdle(raw: String): DeviceIdleInfo {
        var currentState = "UNKNOWN"
        var lightState = "UNKNOWN"
        var deepEnabled = true
        var lightEnabled = true
        var screenOnTime = 0L
        var screenOffTime = 0L
        val whitelisted = mutableListOf<String>()
        val tempWhitelisted = mutableListOf<String>()

        var inWhitelist = false
        var inTempWhitelist = false

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("mState=") -> currentState = trimmed.removePrefix("mState=")
                trimmed.startsWith("mLightState=") -> lightState = trimmed.removePrefix("mLightState=")
                trimmed.startsWith("mDeepEnabled=") -> deepEnabled = trimmed.contains("true")
                trimmed.startsWith("mLightEnabled=") -> lightEnabled = trimmed.contains("true")
                trimmed.startsWith("mScreenOnTime=") -> screenOnTime = trimmed.removePrefix("mScreenOnTime=").toLongOrNull() ?: 0L
                trimmed.startsWith("mScreenOffTime=") -> screenOffTime = trimmed.removePrefix("mScreenOffTime=").toLongOrNull() ?: 0L
                // Real dumps use several headings: "Whitelist system apps:",
                // "Whitelist user apps:", "Whitelist (except idle) system apps:", ...
                trimmed.startsWith("Whitelist") && trimmed.endsWith("apps:") -> {
                    inWhitelist = true
                    inTempWhitelist = false
                }
                trimmed.startsWith("Temp whitelist") -> {
                    inWhitelist = false
                    inTempWhitelist = true
                }
                trimmed.endsWith(":") && !trimmed.contains("=") -> {
                    // Any other section heading ends the current list.
                    inWhitelist = false
                    inTempWhitelist = false
                }
                trimmed.isEmpty() -> {
                    inWhitelist = false
                    inTempWhitelist = false
                }
                inWhitelist && trimmed.isNotBlank() -> whitelisted.add(trimmed)
                inTempWhitelist && trimmed.isNotBlank() -> tempWhitelisted.add(trimmed.substringBefore(":"))
            }
        }

        return DeviceIdleInfo(
            currentState = currentState,
            lightState = lightState,
            deepEnabled = deepEnabled,
            lightEnabled = lightEnabled,
            screenOnTime = screenOnTime,
            screenOffTime = screenOffTime,
            whitelistedApps = whitelisted,
            tempWhitelistedApps = tempWhitelisted
        )
    }

    data class PowerManagerInfo(
        val screenBrightness: Int,
        val isScreenOn: Boolean,
        val holdingWakeLocks: List<String>,
        val suspendBlockers: List<String>,
        val batteryLevel: Int,
        val batteryStatus: String,
        val lowPowerMode: Boolean,
        val deviceIdleMode: String
    )

    fun parsePowerManager(raw: String): PowerManagerInfo {
        var brightness = 0
        var screenOn = false
        var batteryLevel = 0
        var batteryStatus = "UNKNOWN"
        var lowPowerMode = false
        var deviceIdleMode = "UNKNOWN"
        val wakeLocks = mutableListOf<String>()
        val suspendBlockers = mutableListOf<String>()

        var inWakeLocks = false
        var inBlockers = false

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("mScreenBrightnessSetting=") -> {
                    brightness = trimmed.substringAfter("=").toIntOrNull() ?: 0
                }
                trimmed.startsWith("Display Power: state=") -> {
                    screenOn = trimmed.substringAfter("=").startsWith("ON")
                }
                // Fallback for dumps that do not include the Display Power line.
                trimmed.startsWith("mWakefulness=") -> {
                    screenOn = trimmed.substringAfter("=").equals("Awake", ignoreCase = true)
                }
                trimmed.startsWith("mBatteryLevel=") -> {
                    batteryLevel = trimmed.substringAfter("=").toIntOrNull() ?: 0
                }
                trimmed.startsWith("mBatteryStatus=") -> {
                    batteryStatus = trimmed.substringAfter("=")
                }
                // dumpsys power reports the plug state rather than a status string.
                trimmed.startsWith("mIsPowered=") -> {
                    if (batteryStatus == "UNKNOWN") {
                        batteryStatus = if (trimmed.contains("true")) "Charging" else "Discharging"
                    }
                }
                trimmed.startsWith("mLowPowerModeEnabled=") ||
                    trimmed.startsWith("mBatterySaverEnabled=") -> {
                    lowPowerMode = trimmed.contains("true")
                }
                trimmed.startsWith("mDeviceIdleMode=") -> {
                    deviceIdleMode = trimmed.substringAfter("=")
                }
                // Headings carry a size suffix: "Wake Locks: size=3".
                trimmed.startsWith("Wake Locks:") -> {
                    inWakeLocks = true
                    inBlockers = false
                }
                trimmed.startsWith("Suspend Blockers:") -> {
                    inWakeLocks = false
                    inBlockers = true
                }
                trimmed.isEmpty() -> {
                    inWakeLocks = false
                    inBlockers = false
                }
                inWakeLocks && trimmed.isNotBlank() -> wakeLocks.add(trimmed)
                inBlockers && trimmed.isNotBlank() -> suspendBlockers.add(trimmed)
            }
        }

        return PowerManagerInfo(
            screenBrightness = brightness,
            isScreenOn = screenOn,
            holdingWakeLocks = wakeLocks,
            suspendBlockers = suspendBlockers,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus,
            lowPowerMode = lowPowerMode,
            deviceIdleMode = deviceIdleMode
        )
    }
}