package app.batstats.battery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportLimitTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedSingleCsvLine() {
        boundedCsvLines("12345".reader(), 4).toList()
    }
    @Test fun acceptsExactByteLimitAndStripsBom() {
        val bytes = "\uFEFF{\"samples\":[]}".toByteArray()
        assertEquals("{\"samples\":[]}", readImportText(bytes.inputStream(), bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedArchiveBeforeParsing() {
        readImportText(ByteArray(8193).inputStream(), 8192)
    }
}
