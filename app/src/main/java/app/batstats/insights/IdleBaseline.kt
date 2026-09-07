package app.batstats.insights

import kotlin.math.roundToInt

/**
 * Learns what this device draws when nothing much is asking for anything.
 *
 * Attributing drain to the foreground app means subtracting a baseline, and that baseline
 * used to be the constant 80 mA screen-on / 20 mA screen-off. That is a guess about one
 * particular phone: a tablet idles well above it, so every app looked innocent, while an
 * efficient phone idles below it, so every app looked guilty. Here the floor is measured
 * instead - the quietest readings the device actually produces in that screen state.
 *
 * A low percentile rather than the outright minimum, because a single 0 mA reading from a
 * driver hiccup would otherwise define the floor forever.
 */
internal class IdleBaseline(
    private val windowSize: Int = 64,
    private val minReadings: Int = 16,
    private val percentile: Double = 0.10
) {
    private val readings = ArrayDeque<Double>()

    fun observe(milliAmps: Double) {
        if (milliAmps < 0.0 || !milliAmps.isFinite()) return
        readings.addLast(milliAmps)
        while (readings.size > windowSize) readings.removeFirst()
    }

    /** Null until enough has been seen to say anything - better than a number we invented. */
    fun baselineMilliAmps(): Double? {
        if (readings.size < minReadings) return null
        val sorted = readings.sorted()
        return sorted[((sorted.size - 1) * percentile).roundToInt()]
    }
}
