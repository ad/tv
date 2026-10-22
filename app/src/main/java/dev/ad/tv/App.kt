package dev.ad.tv

import android.app.Application
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashWatchdog.install(this)
    }
}

/**
 * Remembers recent crashes. After [LIMIT] crashes within [WINDOW_MS] the home screen shows
 * [RescueActivity] instead of itself, so the TV is never left without a working home.
 */
object CrashWatchdog {
    private const val LIMIT = 3
    private const val WINDOW_MS = 5 * 60_000L
    private const val PREFS = "crashes"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val recent = times(context).filter { now - it < WINDOW_MS } + now
            // commit(): the process is about to die.
            sp.edit().putString("times", recent.joinToString(",")).commit()
            previous?.uncaughtException(thread, error)
        }
    }

    fun tripped(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return times(context).count { now - it < WINDOW_MS } >= LIMIT
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("times").apply()
    }

    private fun times(context: Context): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("times", "")!!.split(',').mapNotNull { it.toLongOrNull() }
}
