package app.batstats

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Uses AGP's output directory so captures survive instrumentation's APK cleanup. */
internal object TestScreenshots {
    fun save(name: String, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.getExternalFilesDir(null), "screenshots")
        val output = File(directory, "$name.png")
        if (Build.VERSION.SDK_INT >= 31) {
            // AGP creates its output directory as shell. Scoped storage can prevent the
            // app UID from writing there, so transfer the capture through the test API.
            // UiAutomation tokenizes directly; shell quoting would become literal filename
            // characters. Restrict these test-owned paths before passing them as arguments.
            check(output.absolutePath.matches(Regex("[A-Za-z0-9_./-]+"))) {
                "Unsupported screenshot output path: ${output.absolutePath}"
            }
            val parent = output.parentFile!!.absolutePath
            shell("mkdir -p $parent")
            val directoryResult = shell("ls -d $parent").trim()
            check(directoryResult == parent) { "Cannot prepare screenshot directory: $directoryResult" }
            val automation = instrumentation.uiAutomation
            val pipes = automation.executeShellCommandRw("dd of=${output.absolutePath}")
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { response ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        "PNG transfer failed for ${output.absolutePath} (${bitmap.width}x${bitmap.height})"
                    }
                }
                response.readBytes()
            }
            val sizeResult = shell("wc -c ${output.absolutePath}").trim()
            check(sizeResult.substringBefore(' ').toLongOrNull()?.let { it > 0 } == true) {
                "Screenshot output is empty or unavailable: $sizeResult"
            }
        } else {
            output.parentFile!!.mkdirs()
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
