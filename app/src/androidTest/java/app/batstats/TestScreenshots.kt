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
            val automation = instrumentation.uiAutomation
            val pipes = automation.executeShellCommandRw("dd of=${quote(output.absolutePath)}")
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { response ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                response.readBytes()
            }
            check(shell("wc -c ${quote(output.absolutePath)}").trim().substringBefore(' ')
                .toLongOrNull()?.let { it > 0 } == true)
        } else {
            output.parentFile!!.mkdirs()
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
