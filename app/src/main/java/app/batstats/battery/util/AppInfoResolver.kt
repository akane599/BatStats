package app.batstats.battery.util

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a package name into something a person recognises.
 *
 * A drain list of "com.google.android.gms" and "com.whatsapp" is a list of strings; the same
 * list with labels and icons is something you can act on. Both lookups hit PackageManager,
 * so both are cached - the list re-reads them on every scroll otherwise.
 */
class AppInfoResolver(private val context: Context) {

    companion object {
        /** Icons are decoded at this size; anything larger is wasted on a list row. */
        private const val ICON_PX = 96
    }

    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, Optional>()
    private val systemFlags = ConcurrentHashMap<String, Boolean>()

    /** ConcurrentHashMap cannot hold nulls, and "no icon" needs caching as much as an icon. */
    private class Optional(val value: ImageBitmap?)

    /** The app's display name, or the package name when it cannot be resolved. */
    fun label(packageName: String): String = labels.getOrPut(packageName) {
        val info = applicationInfo(packageName)
        if (info == null) prettifyPackage(packageName)
        else runCatching { context.packageManager.getApplicationLabel(info).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: prettifyPackage(packageName)
    }

    fun icon(packageName: String): ImageBitmap? = icons.getOrPut(packageName) {
        val info = applicationInfo(packageName)
        val bitmap = if (info == null) null else runCatching {
            context.packageManager.getApplicationIcon(info)
                .toBitmap(ICON_PX, ICON_PX)
                .asImageBitmap()
        }.getOrNull()
        Optional(bitmap)
    }.value

    /** True for platform and pre-installed packages, so the list can offer to hide them. */
    fun isSystem(packageName: String): Boolean = systemFlags.getOrPut(packageName) {
        val info = applicationInfo(packageName) ?: return@getOrPut packageName.startsWith("uid:")
        val mask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        (info.flags and mask) != 0
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? =
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()

    /**
     * A package we cannot look up is usually one that has since been uninstalled, or a uid
     * with no package at all. Its last segment still says more than the whole string does.
     */
    private fun prettifyPackage(packageName: String): String = when {
        packageName.startsWith("uid:") -> packageName
        else -> packageName.substringAfterLast('.').ifBlank { packageName }
    }
}
