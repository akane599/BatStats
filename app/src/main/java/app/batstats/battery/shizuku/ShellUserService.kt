package app.batstats.battery.shizuku

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku UserService binder that executes shell commands with the shell (ADB) identity.
 *
 * Output is streamed back over a pipe instead of being written into the reply [Parcel].
 * `dumpsys batterystats --checkin` routinely produces hundreds of kilobytes (megabytes on
 * devices with many apps), which overflows the 1 MB per-process binder transaction buffer.
 * That failure is intermittent - the buffer is shared with every other binder call the
 * process makes - which is why dumps used to succeed once or twice and then start failing.
 */
class ShellUserService : Binder() {

    companion object {
        /** Legacy: writes the whole output into the reply parcel. Small outputs only. */
        const val TRANSACTION_RUN = 1

        /** Streams output over a caller-supplied pipe. No size limit. */
        const val TRANSACTION_RUN_PIPE = 2

        /** Shizuku calls this when it wants the service to go away. */
        const val TRANSACTION_DESTROY = 16777114

        /** Guard for the legacy path so it can never blow the binder buffer. */
        private const val MAX_INLINE_OUTPUT_CHARS = 192 * 1024

        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_RUN -> {
                val cmd = data.readString().orEmpty()
                val out = runCommand(cmd, DEFAULT_TIMEOUT_MS)
                reply?.writeString(
                    if (out.length > MAX_INLINE_OUTPUT_CHARS) "ERROR:Inline output limit exceeded" else out
                )
                true
            }

            TRANSACTION_RUN_PIPE -> {
                val cmd = data.readString().orEmpty()
                val timeoutMs = data.readLong().takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS
                val pfd = try {
                    ParcelFileDescriptor.CREATOR.createFromParcel(data)
                } catch (_: Throwable) {
                    null
                }
                if (pfd == null) {
                    reply?.writeInt(0)
                } else {
                    // Reply *before* the command runs: the caller is blocked in transact() and
                    // cannot drain the pipe until we return, and a pipe only buffers ~64 KB.
                    reply?.writeInt(1)
                    streamCommand(cmd, pfd, timeoutMs)
                }
                true
            }

            TRANSACTION_DESTROY -> {
                destroy()
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }

    /** Runs [cmd] and pumps its combined output into [pfd], closing it when done. */
    private fun streamCommand(cmd: String, pfd: ParcelFileDescriptor, timeoutMs: Long) {
        val worker = Thread {
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            var process: Process? = null
            var watchdog: Thread? = null
            val timedOut = AtomicBoolean(false)
            try {
                val p = ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                process = p
                runCatching { p.outputStream.close() }

                watchdog = Thread {
                    try {
                        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                            timedOut.set(true)
                            p.destroyForcibly()
                        }
                    } catch (_: InterruptedException) {
                        // Command finished first; nothing to do.
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                val buffer = ByteArray(COPY_BUFFER_BYTES)
                val source = p.inputStream
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
                val exitCode = p.waitFor()
                val error = when {
                    timedOut.get() -> "Command timed out after $timeoutMs ms"
                    exitCode != 0 -> "Command exited with status $exitCode"
                    else -> null
                }
                if (error != null) sink.write("\nERROR:$error\n".toByteArray())
                sink.flush()
            } catch (t: Throwable) {
                runCatching {
                    sink.write("\nERROR:${t.message ?: t.javaClass.simpleName}\n".toByteArray())
                }
            } finally {
                watchdog?.interrupt()
                runCatching { process?.destroy() }
                runCatching { sink.close() }
            }
        }
        worker.isDaemon = true
        worker.name = "batstats-shell"
        worker.start()
    }

    private fun runCommand(cmd: String, timeoutMs: Long): String {
        var process: Process? = null
        var watchdog: Thread? = null
        val timedOut = AtomicBoolean(false)
        return try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            process = p
            runCatching { p.outputStream.close() }
            // Start the timeout before reading: readText() itself can block indefinitely.
            watchdog = Thread {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Finished first.
                }
            }.apply { isDaemon = true; start() }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val exitCode = p.waitFor()
            when {
                timedOut.get() -> "ERROR:Command timed out after $timeoutMs ms"
                exitCode != 0 -> "ERROR:Command exited with status $exitCode"
                else -> out
            }
        } catch (t: Throwable) {
            "ERROR:${t.message ?: t.javaClass.simpleName}"
        } finally {
            watchdog?.interrupt()
            runCatching { process?.destroy() }
        }
    }

    private fun destroy() {
        // Give the reply a chance to make it back to the caller before the process goes away.
        Thread {
            runCatching { Thread.sleep(100) }
            Runtime.getRuntime().halt(0)
        }.apply { isDaemon = true }.start()
    }
}
