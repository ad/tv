package dev.ad.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class SettingsActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    private static final int REQ_VIDEO = 1;

    private interface AdbTask {
        void run() throws IOException;
    }

    private Prefs prefs;
    private LinearLayout rows;
    private TextView title;
    private TextView hint;
    private final ArrayList<String> stack = new ArrayList<>();
    private String homeLog = "";
    private ActivityCursor cursor;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return cursor.onKey(event) || super.dispatchKeyEvent(event);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        setContentView(R.layout.activity_settings);
        getWindow().getDecorView().setBackground(Backgrounds.drawable(prefs.getBackgroundPreset()));
        rows = findViewById(R.id.rows);
        title = findViewById(R.id.pageTitle);
        hint = findViewById(R.id.pageHint);
        cursor = new ActivityCursor(this);
        stack.add("main");
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        if (page != null) stack.add(page);
        render(false);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            render(false);
        } else {
            super.onBackPressed();
        }
    }

    private String page() {
        return stack.get(stack.size() - 1);
    }

    private void open(String page) {
        stack.add(page);
        render(false);
    }

    private void render(boolean keepFocus) {
        int focused = -1;
        if (keepFocus) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                if (rows.getChildAt(i).hasFocus()) {
                    focused = i;
                    break;
                }
            }
        }
        rows.removeAllViews();
        hint.setText("");
        switch (page()) {
            case "main": mainPage(); break;
            case "background": backgroundPage(); break;
            case "favorites": favoritesPage(); break;
            case "feed": feedPage(); break;
            case "hidden": hiddenPage(); break;
            case "home": homePage(); break;
            case "cursor": cursorPage(); break;
            default: break;
        }
        View target = rows.getChildAt(focused >= 0 ? focused : 0);
        if (target == null) target = rows.getChildAt(0);
        if (target != null) target.requestFocus();
        if (!keepFocus) ((ScrollView) findViewById(R.id.scroll)).scrollTo(0, 0);
    }

    private void changed() {
        prefs.touch();
        render(true);
    }

    private void row(String title, Runnable onClick) {
        row(title, null, null, null, null, onClick);
    }

    private void row(String title, String value, Runnable onClick) {
        row(title, value, null, null, null, onClick);
    }

    private void row(String title, String value, String mark, Drawable icon, Runnable onLongClick, Runnable onClick) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_row, rows, false);
        ((TextView) v.findViewById(R.id.rowTitle)).setText(title);
        TextView valueView = v.findViewById(R.id.rowValue);
        valueView.setText(value);
        valueView.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) v.findViewById(R.id.rowMark)).setText(mark != null ? mark : "");
        ImageView iconView = v.findViewById(R.id.rowIcon);
        iconView.setImageDrawable(icon);
        iconView.setVisibility(icon != null ? View.VISIBLE : View.GONE);
        v.setOnClickListener(view -> onClick.run());
        if (onLongClick != null) {
            v.setOnLongClickListener(view -> {
                onLongClick.run();
                return true;
            });
        }
        Views.mouseFriendly(v, true, onLongClick);
        rows.addView(v);
    }

    private void section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(R.style.Text_Small);
        tv.setPadding(18, 18, 0, 8);
        rows.addView(tv);
    }

    private String on(boolean b) {
        return getString(b ? R.string.on : R.string.off);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String name, T fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void mainPage() {
        title.setText(R.string.settings_title);
        hint.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
        row(getString(R.string.background), backgroundName(), () -> open("background"));
        row(getString(R.string.city), prefs.getCityName(), this::askCity);
        int favorites = prefs.getFavorites().size();
        row(getString(R.string.favorites), getResources().getQuantityString(R.plurals.apps_count, favorites, favorites), () -> open("favorites"));
        TileSize tile = parse(TileSize.class, prefs.getTileSize(), TileSize.LARGE);
        row(getString(R.string.tile_size), tileName(tile), () ->
            choose(getString(R.string.tile_size), Arrays.asList(TileSize.values()), this::tileName, tile, t -> {
                prefs.setTileSize(t.name());
                changed();
            }));
        row(getString(R.string.feed), on(prefs.isFeedEnabled()), () -> open("feed"));
        row(getString(R.string.cursor), cursorModeName(cursorMode()), () -> open("cursor"));
        row(getString(R.string.home_screen), getString(HomeSwitch.isOurs(this) ? R.string.home_this : R.string.home_stock), () -> open("home"));
    }

    private String backgroundName() {
        String video = prefs.getBackgroundVideo();
        if (video == null) {
            int i = prefs.getBackgroundPreset();
            BgPreset p = i >= 0 && i < Backgrounds.PRESETS.size() ? Backgrounds.PRESETS.get(i) : Backgrounds.PRESETS.get(0);
            return getString(p.name);
        }
        return getString(R.string.video) + ": " + videoName(video);
    }

    private static String videoName(String uri) {
        String name = Uri.decode(uri.substring(uri.lastIndexOf('/') + 1));
        int q = name.indexOf('?');
        return q >= 0 ? name.substring(0, q) : name;
    }

    private void backgroundPage() {
        title.setText(R.string.background);
        hint.setText(R.string.background_hint);
        section(getString(R.string.colors));
        for (int i = 0; i < Backgrounds.PRESETS.size(); i++) {
            int index = i;
            boolean current = prefs.getBackgroundVideo() == null && prefs.getBackgroundPreset() == i;
            row(getString(Backgrounds.PRESETS.get(i).name), null, current ? "✓" : null, Backgrounds.drawable(i), null, () -> {
                prefs.setBackgroundPreset(index);
                prefs.setBackgroundVideo(null);
                getWindow().getDecorView().setBackground(Backgrounds.drawable(index));
                changed();
            });
        }
        section(getString(R.string.video));
        String error = prefs.getVideoError();
        if (error != null && prefs.getBackgroundVideo() != null) row(getString(R.string.video_failed), error, () -> {});
        for (String uri : Backgrounds.bundledVideos(this)) {
            row(videoName(uri), getString(R.string.video_bundled), uri.equals(prefs.getBackgroundVideo()) ? "✓" : null, null, null, () -> {
                prefs.setBackgroundVideo(uri);
                prefs.setVideoError(null);
                changed();
            });
        }
        row(getString(R.string.video_from_device), getString(R.string.video_from_device_hint), this::pickLocalVideo);
        String url = prefs.getLastVideoUrl();
        boolean urlCurrent = !isBlank(url) && url.equals(prefs.getBackgroundVideo());
        row(getString(R.string.video_from_url), isBlank(url) ? null : url, urlCurrent ? "✓" : null, null, null, () ->
            askText(getString(R.string.video_from_url), url, "https://…", text -> {
                if (!isBlank(text)) {
                    prefs.setLastVideoUrl(text);
                    prefs.setBackgroundVideo(text);
                    prefs.setVideoError(null);
                }
                changed();
            }));
        if (prefs.getBackgroundVideo() != null) {
            row(getString(R.string.video_off), () -> {
                prefs.setBackgroundVideo(null);
                prefs.setVideoError(null);
                changed();
            });
        }
    }

    private void favoritesPage() {
        title.setText(R.string.favorites);
        hint.setText(R.string.favorites_hint);
        List<String> favorites = prefs.getFavorites();
        if (!favorites.isEmpty()) section(getString(R.string.order));
        for (int i = 0; i < favorites.size(); i++) {
            String pkg = favorites.get(i);
            row((i + 1) + ". " + Apps.label(this, pkg), null, null, Apps.icon(this, pkg), null, () -> reorder(pkg));
        }
        section(getString(R.string.all_apps));
        for (AppEntry app : Apps.installed(this)) {
            boolean isOn = favorites.contains(app.pkg);
            row(app.label, null, isOn ? "✓" : null, Apps.icon(this, app.pkg), null, () -> {
                List<String> next = new ArrayList<>(favorites);
                if (isOn) next.remove(app.pkg);
                else next.add(app.pkg);
                prefs.setFavorites(next);
                changed();
            });
        }
    }

    private void reorder(String pkg) {
        String[] items = {getString(R.string.move_up), getString(R.string.move_down), getString(R.string.remove_favorite)};
        new AlertDialog.Builder(this).setTitle(Apps.label(this, pkg)).setItems(items, (d, which) -> {
            List<String> list = new ArrayList<>(prefs.getFavorites());
            int i = list.indexOf(pkg);
            if (which == 0 && i > 0) list.add(i - 1, list.remove(i));
            else if (which == 1 && i >= 0 && i < list.size() - 1) list.add(i + 1, list.remove(i));
            else if (which == 2 && i >= 0) list.remove(i);
            prefs.setFavorites(list);
            changed();
        }).show();
    }

    private void feedPage() {
        title.setText(R.string.feed);
        hint.setText(R.string.feed_hint);
        FeedSource source = FeedRepo.Config.from(prefs).source;
        row(getString(R.string.feed), on(prefs.isFeedEnabled()), () -> {
            prefs.setFeedEnabled(!prefs.isFeedEnabled());
            changed();
        });
        row(getString(R.string.feed_source), sourceName(source), () ->
            choose(getString(R.string.feed_source), Arrays.asList(FeedSource.values()), this::sourceName, source, s -> {
                prefs.setFeedSource(s.name());
                String mode = prefs.getFeedMode();
                if (s != FeedSource.CUB && !mode.equals(FeedMode.NOW.name()) && !mode.equals(FeedMode.NEW.name())) {
                    prefs.setFeedMode(FeedMode.NOW.name());
                }
                FeedRepo.clearCache(this);
                changed();
            }));
        List<FeedMode> modes = source == FeedSource.CUB ? Arrays.asList(FeedMode.values()) : Arrays.asList(FeedMode.NOW, FeedMode.NEW);
        FeedMode mode = FeedRepo.Config.from(prefs).mode;
        row(getString(R.string.feed_mode), modeName(mode, source), () ->
            choose(getString(R.string.feed_mode), modes, m -> modeName(m, source), mode, m -> {
                prefs.setFeedMode(m.name());
                changed();
            }));
        FeedKind kind = FeedRepo.Config.from(prefs).kind;
        row(getString(R.string.feed_kind), kindName(kind), () ->
            choose(getString(R.string.feed_kind), Arrays.asList(FeedKind.values()), this::kindName, kind, k -> {
                prefs.setFeedKind(k.name());
                changed();
            }));
        OpenIn openIn = parse(OpenIn.class, prefs.getOpenIn(), OpenIn.LAMPA);
        row(getString(R.string.open_in_setting), FeedOpener.appName(this, openIn), () ->
            choose(getString(R.string.open_in_setting), Arrays.asList(OpenIn.values()), o -> FeedOpener.appName(this, o), openIn, o -> {
                prefs.setOpenIn(o.name());
                changed();
            }));
        row(getString(R.string.hidden_items), Integer.toString(prefs.getHiddenFeedItems().size()), () -> open("hidden"));
        row(getString(R.string.feed_refresh), () -> {
            FeedRepo.clearCache(this);
            changed();
            Toast.makeText(this, R.string.feed_refresh_done, Toast.LENGTH_SHORT).show();
        });
        section(getString(R.string.keys));
        row(getString(R.string.tmdb_key), mask(prefs.getTmdbKey()), () ->
            askText(getString(R.string.tmdb_key), prefs.getTmdbKey(), "", text -> {
                prefs.setTmdbKey(text);
                FeedRepo.clearCache(this);
                changed();
            }));
        row(getString(R.string.kp_key), mask(prefs.getKpKey()), () ->
            askText(getString(R.string.kp_key), prefs.getKpKey(), "", text -> {
                prefs.setKpKey(text);
                FeedRepo.clearCache(this);
                changed();
            }));
        row(getString(R.string.tmdb_api_host), isBlank(prefs.getTmdbApiHost()) ? getString(R.string.default_value) : prefs.getTmdbApiHost(), () ->
            askText(getString(R.string.tmdb_api_host), prefs.getTmdbApiHost(), "https://apitmdb.example/3", text -> {
                prefs.setTmdbApiHost(text);
                FeedRepo.clearCache(this);
                changed();
            }));
        row(getString(R.string.tmdb_img_host), isBlank(prefs.getTmdbImageHost()) ? getString(R.string.default_value) : prefs.getTmdbImageHost(), () ->
            askText(getString(R.string.tmdb_img_host), prefs.getTmdbImageHost(), "https://imagetmdb.com", text -> {
                prefs.setTmdbImageHost(text);
                FeedRepo.clearCache(this);
                changed();
            }));
    }

    private void hiddenPage() {
        title.setText(R.string.hidden_items);
        hint.setText(R.string.hidden_hint);
        List<String> hidden = new ArrayList<>(prefs.getHiddenFeedItems());
        Collections.sort(hidden);
        if (hidden.isEmpty()) {
            row(getString(R.string.nothing_hidden), () -> {});
            return;
        }
        row(getString(R.string.unhide_all), () -> {
            prefs.setHiddenFeedItems(Collections.emptySet());
            changed();
        });
        for (String entry : hidden) {
            int tab = entry.indexOf('\t');
            row(tab >= 0 ? entry.substring(tab + 1) : entry, getString(R.string.unhide), () -> {
                Set<String> next = new HashSet<>(prefs.getHiddenFeedItems());
                next.remove(entry);
                prefs.setHiddenFeedItems(next);
                changed();
            });
        }
    }

    private void homePage() {
        title.setText(R.string.home_screen);
        boolean ours = HomeSwitch.isOurs(this);
        hint.setText(getString(R.string.home_hint) + (isBlank(homeLog) ? "" : "\n\n" + homeLog));
        row(getString(R.string.home_current), getString(ours ? R.string.home_this : R.string.home_stock), () -> {});
        row(getString(R.string.adb_pair), getString(HomeSwitch.isPaired(this) ? R.string.adb_paired : R.string.adb_pair_hint), this::startPairing);
        if (ours) {
            row(getString(R.string.home_use_stock), () -> runAdb(getString(R.string.home_use_stock), () -> HomeSwitch.useStockLauncher(this, this::log)));
        } else {
            row(getString(R.string.home_use_this), () -> runAdb(getString(R.string.home_use_this), () -> HomeSwitch.useThisLauncher(this, this::log)));
        }
        row(getString(R.string.usage_access), on(RecentApp.hasUsageAccess(this)), () ->
            runAdb(getString(R.string.usage_access), () -> HomeSwitch.grantPermissions(this, this::log)));
    }

    private CursorMode cursorMode() {
        return parse(CursorMode.class, prefs.getCursorMode(), CursorMode.LAUNCHER);
    }

    private String cursorModeName(CursorMode m) {
        switch (m) {
            case OFF: return getString(R.string.off);
            case EVERYWHERE: return getString(R.string.cursor_everywhere);
            default: return getString(R.string.cursor_launcher);
        }
    }

    private static String keyName(int code) {
        String name = KeyEvent.keyCodeToString(code);
        return name.startsWith("KEYCODE_") ? name.substring("KEYCODE_".length()) : name;
    }

    private void cursorPage() {
        title.setText(R.string.cursor);
        hint.setText(R.string.cursor_hint);
        CursorMode mode = cursorMode();
        row(getString(R.string.cursor_mode), cursorModeName(mode), () ->
            choose(getString(R.string.cursor_mode), Arrays.asList(CursorMode.values()), this::cursorModeName, mode, next -> {
                prefs.setCursorMode(next.name());
                setCursorService(next == CursorMode.EVERYWHERE);
                changed();
            }));
        if (mode == CursorMode.EVERYWHERE) {
            row(getString(R.string.cursor_service_label), on(CursorService.isEnabled(this)), () -> {
                setCursorService(!CursorService.isEnabled(this));
                changed();
            });
        }
        row(getString(R.string.cursor_key), keyName(prefs.getCursorKey()), this::captureCursorKey);
        List<Float> speeds = Arrays.asList(0.6f, 1f, 1.6f);
        int[] speedNames = {R.string.speed_slow, R.string.speed_normal, R.string.speed_fast};
        Float speed = currentSpeed(speeds);
        row(getString(R.string.cursor_speed), getString(speedNames[speeds.indexOf(speed)]), () ->
            choose(getString(R.string.cursor_speed), speeds, s -> getString(speedNames[speeds.indexOf(s)]), speed, s -> {
                prefs.setCursorSpeed(s);
                changed();
                Toast.makeText(this, R.string.cursor_speed_restart, Toast.LENGTH_SHORT).show();
            }));
    }

    private Float currentSpeed(List<Float> speeds) {
        float current = prefs.getCursorSpeed();
        Float speed = speeds.get(0);
        for (Float s : speeds) if (Math.abs(s - current) < Math.abs(speed - current)) speed = s;
        return speed;
    }

    private void setCursorService(boolean enabled) {
        if (HomeSwitch.canWriteSecureSettings(this)) {
            CursorService.setEnabled(this, enabled);
        } else if (enabled && !CursorService.isEnabled(this)) {
            Toast.makeText(this, R.string.cursor_enable_manually, Toast.LENGTH_LONG).show();
            Apps.start(this, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void captureCursorKey() {
        Set<Integer> navigation = Set.of(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.cursor_key)
            .setMessage(R.string.cursor_key_press)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnKeyListener((d, code, event) -> {
            if (navigation.contains(code)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                prefs.setCursorKey(code);
                d.dismiss();
                changed();
            }
            return true;
        });
        dialog.show();
    }

    private void log(String line) {
        runOnUiThread(() -> {
            List<String> lines = new ArrayList<>(Arrays.asList(homeLog.split("\n", -1)));
            List<String> kept = new ArrayList<>(lines.subList(Math.max(0, lines.size() - 6), lines.size()));
            kept.add(line);
            homeLog = String.join("\n", kept).trim();
            if (page().equals("home")) hint.setText(getString(R.string.home_hint) + "\n\n" + homeLog);
        });
    }

    private void startPairing() {
        Async.io(() -> {
            boolean open = HomeSwitch.isLegacyPortOpen();
            Async.main(() -> {
                if (isDestroyed()) return;
                if (open) {
                    runAdb(getString(R.string.adb_pair), () -> HomeSwitch.authorize(this));
                } else {
                    homeLog = getString(R.string.adb_port_closed);
                    if (page().equals("home")) render(true);
                }
            });
        });
    }

    private void runAdb(String what, AdbTask task) {
        homeLog = getString(R.string.working);
        render(true);
        Async.io(() -> {
            String result;
            try {
                task.run();
                result = getString(R.string.done);
            } catch (HomeSwitch.WifiOwnedException e) {
                result = getString(R.string.wifi_owned, String.join(", ", e.owners));
            } catch (Exception e) {
                result = what + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String message = result;
            Async.main(() -> {
                if (isDestroyed()) return;
                homeLog = message;
                prefs.touch();
                if (page().equals("home")) render(true);
            });
        });
    }

    private void askCity() {
        askText(getString(R.string.city), "", getString(R.string.city_hint), query -> {
            if (isBlank(query)) return;
            Async.io(() -> {
                List<City> found;
                try {
                    found = Geocoder.search(query);
                } catch (Exception e) {
                    String message = e.getMessage();
                    Async.main(() -> {
                        if (!isDestroyed()) Toast.makeText(this, getString(R.string.city_failed, message), Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                List<City> cities = found;
                Async.main(() -> {
                    if (isDestroyed()) return;
                    if (cities.isEmpty()) {
                        Toast.makeText(this, R.string.city_not_found, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] names = new String[cities.size()];
                    for (int i = 0; i < names.length; i++) names[i] = cities.get(i).details;
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.city)
                        .setItems(names, (d, which) -> {
                            City c = cities.get(which);
                            prefs.setCityName(c.name);
                            prefs.setCityLat(c.lat);
                            prefs.setCityLon(c.lon);
                            changed();
                        })
                        .show();
                });
            });
        });
    }

    private void askText(String title, String value, String hintText, Consumer<String> onDone) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hintText);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setSelection(edit.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(48, 16, 48, 0);
        box.addView(edit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> onDone.accept(edit.getText().toString().trim()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        edit.requestFocus();
    }

    private <T> void choose(String title, List<T> options, Function<T, String> label, T current, Consumer<T> onPick) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = label.apply(options.get(i));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, options.indexOf(current), (d, which) -> {
                d.dismiss();
                onPick.accept(options.get(which));
            })
            .show();
    }

    private String mask(String key) {
        if (isBlank(key)) return getString(R.string.not_set);
        return key.substring(0, Math.min(4, key.length())) + "…" + key.substring(Math.max(0, key.length() - 4));
    }

    private String sourceName(FeedSource s) {
        switch (s) {
            case TMDB: return getString(R.string.source_tmdb);
            case KINOPOISK: return getString(R.string.source_kp);
            default: return getString(R.string.source_cub);
        }
    }

    private String modeName(FeedMode m, FeedSource source) {
        switch (m) {
            case NEW: return getString(R.string.mode_new);
            case NEW_4K: return getString(R.string.mode_new_4k);
            case HOT: return getString(R.string.mode_hot);
            case TOP: return getString(R.string.mode_top);
            default: return getString(source == FeedSource.CUB ? R.string.mode_now : R.string.mode_trending);
        }
    }

    private String tileName(TileSize t) {
        switch (t) {
            case COMPACT: return getString(R.string.tile_compact);
            case MEDIUM: return getString(R.string.tile_medium);
            default: return getString(R.string.tile_large);
        }
    }

    private String kindName(FeedKind k) {
        switch (k) {
            case MOVIE: return getString(R.string.kind_movies);
            case TV: return getString(R.string.kind_series);
            default: return getString(R.string.kind_both);
        }
    }

    private void pickLocalVideo() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_MEDIA_VIDEO}, REQ_VIDEO);
            return;
        }
        List<String[]> videos = queryVideos();
        if (videos.isEmpty()) {
            Toast.makeText(this, R.string.no_local_videos, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[videos.size()];
        for (int i = 0; i < names.length; i++) names[i] = videos.get(i)[1];
        new AlertDialog.Builder(this)
            .setTitle(R.string.video_from_device)
            .setItems(names, (d, which) -> {
                prefs.setBackgroundVideo(videos.get(which)[0]);
                prefs.setVideoError(null);
                changed();
            })
            .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_VIDEO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pickLocalVideo();
    }

    private List<String[]> queryVideos() {
        List<String[]> out = new ArrayList<>();
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH};
        try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                String path = c.getString(2);
                out.add(new String[] {uri.toString(), (path != null ? path : "") + c.getString(1)});
            }
        }
        return out;
    }
}
