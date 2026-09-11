package app.batstats.battery.drain

/** Measured discharge time and charge. CPU sleep divides time, never energy. */
internal data class DrainTotals(
    var screenOnTimeMs: Long = 0L,
    var screenOffTimeMs: Long = 0L,
    var deepSleepTimeMs: Long = 0L,
    var awakeTimeMs: Long = 0L,
    var screenOnDrainMah: Double? = 0.0,
    var screenOffDrainMah: Double? = 0.0
) {
    fun credit(screenOn: Boolean, elapsedMs: Long, sleptMs: Long, drainMah: Double?) {
        if (elapsedMs <= 0L) return
        // A partial sum divided by the full duration would understate the rate.
        val drain = drainMah?.takeIf { it.isFinite() && it >= 0.0 }
        if (screenOn) {
            screenOnTimeMs += elapsedMs
            screenOnDrainMah = addMeasured(screenOnDrainMah, drain)
        } else {
            screenOffTimeMs += elapsedMs
            screenOffDrainMah = addMeasured(screenOffDrainMah, drain)
            val slept = sleptMs.coerceIn(0L, elapsedMs)
            deepSleepTimeMs += slept
            awakeTimeMs += elapsedMs - slept
        }
    }

    private fun addMeasured(total: Double?, delta: Double?): Double? =
        if (total != null && delta != null) total + delta else null
}

/** A zero counter at nonempty/unknown level is a reset or unsupported OEM reading. */
internal fun chargeCounterMah(chargeUah: Long?, levelPercent: Int): Double? =
    chargeUah?.takeIf { it > 0L || (it == 0L && levelPercent == 0) }?.div(1000.0)

/** Counter increases while unplugged indicate recalibration, not zero drain. */
internal fun measuredDrainMah(start: Double?, end: Double?): Double? {
    if (start == null || end == null || !start.isFinite() || !end.isFinite() ||
        start < 0.0 || end < 0.0 || end > start) return null
    return start - end
}

internal data class DrainReading(
    val elapsedMs: Long,
    val sleptMs: Long,
    val screenOn: Boolean,
    val powered: Boolean?,
    val chargeMah: Double?
)

/** One owner serializes calls. Boundaries and polls use the same accounting. */
internal class DrainLedger {
    var totals = DrainTotals()
        private set
    var sessionElapsedMs: Long = 0L
        private set
    private var startedElapsedMs: Long? = null
    private var open: DrainReading? = null

    fun reset(reading: DrainReading, running: Boolean) {
        totals = DrainTotals()
        sessionElapsedMs = 0L
        startedElapsedMs = if (running) reading.elapsedMs else null
        open = if (running) reading else null
    }

    fun advance(reading: DrainReading) {
        val start = startedElapsedMs ?: return
        credit(reading)
        sessionElapsedMs = (reading.elapsedMs - start).coerceAtLeast(0L)
        open = reading
    }

    fun stop(reading: DrainReading) {
        advance(reading)
        startedElapsedMs = null
        open = null
    }

    private fun credit(reading: DrainReading) {
        val previous = open ?: return
        // Powered and unknown-power stretches cannot establish battery discharge.
        if (previous.powered != false) return
        totals.credit(
            screenOn = previous.screenOn,
            elapsedMs = reading.elapsedMs - previous.elapsedMs,
            sleptMs = reading.sleptMs - previous.sleptMs,
            drainMah = measuredDrainMah(previous.chargeMah, reading.chargeMah)
        )
    }
}
