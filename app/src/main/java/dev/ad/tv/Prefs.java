package dev.ad.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    private static volatile Prefs instance;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static Prefs get(Context context) {
        Prefs p = instance;
        if (p != null) return p;
        synchronized (Prefs.class) {
            if (instance == null) instance = new Prefs(context.getApplicationContext());
            return instance;
        }
    }

    int getBackgroundPreset() {
        return sp.getInt("bg_preset", 0);
    }

    void setBackgroundPreset(int v) {
        sp.edit().putInt("bg_preset", v).apply();
    }

    String getBackgroundVideo() {
        String v = sp.getString("bg_video", null);
        // The bundled 4K copy is gone; a saved choice of it falls back to the 1080p one.
        if (v != null && v.startsWith("asset:///")) return v.replace("%204K.mp4", "%201080p.mp4");
        return v;
    }

    void setBackgroundVideo(String v) {
        sp.edit().putString("bg_video", v).apply();
    }

    String getVideoError() {
        return sp.getString("bg_video_error", null);
    }

    void setVideoError(String v) {
        sp.edit().putString("bg_video_error", v).apply();
    }

    String getLastVideoUrl() {
        String v = sp.getString("bg_video_url", "");
        return v != null ? v : "";
    }

    void setLastVideoUrl(String v) {
        sp.edit().putString("bg_video_url", v).apply();
    }

    String getCityName() {
        String v = sp.getString("city_name", "Москва");
        return v != null ? v : "Москва";
    }

    void setCityName(String v) {
        sp.edit().putString("city_name", v).apply();
    }

    double getCityLat() {
        return Double.parseDouble(sp.getString("city_lat", "55.7558"));
    }

    void setCityLat(double v) {
        sp.edit().putString("city_lat", Double.toString(v)).apply();
    }

    double getCityLon() {
        return Double.parseDouble(sp.getString("city_lon", "37.6173"));
    }

    void setCityLon(double v) {
        sp.edit().putString("city_lon", Double.toString(v)).apply();
    }

    List<String> getFavorites() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("favorites", "").split(",")) if (!s.trim().isEmpty()) out.add(s);
        return out;
    }

    void setFavorites(List<String> v) {
        sp.edit().putString("favorites", String.join(",", v)).apply();
    }

    String getTileSize() {
        return sp.getString("tile_size", TileSize.LARGE.name());
    }

    void setTileSize(String v) {
        sp.edit().putString("tile_size", v).apply();
    }

    String getCursorMode() {
        return sp.getString("cursor_mode", CursorMode.LAUNCHER.name());
    }

    void setCursorMode(String v) {
        sp.edit().putString("cursor_mode", v).apply();
    }

    // BOOKMARK is the star button on the Streamer remote.
    int getCursorKey() {
        return sp.getInt("cursor_key", KeyEvent.KEYCODE_BOOKMARK);
    }

    void setCursorKey(int v) {
        sp.edit().putInt("cursor_key", v).apply();
    }

    float getCursorSpeed() {
        return sp.getFloat("cursor_speed", 1f);
    }

    void setCursorSpeed(float v) {
        sp.edit().putFloat("cursor_speed", v).apply();
    }

    String getLastLaunchedPackage() {
        return sp.getString("last_pkg", null);
    }

    void setLastLaunchedPackage(String v) {
        sp.edit().putString("last_pkg", v).putLong("last_pkg_at", System.currentTimeMillis()).apply();
    }

    long getLastLaunchedAt() {
        return sp.getLong("last_pkg_at", 0);
    }

    boolean isFeedEnabled() {
        return sp.getBoolean("feed_on", true);
    }

    void setFeedEnabled(boolean v) {
        sp.edit().putBoolean("feed_on", v).apply();
    }

    String getFeedSource() {
        return sp.getString("feed_source", FeedSource.CUB.name());
    }

    void setFeedSource(String v) {
        sp.edit().putString("feed_source", v).apply();
    }

    String getFeedMode() {
        return sp.getString("feed_mode", FeedMode.NOW.name());
    }

    void setFeedMode(String v) {
        sp.edit().putString("feed_mode", v).apply();
    }

    String getFeedKind() {
        return sp.getString("feed_kind", FeedKind.BOTH.name());
    }

    void setFeedKind(String v) {
        sp.edit().putString("feed_kind", v).apply();
    }

    String getTmdbKey() {
        return sp.getString("tmdb_key", "");
    }

    void setTmdbKey(String v) {
        sp.edit().putString("tmdb_key", v.trim()).apply();
    }

    String getKpKey() {
        return sp.getString("kp_key", "");
    }

    void setKpKey(String v) {
        sp.edit().putString("kp_key", v.trim()).apply();
    }

    String getTmdbApiHost() {
        return sp.getString("tmdb_api_host", "");
    }

    void setTmdbApiHost(String v) {
        sp.edit().putString("tmdb_api_host", v.trim()).apply();
    }

    String getTmdbImageHost() {
        return sp.getString("tmdb_img_host", "");
    }

    void setTmdbImageHost(String v) {
        sp.edit().putString("tmdb_img_host", v.trim()).apply();
    }

    String getOpenIn() {
        return sp.getString("open_in", OpenIn.LAMPA.name());
    }

    void setOpenIn(String v) {
        sp.edit().putString("open_in", v).apply();
    }

    Set<String> getHiddenFeedItems() {
        return sp.getStringSet("feed_hidden", Set.of());
    }

    void setHiddenFeedItems(Set<String> v) {
        sp.edit().putStringSet("feed_hidden", new HashSet<>(v)).apply();
    }

    int getRevision() {
        return sp.getInt("revision", 0);
    }

    void touch() {
        sp.edit().putInt("revision", getRevision() + 1).apply();
    }
}
