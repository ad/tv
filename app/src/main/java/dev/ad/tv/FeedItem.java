package dev.ad.tv;

import org.json.JSONException;
import org.json.JSONObject;

final class FeedItem {
    final String key;
    final String title;
    final String year;
    final boolean isTv;
    final String backdrop;
    final String poster;
    final Long tmdbId;
    final String kpHd;

    FeedItem(String key, String title, String year, boolean isTv, String backdrop, String poster, Long tmdbId, String kpHd) {
        this.key = key;
        this.title = title;
        this.year = year;
        this.isTv = isTv;
        this.backdrop = backdrop;
        this.poster = poster;
        this.tmdbId = tmdbId;
        this.kpHd = kpHd;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("key", key).put("title", title).put("year", year).put("tv", isTv)
            .put("backdrop", backdrop).put("poster", poster).put("tmdb", tmdbId).put("kphd", kpHd);
    }

    static FeedItem fromJson(JSONObject o) throws JSONException {
        return new FeedItem(
            o.getString("key"), o.getString("title"), o.optString("year"),
            o.getBoolean("tv"), optStringOrNull(o, "backdrop"), optStringOrNull(o, "poster"),
            o.isNull("tmdb") ? null : o.optLong("tmdb"), optStringOrNull(o, "kphd")
        );
    }

    static String optStringOrNull(JSONObject o, String name) {
        if (o.isNull(name)) return null;
        String s = o.optString(name);
        return s.trim().isEmpty() || s.equals("null") ? null : s;
    }
}
