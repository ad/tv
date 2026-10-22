package dev.ad.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class CrashWatchdog {
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 5 * 60_000L;
    private static final String PREFS = "crashes";

    private CrashWatchdog() {}

    static void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            List<String> recent = new ArrayList<>();
            for (long t : times(context)) if (now - t < WINDOW_MS) recent.add(Long.toString(t));
            recent.add(Long.toString(now));
            // commit(): the process is about to die.
            sp.edit().putString("times", String.join(",", recent)).commit();
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static boolean tripped(Context context) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (long t : times(context)) if (now - t < WINDOW_MS) count++;
        return count >= LIMIT;
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("times").apply();
    }

    private static List<Long> times(Context context) {
        String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("times", "");
        List<Long> out = new ArrayList<>();
        for (String part : s.split(",")) {
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
