package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class RootFilesTest {
    @Test fun preservesMultilineValuesAndSkipsUnreadableFiles() {
        val data = parseRootFiles("""
            su diagnostic
            __BATSTATS_FILE__/sys/battery/current_now
            -150000
            __BATSTATS_FILE__/sys/cpu/stats/time_in_state
            300000 42
            600000 11
            __BATSTATS_FILE__/sys/battery/missing
        """.trimIndent())
        assertEquals("-150000", data["/sys/battery/current_now"])
        assertEquals("300000 42\n600000 11", data["/sys/cpu/stats/time_in_state"])
        assertFalse(data.containsKey("/sys/battery/missing"))
    }
}
