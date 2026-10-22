package dev.ad.tv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Apps {
    private static final Set<String> HIDDEN = Set.of(
        BuildConfig.APPLICATION_ID,
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.tungsten.setupwraith"
    );

    private static final List<String> DEFAULT_FAVORITES = List.of(
        "com.google.android.youtube.tv", "top.rootu.lampa", "ru.kinopoisk.tv", "ru.yourok.num",
        "ru.more.play", "ru.ivi.client", "ru.mts.mtstv", "ru.rt.video.app.tv", "com.google.android.youtube.tvmusic"
    );

    private Apps() {}

    static List<AppEntry> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<AppEntry> out = new ArrayList<>();
        for (String category : List.of(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
                out.add(new AppEntry(pkg, ri.loadLabel(pm).toString()));
            }
        }
        out.sort(Comparator.comparing(e -> e.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    static boolean isInstalled(Context context, String pkg) {
        try {
            return context.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static Drawable banner(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable d = intent != null ? pm.getActivityBanner(intent) : null;
            return d != null ? d : pm.getApplicationBanner(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    static Drawable icon(Context context, String pkg) {
        try {
            return context.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    static Intent launchIntent(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    static boolean launch(Context context, String pkg) {
        Intent intent = launchIntent(context, pkg);
        if (intent == null) {
            Toast.makeText(context, context.getString(R.string.app_not_found), Toast.LENGTH_SHORT).show();
            return false;
        }
        Prefs.get(context).setLastLaunchedPackage(pkg);
        return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    static void openAppInfo(Context context, String pkg) {
        start(
            context,
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
    }

    static void seedFavorites(Context context) {
        Prefs prefs = Prefs.get(context);
        SharedPreferences sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE);
        if (sp.contains("favorites")) return;
        List<String> installed = new ArrayList<>();
        for (String pkg : DEFAULT_FAVORITES) if (isInstalled(context, pkg)) installed.add(pkg);
        prefs.setFavorites(installed);
    }
}
