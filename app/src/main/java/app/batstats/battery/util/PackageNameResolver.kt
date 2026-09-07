package app.batstats.battery.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a uid to a package name through [android.content.pm.PackageManager].
 *
 * The checkin dump carries its own uid -> package map, but that map is only as complete as
 * the identity that produced it: run through ADB-granted DUMP, dumpsys executes as BatStats
 * itself and Android strips packages the app cannot see, leaving nearly every row as
 * "uid:NNNNN". Looking the names up locally works whichever backend produced the dump.
 *
 * Requires the QUERY_ALL_PACKAGES permission to see beyond this app's own package.
 */
class PackageNameResolver(private val context: Context) {

    // Package names for a uid do not change while the process lives.
    private val cache = ConcurrentHashMap<Int, String>()

    fun nameFor(uid: Int): String? {
        cache[uid]?.let { return it.ifEmpty { null } }
        val resolved = lookup(uid).orEmpty()
        cache[uid] = resolved
        return resolved.ifEmpty { null }
    }

    private fun lookup(uid: Int): String? = try {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        when {
            // A shared uid lists several packages; the shortest is the most recognisable
            // ("com.foo" over "com.foo.overlay.something").
            !packages.isNullOrEmpty() -> packages.minByOrNull { it.length }
            // Falls back to a shared-user name such as "android.uid.system".
            else -> pm.getNameForUid(uid)
        }
    } catch (_: Throwable) {
        null
    }
}
