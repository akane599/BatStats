package app.batstats.battery.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: BatterySample): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSamples(samples: List<BatterySample>)

    @Query("SELECT COUNT(*) FROM battery_samples")
    fun sampleCount(): Flow<Long>

    @Query("SELECT timestamp FROM battery_samples WHERE timestamp IN (:timestamps)")
    suspend fun existingTimestamps(timestamps: List<Long>): List<Long>

    @Query("SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun exportPage(from: Long, to: Long, afterId: Long, limit: Int): List<BatterySample>

    @Query("SELECT * FROM battery_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastSample(): BatterySample?

    @Query("SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun samplesBetween(from: Long, to: Long): Flow<List<BatterySample>>

    @Query("SELECT MIN(timestamp) AS timestamp, AVG(currentNowUa) / 1000.0 AS currentMa FROM battery_samples WHERE timestamp BETWEEN :from AND :to AND currentNowUa IS NOT NULL GROUP BY timestamp / :bucketMs ORDER BY timestamp")
    fun currentChart(from: Long, to: Long, bucketMs: Long): Flow<List<BatteryCurrentPoint>>

    @Query("SELECT MIN(timestamp) AS timestamp, AVG(currentNowUa) / 1000.0 AS currentMa, AVG(voltageMv) AS voltageMv, AVG(temperatureDeciC) / 10.0 AS tempC FROM battery_samples WHERE timestamp BETWEEN :from AND :to GROUP BY timestamp / :bucketMs ORDER BY timestamp")
    fun sessionChart(from: Long, to: Long, bucketMs: Long): Flow<List<BatteryChartPoint>>

    @Query("SELECT AVG(currentNowUa) FROM battery_samples WHERE timestamp BETWEEN :from AND :to")
    suspend fun averageCurrent(from: Long, to: Long): Double?

    @Query("SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to AND chargeCounterUah IS NOT NULL ORDER BY timestamp ASC LIMIT 1")
    suspend fun firstCounter(from: Long, to: Long): BatterySample?

    @Query("SELECT * FROM battery_samples WHERE timestamp BETWEEN :from AND :to AND chargeCounterUah IS NOT NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastCounter(from: Long, to: Long): BatterySample?

    @Query("DELETE FROM battery_samples WHERE timestamp < :olderThan")
    suspend fun purge(olderThan: Long)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChargeSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChargeSession>)

    /** Include sessions that overlap the selected interval, including an active session. */
    @Query("SELECT * FROM charge_sessions WHERE startTime <= :to AND (endTime IS NULL OR endTime >= :from) ORDER BY startTime DESC")
    suspend fun sessionsOverlapping(from: Long, to: Long): List<ChargeSession>

    @Query("SELECT * FROM charge_sessions WHERE endTime IS NULL LIMIT 1")
    suspend fun active(): ChargeSession?

    @Query("SELECT * FROM charge_sessions WHERE endTime IS NULL LIMIT 1")
    fun activeFlow(): Flow<ChargeSession?>

    @Query("SELECT * FROM charge_sessions ORDER BY startTime DESC LIMIT :limit OFFSET :offset")
    fun sessionsPaged(limit: Int, offset: Int): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_sessions ORDER BY startTime DESC")
    fun allSessions(): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_sessions WHERE sessionId = :id")
    fun session(id: String): Flow<ChargeSession?>

    /**
     * Sessions that started within the window. Export used to pull every session ever
     * recorded regardless of the date range the user picked, so the range silently applied
     * to samples only.
     */
    @Query("SELECT * FROM charge_sessions WHERE startTime BETWEEN :from AND :to ORDER BY startTime DESC")
    suspend fun sessionsBetween(from: Long, to: Long): List<ChargeSession>

    @Query("UPDATE charge_sessions SET endTime=:end, endLevel=:endLevel, deltaUah=:delta, avgCurrentUa=:avg, estCapacityMah=:cap WHERE sessionId=:id")
    suspend fun complete(id: String, end: Long, endLevel: Int, delta: Long?, avg: Long?, cap: Int?)
}

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AlarmRule)

    @Query("SELECT * FROM alarm_rules")
    fun rules(): Flow<List<AlarmRule>>

    @Query("DELETE FROM alarm_rules WHERE id=:id")
    suspend fun delete(id: Long)
}

/**
 * App energy aggregation DAO (heuristic mode).
 */
@Dao
interface AppEnergyDao {
    /** One Room transaction for all pending heuristic/measurement increments. */
    @Transaction
    suspend fun incrementEntries(entries: List<AppEnergyIncrement>) {
        entries.forEach { entry ->
            val changed = updateIncrement(
                entry.bucketStart,
                entry.packageName,
                entry.mode,
                entry.energyMah,
                entry.samples
            )
            if (changed == 0) {
                insert(
                    AppEnergyStat(
                        bucketStart = entry.bucketStart,
                        packageName = entry.packageName,
                        mode = entry.mode,
                        energyMah = entry.energyMah,
                        samples = entry.samples
                    )
                )
            }
        }
    }

    @Transaction
    suspend fun incrementBatch(deltas: Map<String, Double>, atMillis: Long, mode: String) {
        incrementEntries(deltas.map { (pkg, delta) ->
            AppEnergyIncrement(hourBucketStart(atMillis), pkg, mode, delta, 1)
        })
    }

    @Transaction
    suspend fun incrementHour(packageName: String, atMillis: Long, deltaMah: Double, addSamples: Int, mode: String = "HEURISTIC") {
        val bucket = hourBucketStart(atMillis)
        val changed = updateIncrement(bucket, packageName, mode, deltaMah, addSamples)
        if (changed == 0) {
            insert(AppEnergyStat(bucketStart = bucket, packageName = packageName, mode = mode, energyMah = deltaMah, samples = addSamples))
        }
    }

    @Query("UPDATE app_energy_stats SET energyMah = energyMah + :deltaMah, samples = samples + :addSamples WHERE bucketStart = :bucket AND packageName = :pkg AND mode = :mode")
    suspend fun updateIncrement(bucket: Long, pkg: String, mode: String, deltaMah: Double, addSamples: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stat: AppEnergyStat)

    @Query("""
        SELECT packageName AS packageName, SUM(energyMah) AS energyMah, SUM(samples) AS samples
        FROM app_energy_stats
        WHERE bucketStart BETWEEN :from AND :to AND (mode = :mode OR (:mode = 'MEASURED' AND mode != 'HEURISTIC'))
        GROUP BY packageName
        ORDER BY energyMah DESC
        LIMIT :limit
    """)
    fun topDrainers(from: Long, to: Long, mode: String = "HEURISTIC", limit: Int = 10): Flow<List<AppDrainAggregate>>

    /**
     * Every app with recorded energy in the window, heaviest first.
     *
     * Unlike [topDrainers] this is unbounded, so the caller can show a share of the real
     * total rather than a share of whatever made the top ten.
     */
    @Query("""
        SELECT packageName AS packageName, SUM(energyMah) AS energyMah, SUM(samples) AS samples
        FROM app_energy_stats
        WHERE bucketStart BETWEEN :from AND :to AND (mode = :mode OR (:mode = 'MEASURED' AND mode != 'HEURISTIC'))
        GROUP BY packageName
        ORDER BY energyMah DESC
    """)
    fun drainersInRange(from: Long, to: Long, mode: String): Flow<List<AppDrainAggregate>>

    /**
     * Which measurement modes have data in the window, heaviest first. A privileged mode is
     * a real reading from batterystats; HEURISTIC is an estimate, and the two should not be
     * added together.
     */
    @Query("""
        SELECT mode FROM app_energy_stats
        WHERE bucketStart BETWEEN :from AND :to
        GROUP BY mode
        ORDER BY SUM(energyMah) DESC
    """)
    fun modesInRange(from: Long, to: Long): Flow<List<String>>

    @Query("DELETE FROM app_energy_stats WHERE bucketStart < :olderThan")
    suspend fun purgeOlderThan(olderThan: Long)
}

internal fun hourBucketStart(ms: Long): Long {
    val hourMs = 60 * 60 * 1000L
    return (ms / hourMs) * hourMs
}

/** A pre-aggregated update, allowing high-rate samples to share one database transaction. */
data class AppEnergyIncrement(
    val bucketStart: Long,
    val packageName: String,
    val mode: String,
    val energyMah: Double,
    val samples: Int
)

/** One averaged chart bucket, keeping long-range charts bounded in memory. */
data class BatteryCurrentPoint(val timestamp: Long, val currentMa: Double?)

data class BatteryChartPoint(val timestamp: Long, val currentMa: Double?, val voltageMv: Double?, val tempC: Double?)
