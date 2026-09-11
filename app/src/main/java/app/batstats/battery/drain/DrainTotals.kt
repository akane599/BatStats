package app.batstats.battery.drain

/**
 * Accumulated time and charge per bucket, built up one segment at a time.
 *
 * The buckets are defined so they reconcile by construction:
 * `screenOn + screenOff` is the tracked time, `active + idle` is the screen-on time, and
 * `deepSleep + awake` is the screen-off time. Nothing is credited to more than one pair, so
 * a share can never exceed its whole.
 */
internal class DrainTotals(
    var screenOnTimeMs: Long = 0L,
    var screenOffTimeMs: Long = 0L,
    var activeTimeMs: Long = 0L,
    var idleTimeMs: Long = 0L,
    var deepSleepTimeMs: Long = 0L,
    var awakeTimeMs: Long = 0L,
    var screenOnDrainMah: Double = 0.0,
    var screenOffDrainMah: Double = 0.0,
    var activeDrainMah: Double = 0.0,
    var idleDrainMah: Double = 0.0,
    var deepSleepDrainMah: Double = 0.0,
    var awakeDrainMah: Double = 0.0
) {
    fun copy() = DrainTotals(
        screenOnTimeMs, screenOffTimeMs, activeTimeMs, idleTimeMs,
        deepSleepTimeMs, awakeTimeMs, screenOnDrainMah, screenOffDrainMah,
        activeDrainMah, idleDrainMah, deepSleepDrainMah, awakeDrainMah
    )

    /**
     * Credits one segment.
     *
     * [sleptMs] is how long the CPU was actually suspended during it, so a screen-off
     * stretch splits between deep sleep and awake by measurement rather than by guesswork,
     * and its drain splits along the same line.
     */
    fun credit(
        screenOn: Boolean,
        active: Boolean,
        elapsedMs: Long,
        sleptMs: Long,
        drainMah: Double
    ) {
        if (elapsedMs <= 0L) return
        val drain = if (drainMah.isFinite() && drainMah > 0.0) drainMah else 0.0

        if (screenOn) {
            screenOnTimeMs += elapsedMs
            screenOnDrainMah += drain
            if (active) {
                activeTimeMs += elapsedMs
                activeDrainMah += drain
            } else {
                idleTimeMs += elapsedMs
                idleDrainMah += drain
            }
            return
        }

        screenOffTimeMs += elapsedMs
        screenOffDrainMah += drain
        val slept = sleptMs.coerceIn(0L, elapsedMs)
        val awake = elapsedMs - slept
        deepSleepTimeMs += slept
        awakeTimeMs += awake
        val sleptShare = slept.toDouble() / elapsedMs
        deepSleepDrainMah += drain * sleptShare
        awakeDrainMah += drain * (1.0 - sleptShare)
    }
}
