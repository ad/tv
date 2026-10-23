package dev.ad.tv;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // CursorService's process: its crashes must not count toward the launcher's rescue screen.
        if (!getPackageName().equals(getProcessName())) return;
        CrashWatchdog.install(this);
        Apps.watch(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) Images.trimMemory();
    }
}
