package app.batstats.battery.data

import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession

/** The two CSV formats written by BatStats. Reject malformed rows instead of guessing. */
internal object BatteryCsv {
    const val SAMPLE_HEADER = "timestamp,levelPercent,status,plugged,currentNowUa,chargeCounterUah,voltageMv,temperatureDeciC,health,screenOn"
    const val SESSION_HEADER = "sessionId,type,startTime,endTime,startLevel,endLevel,deltaUah,avgCurrentUa,estCapacityMah"

    fun parseSample(line: String): BatterySample {
        val p = columns(line, 10)
        return BatterySample(
            timestamp = p[0].toLong(),
            levelPercent = p[1].toInt(),
            status = p[2].toInt(),
            plugged = p[3].toInt(),
            currentNowUa = p[4].takeIf(String::isNotEmpty)?.toLong(),
            chargeCounterUah = p[5].takeIf(String::isNotEmpty)?.toLong(),
            voltageMv = p[6].takeIf(String::isNotEmpty)?.toInt(),
            temperatureDeciC = p[7].takeIf(String::isNotEmpty)?.toInt(),
            health = p[8].takeIf(String::isNotEmpty)?.toInt(),
            screenOn = p[9].toBooleanStrict()
        )
    }

    fun parseSession(line: String): ChargeSession {
        val p = columns(line, 9)
        require(p[0].isNotBlank()) { "Missing session ID" }
        return ChargeSession(
            sessionId = p[0],
            type = enumValueOf(p[1]),
            startTime = p[2].toLong(),
            endTime = p[3].takeIf(String::isNotEmpty)?.toLong(),
            startLevel = p[4].toInt(),
            endLevel = p[5].takeIf(String::isNotEmpty)?.toInt(),
            deltaUah = p[6].takeIf(String::isNotEmpty)?.toLong(),
            avgCurrentUa = p[7].takeIf(String::isNotEmpty)?.toLong(),
            estCapacityMah = p[8].takeIf(String::isNotEmpty)?.toInt()
        )
    }

    private fun columns(line: String, count: Int): List<String> =
        line.split(',').map(String::trim).also {
            require(it.size == count) { "Expected $count columns, found ${it.size}" }
        }
}
