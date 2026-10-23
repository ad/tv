package dev.ad.tv;

import android.content.Context;
import android.icu.util.ULocale;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FeedRepo {
    static final long REFRESH_MS = 6 * 60 * 60_000L;

    private static final List<String> CUB_DOMAINS = List.of("cub.black", "cub.red", "cub.best");
    private static final List<String> TMDB_API =
        List.of("https://apitmdb.cub.black/3", "https://apitmdb.cub.best/3", "https://lampa.byskaz.ru/tmdb/api/3");
    private static final String TMDB_IMG = "https://imagetmdb.com";
    private static final String KP_API = "https://api.poiskkino.dev/v1.5/movie";

    private FeedRepo() {}

    static final class Config {
        final FeedSource source;
        final FeedMode mode;
        final FeedKind kind;

        Config(FeedSource source, FeedMode mode, FeedKind kind) {
            this.source = source;
            this.mode = mode;
            this.kind = kind;
        }

        String id() {
            return source.name() + "/" + mode.name() + "/" + kind.name() + "/" + Locale.getDefault().toLanguageTag();
        }

        static Config from(Prefs p) {
            return new Config(
                parse(FeedSource.class, p.getFeedSource(), FeedSource.CUB),
                parse(FeedMode.class, p.getFeedMode(), FeedMode.NOW),
                parse(FeedKind.class, p.getFeedKind(), FeedKind.BOTH)
            );
        }

        private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
            try {
                return Enum.valueOf(type, name);
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    static final class Cached {
        final List<FeedItem> items;
        final long fetchedAt;

        Cached(List<FeedItem> items, long fetchedAt) {
            this.items = items;
            this.fetchedAt = fetchedAt;
        }
    }

    private interface Loader {
        List<FeedItem> load(String cat) throws Exception;
    }

    private interface Retitle {
        String title(boolean isTv, long id) throws Exception;
    }

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), "feed.json");
    }

    static Cached cached(Context context, Config config) {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(cacheFile(context).toPath()), StandardCharsets.UTF_8));
            if (!o.getString("config").equals(config.id())) return null;
            JSONArray arr = o.getJSONArray("items");
            List<FeedItem> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) items.add(FeedItem.fromJson(arr.getJSONObject(i)));
            return new Cached(items, o.getLong("at"));
        } catch (Exception e) {
            return null;
        }
    }

    static List<FeedItem> fetch(Context context, Config config) throws Exception {
        Prefs prefs = Prefs.get(context);
        List<FeedItem> all = switch (config.source) {
            case CUB -> cub(prefs, config);
            case TMDB -> tmdb(context, prefs, config);
            case KINOPOISK -> kinopoisk(context, prefs, config);
        };
        Set<String> keys = new HashSet<>();
        List<FeedItem> items = new ArrayList<>();
        for (FeedItem item : all) if (keys.add(item.key)) items.add(item);
        JSONArray arr = new JSONArray();
        for (FeedItem item : items) arr.put(item.toJson());
        String json = new JSONObject().put("config", config.id()).put("at", System.currentTimeMillis()).put("items", arr).toString();
        Files.write(cacheFile(context).toPath(), json.getBytes(StandardCharsets.UTF_8));
        for (FeedItem item : items.subList(0, Math.min(8, items.size()))) {
            if (item.backdrop != null) Images.prefetch(context, item.backdrop);
            if (item.poster != null) Images.prefetch(context, item.poster);
        }
        return items;
    }

    // Hidden entries are stored as "key\ttitle" so the settings list can show titles.
    static List<FeedItem> visible(Context context, List<FeedItem> items) {
        Set<String> hidden = new HashSet<>();
        for (String s : Prefs.get(context).getHiddenFeedItems()) hidden.add(s.split("\t", 2)[0]);
        List<FeedItem> out = new ArrayList<>();
        for (FeedItem item : items) if (!hidden.contains(item.key)) out.add(item);
        return out;
    }

    static void hide(Context context, FeedItem item) {
        Prefs prefs = Prefs.get(context);
        Set<String> hidden = new HashSet<>(prefs.getHiddenFeedItems());
        hidden.add(item.key + "\t" + item.title);
        prefs.setHiddenFeedItems(hidden);
    }

    static void clearCache(Context context) {
        cacheFile(context).delete();
    }

    private static String cubPath(FeedMode mode, String cat) {
        return switch (mode) {
            case NOW -> "/?sort=now_playing&cat=" + cat + "&page=1";
            case NEW -> "/?sort=latest&cat=" + cat + "&page=1";
            case NEW_4K -> cat.equals("movie") ? "/?cat=movie&sort=latest&uhd=true&page=1" : "/?sort=latest&cat=tv&page=1";
            case HOT -> "/top/fire/" + cat;
            case TOP -> "/top/hundred/" + cat;
        };
    }

    static String cubLampaUrl(Config config) {
        String cat = config.kind == FeedKind.TV ? "tv" : "movie";
        String path = cubPath(config.mode, cat);
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static List<FeedItem> cub(Prefs prefs, Config config) throws Exception {
        List<String> bases = new ArrayList<>();
        for (String d : CUB_DOMAINS) bases.add("https://tmdb." + d);
        String img = imageHost(prefs);
        return byKind(config.kind, cat -> {
            JSONArray arr = new JSONObject(Http.getFirst(bases, cubPath(config.mode, cat))).getJSONArray("results");
            return parseTmdbList(arr, "cub", img, null, null);
        });
    }

    private static List<FeedItem> tmdb(Context context, Prefs prefs, Config config) throws Exception {
        String key = prefs.getTmdbKey();
        if (key.trim().isEmpty()) throw new IllegalStateException(context.getString(R.string.tmdb_key_missing));
        List<String> bases = new ArrayList<>();
        if (!prefs.getTmdbApiHost().trim().isEmpty()) bases.add(prefs.getTmdbApiHost().replaceAll("/+$", ""));
        bases.addAll(TMDB_API);
        String img = imageHost(prefs);
        // The app's own language setting is just "ru" or "en"; TMDB wants a region too.
        ULocale locale = ULocale.addLikelySubtags(ULocale.forLocale(Locale.getDefault()));
        String q = "api_key=" + Http.enc(key) + "&language=" + locale.getLanguage() + "-" + locale.getCountry()
            + "&region=" + locale.getCountry();
        // TMDB returns the original title when it has no translation; English beats an unreadable script.
        boolean[] englishFailed = {false};
        Retitle english = (isTv, id) -> {
            // Each failed lookup waits on every mirror's timeout; one failure is enough for this fetch.
            if (englishFailed[0]) return null;
            try {
                return FeedItem.optStringOrNull(new JSONObject(Http.getFirst(bases,
                    "/" + (isTv ? "tv/" : "movie/") + id + "?api_key=" + Http.enc(key) + "&language=en-US")), isTv ? "name" : "title");
            } catch (Exception e) {
                englishFailed[0] = true;
                throw e;
            }
        };
        String fresh = LocalDate.now().minusDays(120).toString();
        String today = LocalDate.now().toString();
        return byKind(config.kind, cat -> {
            String path;
            if (config.mode == FeedMode.NEW) {
                path = cat.equals("movie")
                    ? "/discover/movie?" + q + "&sort_by=popularity.desc&vote_count.gte=20" +
                        "&primary_release_date.gte=" + fresh + "&primary_release_date.lte=" + today
                    : "/discover/tv?" + q + "&sort_by=popularity.desc&vote_count.gte=10&first_air_date.gte=" + fresh +
                        "&first_air_date.lte=" + today;
            } else {
                path = "/trending/" + cat + "/week?" + q;
            }
            JSONArray arr = new JSONObject(Http.getFirst(bases, path)).getJSONArray("results");
            return parseTmdbList(arr, "tmdb", img, cat.equals("tv"), english);
        });
    }

    private static String imageHost(Prefs prefs) {
        String host = prefs.getTmdbImageHost();
        return (host.trim().isEmpty() ? TMDB_IMG : host).replaceAll("/+$", "");
    }

    private static List<FeedItem> parseTmdbList(JSONArray arr, String source, String img, Boolean forceTv, Retitle english) throws Exception {
        List<FeedItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            boolean isTv = forceTv != null
                ? forceTv
                : o.optString("media_type").equals("tv") || (!o.has("title") || o.isNull("title")) && o.has("name");
            String title = isTv ? FeedItem.optStringOrNull(o, "name") : FeedItem.optStringOrNull(o, "title");
            if (title == null) title = FeedItem.optStringOrNull(o, "title");
            if (title == null) title = FeedItem.optStringOrNull(o, "name");
            if (title == null) continue;
            long id = o.getLong("id");
            String original = FeedItem.optStringOrNull(o, isTv ? "original_name" : "original_title");
            String lang = o.optString("original_language");
            if (english != null && title.equals(original) && !lang.equals("en")
                    && !lang.equals(Locale.getDefault().getLanguage())) {
                try {
                    String en = english.title(isTv, id);
                    if (en != null && !en.isEmpty()) title = en;
                } catch (Exception ignored) {
                }
            }
            String date = FeedItem.optStringOrNull(o, "release_date");
            if (date == null) date = FeedItem.optStringOrNull(o, "first_air_date");
            if (date == null) date = "";
            String backdrop = FeedItem.optStringOrNull(o, "backdrop_path");
            String poster = FeedItem.optStringOrNull(o, "poster_path");
            out.add(new FeedItem(
                source + ":" + (isTv ? "tv" : "movie") + ":" + id,
                title,
                date.substring(0, Math.min(4, date.length())),
                isTv,
                backdrop != null ? img + "/t/p/w780" + backdrop : null,
                poster != null ? img + "/t/p/w342" + poster : null,
                id,
                null
            ));
        }
        return out;
    }

    private static List<FeedItem> kinopoisk(Context context, Prefs prefs, Config config) throws Exception {
        String key = prefs.getKpKey();
        if (key.trim().isEmpty()) throw new IllegalStateException(context.getString(R.string.kp_key_missing));
        int year = LocalDate.now().getYear();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        String filter = config.mode == FeedMode.NEW
            ? "premiere.digital=" + LocalDate.now().minusDays(90).format(fmt) + "-" + LocalDate.now().format(fmt)
            : "year=" + (year - 1) + "-" + year;
        String kind = switch (config.kind) {
            case MOVIE -> "&isSeries=false";
            case TV -> "&isSeries=true";
            case BOTH -> "";
        };
        StringBuilder fields = new StringBuilder();
        for (String f : List.of("id", "name", "alternativeName", "year", "isSeries", "poster", "backdrop", "externalId")) {
            fields.append("&selectFields=").append(f);
        }
        String url = KP_API + "?limit=30&" + filter + kind + fields + "&notNullFields=poster.url&sortField=votes.kp&sortType=-1";
        JSONArray docs = new JSONObject(Http.get(url, Map.of("X-API-KEY", key))).getJSONArray("docs");
        List<FeedItem> out = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject o = docs.getJSONObject(i);
            String title = FeedItem.optStringOrNull(o, "name");
            if (title == null) title = FeedItem.optStringOrNull(o, "alternativeName");
            if (title == null) continue;
            boolean isTv = o.optBoolean("isSeries");
            JSONObject ext = o.optJSONObject("externalId");
            JSONObject backdrop = o.optJSONObject("backdrop");
            JSONObject posterObj = o.optJSONObject("poster");
            String poster = null;
            if (posterObj != null) {
                poster = FeedItem.optStringOrNull(posterObj, "previewUrl");
                if (poster == null) poster = FeedItem.optStringOrNull(posterObj, "url");
            }
            out.add(new FeedItem(
                "kp:" + (isTv ? "tv" : "movie") + ":" + o.getLong("id"),
                title,
                o.optString("year"),
                isTv,
                backdrop != null ? FeedItem.optStringOrNull(backdrop, "url") : null,
                poster,
                ext != null && ext.has("tmdb") && !ext.isNull("tmdb") ? Long.valueOf(ext.optLong("tmdb")) : null,
                ext != null ? FeedItem.optStringOrNull(ext, "kpHD") : null
            ));
        }
        return out;
    }

    private static List<FeedItem> byKind(FeedKind kind, Loader load) throws Exception {
        switch (kind) {
            case MOVIE:
                return load.load("movie");
            case TV:
                return load.load("tv");
            default:
                List<FeedItem> movies = List.of();
                List<FeedItem> shows = List.of();
                Exception moviesError = null;
                Exception showsError = null;
                try {
                    movies = load.load("movie");
                } catch (Exception e) {
                    moviesError = e;
                }
                try {
                    shows = load.load("tv");
                } catch (Exception e) {
                    showsError = e;
                }
                if (moviesError != null && showsError != null) throw moviesError;
                return interleave(movies, shows);
        }
    }

    private static <T> List<T> interleave(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a.size() + b.size());
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }
}
