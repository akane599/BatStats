package app.batstats.battery.shizuku

import app.batstats.battery.util.BatteryStatsParser

/**
 * Small parser for "dumpsys batterystats --checkin", for the per-app energy poller.
 *
 * Deliberately lighter than [BatteryStatsParser.parseCheckin]: this runs on a background
 * schedule and only needs the per-app power figures, not wakelocks, alarms, sensors and the
 * rest.
 *
 * Returns cumulative mAh since the last full charge, keyed by uid. Uids rather than package
 * names because the dump's own uid -> package map is only as complete as the identity that
 * produced it; the caller resolves the leftovers through PackageManager.
 */
object CheckinParser {

    data class Snapshot(
        val perUidMah: Map<Int, Double>,
        val uidToPackage: Map<Int, String>,
        val batteryRealtimeMs: Long? = null
    )

    fun parse(lines: Sequence<String>): Snapshot {
        val uidToPkg = mutableMapOf<Int, String>()
        val energyByUid = mutableMapOf<Int, Double>()
        var batteryRealtimeMs: Long? = null

        lines.forEach { line ->
            // Quote-aware: names may contain commas.
            val p = BatteryStatsParser.splitCheckinLine(line)
            if (p.size < 4) return@forEach
            if (p[2] == "l" && p[3] == "bt") batteryRealtimeMs = p.getOrNull(5)?.toLongOrNull()

            // uid map: 9,0,i,uid,1000,android
            if (p[2] == "i" && p[3] == "uid" && p.size >= 6) {
                val uid = p[4].toIntOrNull() ?: return@forEach
                uidToPkg[uid] = p[5]
            }

            // Power use item: 9,<uid>,l,pwi,<label>,<mAh>,...
            if (p[2] == "l" && p[3] == "pwi" && p.size >= 6) {
                // Only rows labelled "uid" are per-app. The rest are device-wide component
                // totals - screen, cpu, cell, gnss - reported against uid 0. Without this
                // check every one of them was folded into whichever package owns uid 0, so
                // one app appeared to be responsible for the screen and the radio.
                if (p[4] != "uid") return@forEach
                val uid = p[1].toIntOrNull() ?: return@forEach
                val mah = p[5].toDoubleOrNull() ?: return@forEach
                if (!mah.isFinite() || mah < 0) return@forEach
                energyByUid[uid] = (energyByUid[uid] ?: 0.0) + mah
            }
        }
        return Snapshot(energyByUid, uidToPkg, batteryRealtimeMs)
    }
}

/** Never attribute a UID's historical total to the instant it first becomes visible. */
internal fun energyDeltas(previous: CheckinParser.Snapshot?, current: CheckinParser.Snapshot): Map<Int, Double> {
    previous ?: return emptyMap()
    if (previous.batteryRealtimeMs != null && current.batteryRealtimeMs != null &&
        current.batteryRealtimeMs < previous.batteryRealtimeMs) return emptyMap()
    return current.perUidMah.mapNotNull { (uid, value) ->
        val old = previous.perUidMah[uid] ?: return@mapNotNull null
        val delta = value - old
        if (delta.isFinite() && delta > 0.0001) uid to delta else null
    }.toMap()
}
