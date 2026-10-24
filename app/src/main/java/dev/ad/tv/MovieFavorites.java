package dev.ad.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MovieFavorites {
    private MovieFavorites() {}

    static List<FeedItem> list(Context context) {
        List<FeedItem> out = new ArrayList<>();
        try {
            JSONArray arr = load(context);
            for (int i = 0; i < arr.length(); i++) out.add(FeedItem.fromJson(arr.getJSONObject(i)));
        } catch (JSONException e) {
            // Read on every return home: a throw here would crash-loop the launcher.
            return new ArrayList<>();
        }
        return out;
    }

    static boolean contains(Context context, FeedItem item) {
        return indexOf(list(context), item) >= 0;
    }

    static OpenIn openIn(Context context, FeedItem item) {
        String name = Prefs.get(context).getOpenIn();
        try {
            JSONArray arr = load(context);
            int i = indexOf(list(context), item);
            if (i >= 0) name = arr.getJSONObject(i).optString("open", name);
            return OpenIn.parse(name);
        } catch (JSONException e) {
            return OpenIn.LAMPA;
        }
    }

    static boolean add(Context context, FeedItem item, OpenIn openIn) {
        try {
            JSONArray arr = load(context);
            int i = indexOf(list(context), item);
            JSONObject entry;
            if (i >= 0) {
                JSONObject old = arr.getJSONObject(i);
                FeedItem o = FeedItem.fromJson(old);
                // The soap id lives in the key, and only Soap TV can open a title by it.
                boolean soap = item.key.startsWith("soap:");
                String key = soap ? item.key : o.key;
                entry = new FeedItem(key, o.title, o.year, o.isTv, o.backdrop, o.poster != null ? o.poster : item.poster,
                    o.tmdbId != null ? o.tmdbId : item.tmdbId, o.kpHd != null ? o.kpHd : item.kpHd)
                    .toJson().put("open", soap ? openIn.name() : old.optString("open", openIn.name()));
            } else {
                entry = item.toJson().put("open", openIn.name());
            }
            save(context, toFront(arr, i, entry));
            return i < 0;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static void opened(Context context, FeedItem item, OpenIn target) {
        try {
            JSONArray arr = load(context);
            int i = indexOf(list(context), item);
            if (i < 0) return;
            JSONObject entry = arr.getJSONObject(i);
            if (target != null) entry.put("open", target.name());
            save(context, toFront(arr, i, entry));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    static void remove(Context context, FeedItem item) {
        try {
            JSONArray arr = load(context);
            int i = indexOf(list(context), item);
            if (i < 0) return;
            arr.remove(i);
            save(context, arr);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // The same title reaches us from CUB, Kinopoisk, Lampa and Soap TV under different keys.
    static boolean same(FeedItem a, FeedItem b) {
        if (a.tmdbId != null && b.tmdbId != null) return a.tmdbId.equals(b.tmdbId) && a.isTv == b.isTv;
        if (a.kpHd != null && b.kpHd != null) return a.kpHd.equals(b.kpHd);
        if (a.key.equals(b.key)) return true;
        return a.isTv == b.isTv && !a.year.isEmpty() && a.year.equals(b.year) && norm(a.title).equals(norm(b.title));
    }

    static String norm(String title) {
        return title.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static int indexOf(List<FeedItem> items, FeedItem item) {
        for (int i = 0; i < items.size(); i++) if (same(items.get(i), item)) return i;
        return -1;
    }

    private static JSONArray load(Context context) throws JSONException {
        return new JSONArray(Prefs.get(context).getMovieFavorites());
    }

    private static void save(Context context, JSONArray arr) {
        Prefs.get(context).setMovieFavorites(arr.toString());
    }

    private static JSONArray toFront(JSONArray arr, int index, JSONObject entry) throws JSONException {
        JSONArray out = new JSONArray().put(entry);
        for (int i = 0; i < arr.length(); i++) if (i != index) out.put(arr.getJSONObject(i));
        return out;
    }
}
