package dev.ad.tv;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

final class RecentApp {
    private static final long LOOKBACK_MS = 6 * 60 * 60_000L;

    private RecentApp() {}

    static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) ==
            AppOpsManager.MODE_ALLOWED;
    }

    static String last(Context context) {
        Prefs prefs = Prefs.get(context);
        String best = prefs.getLastLaunchedPackage();
        long bestAt = prefs.getLastLaunchedAt();
        if (hasUsageAccess(context)) {
            UsageStatsManager usm = context.getSystemService(UsageStatsManager.class);
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents events = usm.queryEvents(now - LOOKBACK_MS, now);
                UsageEvents.Event e = new UsageEvents.Event();
                String pkg = null;
                long at = 0;
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
                    String p = e.getPackageName();
                    if (p.equals(context.getPackageName()) || p.startsWith("com.google.android.apps.tv.launcherx") ||
                        p.equals("com.android.systemui") || p.equals("com.android.tv.settings")
                    ) continue;
                    pkg = p;
                    at = e.getTimeStamp();
                }
                if (pkg != null && at > bestAt) best = pkg;
            }
        }
        return best;
    }
}
