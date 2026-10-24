package dev.ad.tv;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

final class FeedOpener {
    private FeedOpener() {}

    static boolean open(Context context, FeedItem item, OpenIn target) {
        if (!Apps.isInstalled(context, target.pkg)) {
            Toast.makeText(context, context.getString(R.string.install_app, appName(context, target)), Toast.LENGTH_LONG).show();
            return false;
        }
        boolean ok = switch (target) {
            case LAMPA -> openLampa(context, item);
            case KINOPOISK -> openKinopoisk(context, item);
            case NUM -> false;
            // The stock app has no search entry point, so this falls back to a plain launch.
            case SOAP4ALL -> search(context, target.pkg, item.title);
        };
        if (!ok) return Apps.launch(context, target.pkg);
        Prefs.get(context).setLastLaunchedPackage(target.pkg);
        return true;
    }

    static void openLampaList(Context context, String title, String url) {
        JSONObject activity;
        try {
            activity = new JSONObject().put("component", "category_full").put("source", "cub")
                .put("url", url).put("title", title).put("page", 1);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW).setPackage(OpenIn.LAMPA.pkg)
            .putExtra("continueWatch", true)
            .putExtra("lampaActivity", activity.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Apps.start(context, intent)) Prefs.get(context).setLastLaunchedPackage(OpenIn.LAMPA.pkg);
        else Apps.launch(context, OpenIn.LAMPA.pkg);
    }

    private static boolean openLampa(Context context, FeedItem item) {
        String pkg = OpenIn.LAMPA.pkg;
        if (item.tmdbId != null) {
            Uri uri = Uri.parse("https://www.themoviedb.org/" + (item.isTv ? "tv" : "movie") + "/" + item.tmdbId);
            if (Apps.start(context, new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                return true;
            }
        }
        return search(context, pkg, item.title);
    }

    private static boolean search(Context context, String pkg, String query) {
        return Apps.start(
            context,
            new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
    }

    private static boolean openKinopoisk(Context context, FeedItem item) {
        String hd = item.kpHd;
        if (hd == null) return false;
        String pkg = OpenIn.KINOPOISK.pkg;
        for (String uri : List.of("kpatv://film/" + hd, "https://hd.kinopoisk.ru/film/" + hd)) {
            if (Apps.start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                return true;
            }
        }
        return false;
    }

    static String appName(Context context, OpenIn target) {
        return switch (target) {
            case LAMPA -> "Lampa";
            case KINOPOISK -> context.getString(R.string.kinopoisk);
            case NUM -> "NUM";
            case SOAP4ALL -> "soap4all";
        };
    }
}
