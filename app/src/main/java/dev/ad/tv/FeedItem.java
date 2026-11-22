package dev.ad.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class FeedItem {
    final String key;
    final String title;
    final String year;
    final boolean isTv;
    final String backdrop;
    final String poster;
    final Long tmdbId;
    final String kpHd;
    final String quality;

    FeedItem(String key, String title, String year, boolean isTv, String backdrop, String poster, Long tmdbId, String kpHd) {
        this(key, title, year, isTv, backdrop, poster, tmdbId, kpHd, null);
    }

    FeedItem(String key, String title, String year, boolean isTv, String backdrop, String poster, Long tmdbId, String kpHd,
             String quality) {
        this.key = key;
        this.title = title;
        this.year = year;
        this.isTv = isTv;
        this.backdrop = backdrop;
        this.poster = poster;
        this.tmdbId = tmdbId;
        this.kpHd = kpHd;
        this.quality = quality(quality);
    }

    FeedItem withQuality(String q) {
        return new FeedItem(key, title, year, isTv, backdrop, poster, tmdbId, kpHd, q);
    }

    // CUB sends "4K" but also "webdl", "ts"; the value may come from a Lampa URI, so keep it short and plain.
    static String quality(String raw) {
        if (raw == null || !raw.trim().matches("[A-Za-z0-9 ]{1,10}")) return null;
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("key", key).put("title", title).put("year", year).put("tv", isTv)
            .put("backdrop", backdrop).put("poster", poster).put("tmdb", tmdbId).put("kphd", kpHd).put("quality", quality);
    }

    static FeedItem fromJson(JSONObject o) throws JSONException {
        return new FeedItem(
            o.getString("key"), o.getString("title"), o.optString("year"),
            o.getBoolean("tv"), optStringOrNull(o, "backdrop"), optStringOrNull(o, "poster"),
            o.isNull("tmdb") ? null : o.optLong("tmdb"), optStringOrNull(o, "kphd"), optStringOrNull(o, "quality")
        );
    }

    static String optStringOrNull(JSONObject o, String name) {
        if (o.isNull(name)) return null;
        String s = o.optString(name);
        return s.trim().isEmpty() || s.equals("null") ? null : s;
    }
}
