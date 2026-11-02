package dev.ad.tv;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // CursorService's process: its crashes must not count toward the launcher's rescue screen.
        if (!getPackageName().equals(getProcessName())) return;
        CrashWatchdog.install(this);
        Apps.watch(this);
        returnHomeOnSleep();
    }

    // The TV wakes to whatever was on top: a player left there resumes and, over HDMI-CEC, turns the TV on with it.
    // The stock launcher goes home on sleep; with it off, that is ours to do.
    private void returnHomeOnSleep() {
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!HomeSwitch.isOurs(c)) return;
                try {
                    // The current home may start activities from the background.
                    c.startActivity(new Intent(Intent.ACTION_MAIN, null, c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) Images.trimMemory();
    }
}
