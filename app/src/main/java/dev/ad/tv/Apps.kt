package dev.ad.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast

data class AppEntry(val pkg: String, val label: String)

object Apps {
    /** Packages we never show: ourselves and the stock launchers. */
    private val HIDDEN = setOf(
        BuildConfig.APPLICATION_ID,
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.tungsten.setupwraith",
    )

    /** Apps offered as favorites on first run, if installed. */
    private val DEFAULT_FAVORITES = listOf(
        "com.google.android.youtube.tv", "top.rootu.lampa", "ru.kinopoisk.tv", "ru.yourok.num",
        "ru.more.play", "ru.ivi.client", "ru.mts.mtstv", "ru.rt.video.app.tv", "com.google.android.youtube.tvmusic",
    )

    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg in HIDDEN || !seen.add(pkg)) continue
                out += AppEntry(pkg, ri.loadLabel(pm).toString())
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** The 16:9 TV banner, or null when the app only ships an icon. */
    fun banner(context: Context, pkg: String): Drawable? {
        val pm = context.packageManager
        return try {
            pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
                ?: pm.getApplicationBanner(pkg)
        } catch (e: Exception) {
            null
        }
    }

    fun icon(context: Context, pkg: String): Drawable? = try {
        context.packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = launchIntent(context, pkg) ?: return false.also {
            Toast.makeText(context, context.getString(R.string.app_not_found), Toast.LENGTH_SHORT).show()
        }
        Prefs.get(context).lastLaunchedPackage = pkg
        return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun openAppInfo(context: Context, pkg: String) {
        start(
            context,
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun seedFavorites(context: Context) {
        val prefs = Prefs.get(context)
        val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (sp.contains("favorites")) return
        prefs.favorites = DEFAULT_FAVORITES.filter { isInstalled(context, it) }
    }
}
