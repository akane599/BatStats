package app.batstats.battery.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One shared copy of `dumpsys batterystats --checkin`.
 *
 * Two collectors ran that command on their own schedules - the background per-app poller
 * and the Detailed Stats screen - so on a phone with Shizuku the same ~250 KB dump was
 * produced, piped across a binder and parsed twice over. That is real battery cost, in a
 * battery app.
 *
 * A dump that is only seconds old is indistinguishable from a fresh one: batterystats
 * counters are cumulative since the last charge and move slowly. So a recent result is
 * handed straight back, and because the lock is held across the command itself, callers
 * that arrive together wait for the one dump instead of each starting another.
 */
class CheckinSource(
    private val shellRunner: ShellRunner,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val COMMAND = "dumpsys batterystats --checkin"

        /** Short enough that a pull-to-refresh still feels live. */
        const val DEFAULT_MAX_AGE_MS = 15_000L
    }

    private val lock = Mutex()
    private var cached: ShellRunner.Outcome.Success? = null
    private var cachedAt = 0L

    suspend fun get(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): ShellRunner.Outcome = lock.withLock {
        cached?.let { if (isFresh(cachedAt, now(), maxAgeMs)) return@withLock it }

        val outcome = shellRunner.exec(COMMAND)
        if (outcome is ShellRunner.Outcome.Success) {
            cached = outcome
            cachedAt = now()
        } else {
            // Don't keep serving an old dump once the backend has started failing; the
            // screen should say so rather than show numbers that stopped updating.
            cached = null
        }
        outcome
    }

    /** Drops the cached dump - after a `--reset`, or when a collector stops. */
    suspend fun invalidate() = lock.withLock {
        cached = null
        cachedAt = 0L
    }
}

/**
 * Whether a dump taken at [takenAt] is still usable at [now]. A cache timestamped in the
 * future - a clock correction between the two reads - counts as stale rather than as
 * permanently fresh.
 */
internal fun isFresh(takenAt: Long, now: Long, maxAgeMs: Long): Boolean {
    val age = now - takenAt
    return age in 0..maxAgeMs
}
