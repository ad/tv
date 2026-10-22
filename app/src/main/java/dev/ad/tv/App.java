package dev.ad.tv;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashWatchdog.install(this);
    }
}
