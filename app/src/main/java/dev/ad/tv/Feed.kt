package dev.ad.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class FeedItem(
    /** Stable id used for hiding: "<source>:<movie|tv>:<id>". */
    val key: String,
    val title: String,
    val year: String,
    val isTv: Boolean,
    val backdrop: String?,
    val poster: String?,
    val tmdbId: Long?,
    val kpHd: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("title", title).put("year", year).put("tv", isTv)
        .put("backdrop", backdrop).put("poster", poster).put("tmdb", tmdbId).put("kphd", kpHd)

    companion object {
        fun fromJson(o: JSONObject) = FeedItem(
            key = o.getString("key"), title = o.getString("title"), year = o.optString("year"),
            isTv = o.getBoolean("tv"), backdrop = o.optStringOrNull("backdrop"), poster = o.optStringOrNull("poster"),
            tmdbId = if (o.isNull("tmdb")) null else o.optLong("tmdb"), kpHd = o.optStringOrNull("kphd"),
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

/** Movie/series feed from Lampa's CUB, TMDB (via Lampa's proxies) or Kinopoisk (poiskkino.dev). */
object FeedRepo {
    const val REFRESH_MS = 6 * 60 * 60_000L

    private val CUB_DOMAINS = listOf("cub.black", "cub.red", "cub.best")
    private val TMDB_API = listOf("https://apitmdb.cub.black/3", "https://apitmdb.cub.best/3", "https://lampa.byskaz.ru/tmdb/api/3")
    private const val TMDB_IMG = "https://imagetmdb.com"
    private const val KP_API = "https://api.poiskkino.dev/v1.5/movie"

    class Config(val source: FeedSource, val mode: FeedMode, val kind: FeedKind) {
        val id get() = "${source.name}/${mode.name}/${kind.name}"

        companion object {
            fun from(p: Prefs) = Config(
                runCatching { FeedSource.valueOf(p.feedSource) }.getOrDefault(FeedSource.CUB),
                runCatching { FeedMode.valueOf(p.feedMode) }.getOrDefault(FeedMode.NOW),
                runCatching { FeedKind.valueOf(p.feedKind) }.getOrDefault(FeedKind.BOTH),
            )
        }
    }

    class Cached(val items: List<FeedItem>, val fetchedAt: Long)

    private fun cacheFile(context: Context) = File(context.cacheDir, "feed.json")

    fun cached(context: Context, config: Config): Cached? = try {
        val o = JSONObject(cacheFile(context).readText())
        if (o.getString("config") != config.id) null else {
            val arr = o.getJSONArray("items")
            Cached((0 until arr.length()).map { FeedItem.fromJson(arr.getJSONObject(it)) }, o.getLong("at"))
        }
    } catch (e: Exception) {
        null
    }

    /** Network call; run off the main thread. */
    fun fetch(context: Context, config: Config): List<FeedItem> {
        val prefs = Prefs.get(context)
        val items = when (config.source) {
            FeedSource.CUB -> cub(prefs, config)
            FeedSource.TMDB -> tmdb(prefs, config)
            FeedSource.KINOPOISK -> kinopoisk(prefs, config)
        }.distinctBy { it.key }
        val arr = JSONArray().apply { items.forEach { put(it.toJson()) } }
        cacheFile(context).writeText(
            JSONObject().put("config", config.id).put("at", System.currentTimeMillis()).put("items", arr).toString(),
        )
        items.take(8).forEach { item -> listOfNotNull(item.backdrop, item.poster).forEach { Images.prefetch(context, it) } }
        return items
    }

    // Hidden entries are stored as "key\ttitle" so the settings list can show titles.
    fun visible(context: Context, items: List<FeedItem>): List<FeedItem> {
        val hidden = Prefs.get(context).hiddenFeedItems.mapTo(HashSet()) { it.substringBefore('\t') }
        return items.filter { it.key !in hidden }
    }

    fun hide(context: Context, item: FeedItem) {
        val prefs = Prefs.get(context)
        prefs.hiddenFeedItems = prefs.hiddenFeedItems + "${item.key}\t${item.title}"
    }

    fun clearCache(context: Context) {
        cacheFile(context).delete()
    }

    // ---- CUB --------------------------------------------------------------------------------

    private fun cubPath(mode: FeedMode, cat: String): String = when (mode) {
        FeedMode.NOW -> "/?sort=now_playing&cat=$cat&page=1"
        FeedMode.NEW -> "/?sort=latest&cat=$cat&page=1"
        FeedMode.NEW_4K -> if (cat == "movie") "/?cat=movie&sort=latest&uhd=true&page=1" else "/?sort=latest&cat=tv&page=1"
        FeedMode.HOT -> "/top/fire/$cat"
        FeedMode.TOP -> "/top/hundred/$cat"
    }

    /** What Lampa should open for the "More" card; matches [cubPath] for the first category. */
    fun cubLampaUrl(config: Config): String {
        val cat = if (config.kind == FeedKind.TV) "tv" else "movie"
        return cubPath(config.mode, cat).removePrefix("/")
    }

    private fun cub(prefs: Prefs, config: Config): List<FeedItem> {
        val bases = CUB_DOMAINS.map { "https://tmdb.$it" }
        val img = imageHost(prefs)
        return byKind(config.kind) { cat ->
            val arr = JSONObject(Http.getFirst(bases, cubPath(config.mode, cat))).getJSONArray("results")
            parseTmdbList(arr, "cub", img, forceTv = null)
        }
    }

    // ---- TMDB via proxy ---------------------------------------------------------------------

    private fun tmdb(prefs: Prefs, config: Config): List<FeedItem> {
        val key = prefs.tmdbKey.ifBlank { throw IllegalStateException("TMDB key is not set") }
        val bases = listOfNotNull(prefs.tmdbApiHost.takeIf { it.isNotBlank() }?.trimEnd('/')) + TMDB_API
        val img = imageHost(prefs)
        val q = "api_key=${Http.enc(key)}&language=ru-RU&region=RU"
        val fresh = LocalDate.now().minusDays(120).toString()
        val today = LocalDate.now().toString()
        return byKind(config.kind) { cat ->
            val path = if (config.mode == FeedMode.NEW) {
                if (cat == "movie") "/discover/movie?$q&sort_by=popularity.desc&vote_count.gte=20" +
                    "&primary_release_date.gte=$fresh&primary_release_date.lte=$today"
                else "/discover/tv?$q&sort_by=popularity.desc&vote_count.gte=10&first_air_date.gte=$fresh&first_air_date.lte=$today"
            } else {
                "/trending/$cat/week?$q"
            }
            val arr = JSONObject(Http.getFirst(bases, path)).getJSONArray("results")
            parseTmdbList(arr, "tmdb", img, forceTv = cat == "tv")
        }
    }

    private fun imageHost(prefs: Prefs) = prefs.tmdbImageHost.ifBlank { TMDB_IMG }.trimEnd('/')

    private fun parseTmdbList(arr: JSONArray, source: String, img: String, forceTv: Boolean?): List<FeedItem> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val isTv = forceTv ?: (o.optString("media_type") == "tv" || (!o.has("title") || o.isNull("title")) && o.has("name"))
            val title = (if (isTv) o.optStringOrNull("name") else o.optStringOrNull("title"))
                ?: o.optStringOrNull("title") ?: o.optStringOrNull("name") ?: return@mapNotNull null
            val date = o.optStringOrNull("release_date") ?: o.optStringOrNull("first_air_date") ?: ""
            val id = o.getLong("id")
            FeedItem(
                key = "$source:${if (isTv) "tv" else "movie"}:$id",
                title = title,
                year = date.take(4),
                isTv = isTv,
                backdrop = o.optStringOrNull("backdrop_path")?.let { "$img/t/p/w780$it" },
                poster = o.optStringOrNull("poster_path")?.let { "$img/t/p/w342$it" },
                tmdbId = id,
                kpHd = null,
            )
        }

    // ---- Kinopoisk (poiskkino.dev) ----------------------------------------------------------

    private fun kinopoisk(prefs: Prefs, config: Config): List<FeedItem> {
        val key = prefs.kpKey.ifBlank { throw IllegalStateException("Kinopoisk key is not set") }
        val year = LocalDate.now().year
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val filter = if (config.mode == FeedMode.NEW) {
            "premiere.digital=${LocalDate.now().minusDays(90).format(fmt)}-${LocalDate.now().format(fmt)}"
        } else {
            "year=${year - 1}-$year"
        }
        val kind = when (config.kind) {
            FeedKind.MOVIE -> "&isSeries=false"
            FeedKind.TV -> "&isSeries=true"
            FeedKind.BOTH -> ""
        }
        val fields = listOf("id", "name", "alternativeName", "year", "isSeries", "poster", "backdrop", "externalId")
            .joinToString("") { "&selectFields=$it" }
        val url = "$KP_API?limit=30&$filter$kind$fields&notNullFields=poster.url&sortField=votes.kp&sortType=-1"
        val docs = JSONObject(Http.get(url, mapOf("X-API-KEY" to key))).getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { i ->
            val o = docs.getJSONObject(i)
            val title = o.optStringOrNull("name") ?: o.optStringOrNull("alternativeName") ?: return@mapNotNull null
            val isTv = o.optBoolean("isSeries")
            val ext = o.optJSONObject("externalId")
            FeedItem(
                key = "kp:${if (isTv) "tv" else "movie"}:${o.getLong("id")}",
                title = title,
                year = o.optString("year"),
                isTv = isTv,
                backdrop = o.optJSONObject("backdrop")?.optStringOrNull("url"),
                poster = o.optJSONObject("poster")?.let { it.optStringOrNull("previewUrl") ?: it.optStringOrNull("url") },
                tmdbId = ext?.takeIf { it.has("tmdb") && !it.isNull("tmdb") }?.optLong("tmdb"),
                kpHd = ext?.optStringOrNull("kpHD"),
            )
        }
    }

    /** Fetches movies and series separately for [FeedKind.BOTH] and interleaves them. */
    private fun byKind(kind: FeedKind, load: (String) -> List<FeedItem>): List<FeedItem> = when (kind) {
        FeedKind.MOVIE -> load("movie")
        FeedKind.TV -> load("tv")
        FeedKind.BOTH -> {
            val movies = runCatching { load("movie") }
            val shows = runCatching { load("tv") }
            if (movies.isFailure && shows.isFailure) throw movies.exceptionOrNull()!!
            interleave(movies.getOrDefault(emptyList()), shows.getOrDefault(emptyList()))
        }
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(out::add)
            b.getOrNull(i)?.let(out::add)
        }
        return out
    }
}

/** Opens feed items in Lampa / Kinopoisk / NUM. */
object FeedOpener {
    fun open(context: Context, item: FeedItem, target: OpenIn) {
        if (!Apps.isInstalled(context, target.pkg)) {
            Toast.makeText(context, context.getString(R.string.install_app, appName(context, target)), Toast.LENGTH_LONG).show()
            return
        }
        val ok = when (target) {
            OpenIn.LAMPA -> openLampa(context, item)
            OpenIn.KINOPOISK -> openKinopoisk(context, item)
            OpenIn.NUM -> false
        }
        if (!ok) Apps.launch(context, target.pkg) else Prefs.get(context).lastLaunchedPackage = target.pkg
    }

    /** Opens the same CUB list in Lampa ("More" card). */
    fun openLampaList(context: Context, title: String, url: String) {
        val activity = JSONObject().put("component", "category_full").put("source", "cub")
            .put("url", url).put("title", title).put("page", 1)
        val intent = Intent(Intent.ACTION_VIEW).setPackage(OpenIn.LAMPA.pkg)
            .putExtra("continueWatch", true)
            .putExtra("lampaActivity", activity.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Apps.start(context, intent)) Prefs.get(context).lastLaunchedPackage = OpenIn.LAMPA.pkg
        else Apps.launch(context, OpenIn.LAMPA.pkg)
    }

    private fun openLampa(context: Context, item: FeedItem): Boolean {
        val pkg = OpenIn.LAMPA.pkg
        item.tmdbId?.let { id ->
            val uri = Uri.parse("https://www.themoviedb.org/${if (item.isTv) "tv" else "movie"}/$id")
            if (Apps.start(context, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true
        }
        return Apps.start(
            context,
            Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, item.title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openKinopoisk(context: Context, item: FeedItem): Boolean {
        val hd = item.kpHd ?: return false
        val pkg = OpenIn.KINOPOISK.pkg
        return listOf("kpatv://film/$hd", "https://hd.kinopoisk.ru/film/$hd").any { uri ->
            Apps.start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun appName(context: Context, target: OpenIn): String = when (target) {
        OpenIn.LAMPA -> "Lampa"
        OpenIn.KINOPOISK -> context.getString(R.string.kinopoisk)
        OpenIn.NUM -> "NUM"
    }
}
