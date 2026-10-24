package dev.ad.tv;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

// Any app can send this link, so images are TMDB paths or soap4 covers, never arbitrary URLs.
public class FavAddActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeedItem item = parse(getIntent().getData(), FeedRepo.imageHost(Prefs.get(this)));
        OpenIn target = item != null && item.key.startsWith("soap:") ? OpenIn.SOAP_TV : OpenIn.LAMPA;
        int message = item == null ? R.string.favorite_invalid
            : MovieFavorites.add(this, item, target) ? R.string.favorite_added : R.string.favorite_exists;
        Toast.makeText(getApplicationContext(), getString(message), Toast.LENGTH_SHORT).show();
        setResult(item == null ? RESULT_CANCELED : message == R.string.favorite_added ? RESULT_OK : RESULT_FIRST_USER);
        finish();
    }

    static FeedItem parse(Uri uri, String imageHost) {
        if (uri == null || !"adtv".equals(uri.getScheme()) || !"fav".equals(uri.getHost()) || !"/add".equals(uri.getPath())) {
            return null;
        }
        String tmdb = uri.getQueryParameter("tmdb");
        String id = tmdb != null ? tmdb : uri.getQueryParameter("soap");
        String media = uri.getQueryParameter("media");
        String title = uri.getQueryParameter("title");
        if (id == null || !id.matches("[1-9][0-9]{0,9}")) return null;
        if (!"movie".equals(media) && !"tv".equals(media)) return null;
        if (title == null || title.trim().isEmpty()) return null;
        title = title.trim();
        if (title.length() > 200) title = title.substring(0, 200);
        String year = uri.getQueryParameter("year");
        year = year != null && year.matches("[0-9]{4}") ? year : "";
        if (tmdb == null) {
            String poster = media.equals("tv") ? "https://soap4youand.me/assets/covers/soap/big/" + id + ".jpg"
                : soapCover(uri.getQueryParameter("poster"));
            return new FeedItem("soap:" + media + ":" + id, title, year, media.equals("tv"), null, poster, null, null);
        }
        String poster = tmdbImage(imageHost, "w342", uri.getQueryParameter("poster"));
        String backdrop = tmdbImage(imageHost, "w780", uri.getQueryParameter("backdrop"));
        return new FeedItem("tmdb:" + media + ":" + tmdb, title, year,
            media.equals("tv"), backdrop, poster, Long.parseLong(tmdb), null);
    }

    private static String soapCover(String url) {
        Uri u = url == null ? null : Uri.parse(url);
        boolean ok = u != null && "https".equals(u.getScheme()) && "covers.soap4uand.me".equals(u.getHost())
            && u.getEncodedPath() != null && u.getEncodedPath().matches("(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)+\\.(jpg|jpeg|png|webp)");
        return ok ? "https://covers.soap4uand.me" + u.getEncodedPath() : null;
    }

    private static String tmdbImage(String host, String size, String path) {
        return path != null && path.matches("/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)") ? host + "/t/p/" + size + path : null;
    }
}
