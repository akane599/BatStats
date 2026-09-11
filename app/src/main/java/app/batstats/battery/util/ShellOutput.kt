package app.batstats.battery.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A failure can follow partial dumpsys output; checking only its first line loses it. */
internal fun shellOutputError(output: String): String? = output.lineSequence()
    .map(String::trimStart)
    .firstOrNull {
        it.equals("ERROR", ignoreCase = true) ||
            it.startsWith("ERROR:", ignoreCase = true) ||
            it.startsWith("ERROR ", ignoreCase = true) ||
            it.startsWith("Permission Denial", ignoreCase = true)
    }
    ?.take(200)
    ?.trim()

/** The limit counts bytes, including multibyte UTF-8, and never returns a truncated dump. */
internal fun readShellOutput(input: InputStream, maxOutputBytes: Int): String {
    require(maxOutputBytes > 0)
    val buffer = ByteArray(minOf(64 * 1024, maxOutputBytes))
    val sink = ByteArrayOutputStream(buffer.size)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > maxOutputBytes - total) {
            throw IOException("Command output exceeds $maxOutputBytes bytes; refusing a partial dump")
        }
        sink.write(buffer, 0, read)
        total += read
    }
    return sink.toString(Charsets.UTF_8.name())
}
