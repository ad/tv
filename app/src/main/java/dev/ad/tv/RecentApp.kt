package dev.ad.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Which app the user most likely just came back from, so the home screen can focus its tile. */
object RecentApp {
    private const val LOOKBACK_MS = 6 * 60 * 60_000L

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun last(context: Context): String? {
        val prefs = Prefs.get(context)
        var best = prefs.lastLaunchedPackage
        var bestAt = prefs.lastLaunchedAt
        if (hasUsageAccess(context)) {
            lastFromUsage(context)?.let { (pkg, at) ->
                if (at > bestAt) {
                    best = pkg
                    bestAt = at
                }
            }
        }
        return best
    }

    private fun lastFromUsage(context: Context): Pair<String, Long>? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val e = UsageEvents.Event()
        var result: Pair<String, Long>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName
            if (pkg == context.packageName || pkg.startsWith("com.google.android.apps.tv.launcherx") ||
                pkg == "com.android.systemui" || pkg == "com.android.tv.settings"
            ) continue
            result = pkg to e.timeStamp
        }
        return result
    }
}
