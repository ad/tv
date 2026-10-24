package dev.ad.tv;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

// Any app can send this link, so images are TMDB paths only, never URLs.
public class FavAddActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeedItem item = parse(getIntent().getData(), FeedRepo.imageHost(Prefs.get(this)));
        int message = item == null ? R.string.favorite_invalid
            : MovieFavorites.add(this, item, OpenIn.LAMPA) ? R.string.favorite_added : R.string.favorite_exists;
        Toast.makeText(getApplicationContext(), getString(message), Toast.LENGTH_SHORT).show();
        finish();
    }

    static FeedItem parse(Uri uri, String imageHost) {
        if (uri == null || !"adtv".equals(uri.getScheme()) || !"fav".equals(uri.getHost()) || !"/add".equals(uri.getPath())) {
            return null;
        }
        String tmdb = uri.getQueryParameter("tmdb");
        String media = uri.getQueryParameter("media");
        String title = uri.getQueryParameter("title");
        if (tmdb == null || !tmdb.matches("[1-9][0-9]{0,9}")) return null;
        if (!"movie".equals(media) && !"tv".equals(media)) return null;
        if (title == null || title.trim().isEmpty()) return null;
        title = title.trim();
        if (title.length() > 200) title = title.substring(0, 200);
        String year = uri.getQueryParameter("year");
        String poster = tmdbImage(imageHost, "w342", uri.getQueryParameter("poster"));
        String backdrop = tmdbImage(imageHost, "w780", uri.getQueryParameter("backdrop"));
        return new FeedItem("tmdb:" + media + ":" + tmdb, title, year != null && year.matches("[0-9]{4}") ? year : "",
            media.equals("tv"), backdrop, poster, Long.parseLong(tmdb), null);
    }

    private static String tmdbImage(String host, String size, String path) {
        return path != null && path.matches("/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)") ? host + "/t/p/" + size + path : null;
    }
}
