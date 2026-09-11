package app.batstats.battery.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ShellOutputTest {
    @Test fun `failure after partial output is not a successful dump`() {
        assertEquals("ERROR:Command exited with status 1",
            shellOutputError("9,0,i,vers,36\n\nERROR:Command exited with status 1\n"))
    }

    @Test fun `indented permission denial is rejected`() {
        assertEquals("Permission Denial: requires DUMP",
            shellOutputError("  Permission Denial: requires DUMP\n"))
    }

    @Test fun `empty reset output and error words within data are allowed`() {
        assertNull(shellOutputError(""))
        assertNull(shellOutputError("9,10001,l,wl,ErrorReporter,0\n"))
        assertNull(shellOutputError("ErrorReporter statistics\n"))
    }

    @Test fun `output exactly at the byte limit is complete`() {
        assertEquals("abcd", readShellOutput(ByteArrayInputStream("abcd".toByteArray()), 4))
    }

    @Test(expected = IOException::class)
    fun `oversized output is an error instead of partial success`() {
        readShellOutput(ByteArrayInputStream("abcde".toByteArray()), 4)
    }

    @Test(expected = IOException::class)
    fun `output limit is in bytes rather than characters`() {
        readShellOutput(ByteArrayInputStream("ğğ".toByteArray(Charsets.UTF_8)), 3)
    }
}
