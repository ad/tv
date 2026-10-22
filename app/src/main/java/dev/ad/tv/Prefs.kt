package dev.ad.tv

import android.content.Context
import android.content.SharedPreferences

/** All user settings. One SharedPreferences file; the app runs in a single process. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // Background
    var backgroundPreset: Int
        get() = sp.getInt("bg_preset", 0)
        set(v) = sp.edit().putInt("bg_preset", v).apply()

    /** null = colour preset; otherwise "asset:///…", "content://…", "file://…" or an http(s) URL. */
    var backgroundVideo: String?
        get() = sp.getString("bg_video", null)
        set(v) = sp.edit().putString("bg_video", v).apply()

    /** Why the selected video could not play, shown in background settings; null when it played. */
    var videoError: String?
        get() = sp.getString("bg_video_error", null)
        set(v) = sp.edit().putString("bg_video_error", v).apply()

    var lastVideoUrl: String
        get() = sp.getString("bg_video_url", "") ?: ""
        set(v) = sp.edit().putString("bg_video_url", v).apply()

    // City
    var cityName: String
        get() = sp.getString("city_name", "Москва") ?: "Москва"
        set(v) = sp.edit().putString("city_name", v).apply()
    var cityLat: Double
        get() = sp.getString("city_lat", "55.7558")!!.toDouble()
        set(v) = sp.edit().putString("city_lat", v.toString()).apply()
    var cityLon: Double
        get() = sp.getString("city_lon", "37.6173")!!.toDouble()
        set(v) = sp.edit().putString("city_lon", v.toString()).apply()

    // Favorites, in display order
    var favorites: List<String>
        get() = sp.getString("favorites", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favorites", v.joinToString(",")).apply()

    var tileSize: String
        get() = sp.getString("tile_size", TileSize.LARGE.name)!!
        set(v) = sp.edit().putString("tile_size", v).apply()

    // Remote cursor
    var cursorMode: String
        get() = sp.getString("cursor_mode", CursorMode.LAUNCHER.name)!!
        set(v) = sp.edit().putString("cursor_mode", v).apply()
    /** Remote key that shows/hides the cursor; the Streamer remote's star button by default. */
    var cursorKey: Int
        get() = sp.getInt("cursor_key", android.view.KeyEvent.KEYCODE_BOOKMARK)
        set(v) = sp.edit().putInt("cursor_key", v).apply()
    var cursorSpeed: Float
        get() = sp.getFloat("cursor_speed", 1f)
        set(v) = sp.edit().putFloat("cursor_speed", v).apply()

    var lastLaunchedPackage: String?
        get() = sp.getString("last_pkg", null)
        set(v) = sp.edit().putString("last_pkg", v).putLong("last_pkg_at", System.currentTimeMillis()).apply()
    val lastLaunchedAt: Long get() = sp.getLong("last_pkg_at", 0)

    // Feed
    var feedEnabled: Boolean
        get() = sp.getBoolean("feed_on", true)
        set(v) = sp.edit().putBoolean("feed_on", v).apply()
    var feedSource: String
        get() = sp.getString("feed_source", FeedSource.CUB.name)!!
        set(v) = sp.edit().putString("feed_source", v).apply()
    var feedMode: String
        get() = sp.getString("feed_mode", FeedMode.NOW.name)!!
        set(v) = sp.edit().putString("feed_mode", v).apply()
    var feedKind: String
        get() = sp.getString("feed_kind", FeedKind.BOTH.name)!!
        set(v) = sp.edit().putString("feed_kind", v).apply()
    var tmdbKey: String
        get() = sp.getString("tmdb_key", "")!!
        set(v) = sp.edit().putString("tmdb_key", v.trim()).apply()
    var kpKey: String
        get() = sp.getString("kp_key", "")!!
        set(v) = sp.edit().putString("kp_key", v.trim()).apply()
    var tmdbApiHost: String
        get() = sp.getString("tmdb_api_host", "")!!
        set(v) = sp.edit().putString("tmdb_api_host", v.trim()).apply()
    var tmdbImageHost: String
        get() = sp.getString("tmdb_img_host", "")!!
        set(v) = sp.edit().putString("tmdb_img_host", v.trim()).apply()
    var openIn: String
        get() = sp.getString("open_in", OpenIn.LAMPA.name)!!
        set(v) = sp.edit().putString("open_in", v).apply()
    var hiddenFeedItems: Set<String>
        get() = sp.getStringSet("feed_hidden", emptySet())!!
        set(v) = sp.edit().putStringSet("feed_hidden", HashSet(v)).apply()

    /** Bumped whenever a setting that affects the home screen changes, so it knows to rebuild. */
    var revision: Int
        get() = sp.getInt("revision", 0)
        private set(v) = sp.edit().putInt("revision", v).apply()

    fun touch() {
        revision += 1
    }

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }
    }
}

/** Favorites tile size in dp (16:9) and the panel padding around the row. */
enum class TileSize(val width: Int, val height: Int, val panelPadding: Int) {
    COMPACT(112, 63, 6),
    MEDIUM(136, 77, 8),
    LARGE(160, 90, 10),
}

enum class FeedSource { CUB, TMDB, KINOPOISK }
enum class FeedMode { NOW, NEW, NEW_4K, HOT, TOP }
enum class FeedKind { BOTH, MOVIE, TV }
enum class OpenIn(val pkg: String) {
    LAMPA("top.rootu.lampa"),
    KINOPOISK("ru.kinopoisk.tv"),
    NUM("ru.yourok.num"),
}
