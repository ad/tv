package dev.ad.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends Activity {
    private Prefs prefs;
    private String language;

    private FrameLayout root;
    private TextView clock;
    private TextView date;
    private View weatherBox;
    private View favPanel;
    private FavoritesRow favList;
    private View feedBox;
    private FeedRow feedList;
    private TextView feedCaption;
    private TextView topLabel;
    private ImageButton btnInputs;
    private ImageButton btnFeed;
    private View topBar;

    private VideoBackground video;
    private ActivityCursor cursor;

    private int appliedRevision = -1;
    private int seenAppChanges = -1;
    private boolean weatherLoading;
    private boolean feedLoading;
    private boolean screensaver;
    private boolean backDown;
    private boolean favoritesShown;
    private List<FeedItem> feedItems = Collections.emptyList();
    private FeedRepo.Config feedConfig;

    private long weatherFetchedAt;
    private long weatherFailedAt;
    private long feedFetchedAt;
    private long feedFailedAt;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
            maybeRefreshWeather();
            maybeRefreshFeed();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CrashWatchdog.tripped(this)) {
            startActivity(new Intent(this, RescueActivity.class));
        }
        prefs = Prefs.get(this);
        language = prefs.getLanguage();
        setContentView(R.layout.activity_home);

        root = findViewById(R.id.root);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weatherBox = findViewById(R.id.weatherBox);
        favPanel = findViewById(R.id.favPanel);
        favList = findViewById(R.id.favList);
        // Scrolled tiles are cut just inside the 1dp rim of glass.xml, along its 26dp corners.
        float rim = getResources().getDisplayMetrics().density;
        favPanel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(Math.round(rim), Math.round(rim), view.getWidth() - Math.round(rim), view.getHeight() - Math.round(rim), 25 * rim);
            }
        });
        favPanel.setClipToOutline(true);
        feedBox = findViewById(R.id.feedBox);
        feedList = findViewById(R.id.feedList);
        feedCaption = findViewById(R.id.feedCaption);
        topLabel = findViewById(R.id.topLabel);
        btnInputs = findViewById(R.id.btnInputs);
        btnFeed = findViewById(R.id.btnFeed);
        topBar = findViewById(R.id.topBar);
        video = new VideoBackground(findViewById(R.id.bgVideo));

        favList.setCallbacks(this::onFavoriteClick, this::onFavoriteLongClick);
        feedList.setCallbacks(this::onFeedClick, this::onFeedLongClick, this::onFeedSelected);

        setupTopButton(btnInputs, this::showInputs);
        setupTopButton(btnFeed, this::cycleFeed);
        setupTopButton(findViewById(R.id.btnApps), () -> startActivity(new Intent(this, AppsActivity.class)));
        setupTopButton(findViewById(R.id.btnAppSettings), () -> startActivity(new Intent(this, SettingsActivity.class)));
        setupTopButton(findViewById(R.id.btnTvSettings),
                () -> Apps.start(this, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        updateClock();
        // Network and package queries happen after the first frame.
        root.post(() -> {
            Apps.seedFavorites(this);
            applySettings();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!language.equals(prefs.getLanguage())) recreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, filter);
        updateClock();
        if (appliedRevision != -1) {
            applySettings();
            // Favorites added from Lampa or reordered by opening show up here, while the row is off screen.
            if (prefs.isFeedEnabled() && !MovieFavorites.list(this).isEmpty()) {
                showFeed(feedItems);
                if (favoritesShown) feedList.selectFirst();
            }
        }
        video.start(prefs.getBackgroundVideo());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedRevision != -1 && seenAppChanges != Apps.changes()) submitFavorites();
        root.post(this::focusRecentApp);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(tick);
        video.stop();
        setScreensaver(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: jump back to the favorites.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            setScreensaver(false);
            feedList.selectFirst();
            favList.scrollTo(0, 0);
            favList.post(() -> favList.focusTile(0));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Toggle on key up, so the matching up never reaches a focused view.
        if (screensaver) {
            if (event.getAction() == KeyEvent.ACTION_UP) setScreensaver(false);
            return true;
        }
        if (cursor == null) cursor = new ActivityCursor(this);
        if (cursor.onKey(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // An app that closed on BACK down hands its stray up to us.
            if (event.getAction() == KeyEvent.ACTION_DOWN) backDown = true;
            else if (backDown) setScreensaver(true);
            if (event.getAction() == KeyEvent.ACTION_UP) backDown = false;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Hidden views are only transparent: taps and cursor clicks must not reach them.
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!screensaver) return super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) setScreensaver(false);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return screensaver || super.dispatchGenericMotionEvent(event);
    }

    private void setScreensaver(boolean on) {
        screensaver = on;
        for (View v : new View[] {topBar, feedBox, favPanel}) {
            v.animate().alpha(on ? 0f : 1f).setDuration(300).start();
        }
    }

    private void applySettings() {
        if (appliedRevision == prefs.getRevision()) return;
        appliedRevision = prefs.getRevision();
        root.setBackground(Backgrounds.drawable(prefs.getBackgroundPreset()));
        applyTileSize();
        submitFavorites();
        btnInputs.setVisibility(Inputs.list(this).isEmpty() ? View.GONE : View.VISIBLE);
        applyFeed();
        // The last fetch may be for another city.
        weatherFetchedAt = 0;
        if (WeatherRepo.cached(this, prefs.getCityLat(), prefs.getCityLon()) == null) weatherFailedAt = 0;
        showCachedWeather();
        maybeRefreshWeather();
    }

    private void applyFeed() {
        boolean feedOn = prefs.isFeedEnabled();
        feedBox.setVisibility(feedOn ? View.VISIBLE : View.GONE);
        FeedRepo.Config config = FeedRepo.Config.from(prefs);
        if (feedOn) {
            boolean changed = feedConfig == null || !feedConfig.id().equals(config.id());
            feedConfig = config;
            if (changed) showFeed(Collections.emptyList());
            FeedRepo.Cached cached = FeedRepo.cached(this, config);
            if (cached != null) showFeed(cached.items);
            // A key, host or source change clears the cache: fetch now, whatever happened to the last fetch.
            if (changed || cached == null) {
                feedFetchedAt = 0;
                feedFailedAt = 0;
            }
            maybeRefreshFeed();
            if (!feedItems.isEmpty()) showFeed(feedItems);
        }
        String state = feedOn ? getString(config.kind.label) : getString(R.string.off);
        btnFeed.setContentDescription(getString(R.string.feed) + ": " + state);
        btnFeed.setAlpha(feedOn ? 1f : 0.5f);
    }

    private void submitFavorites() {
        seenAppChanges = Apps.changes();
        favList.submit(prefs.getFavorites());
        favPanel.setVisibility(favList.getPackages().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void applyTileSize() {
        TileSize size;
        try {
            size = TileSize.valueOf(prefs.getTileSize());
        } catch (IllegalArgumentException e) {
            size = TileSize.LARGE;
        }
        float d = getResources().getDisplayMetrics().density;
        favList.setTileSize(size);
        // 7dp of focus room above and below the tiles.
        favList.getLayoutParams().height = (int) ((size.height + 14) * d);
        favList.requestLayout();
        int pad = (int) (size.panelPadding * d);
        favPanel.setPadding(pad, pad, pad, pad);
    }

    private void cycleFeed() {
        FeedKind kind = FeedRepo.Config.from(prefs).kind;
        if (!prefs.isFeedEnabled()) {
            prefs.setFeedEnabled(true);
            prefs.setFeedKind(FeedKind.BOTH.name());
        } else if (kind.ordinal() == FeedKind.values().length - 1) {
            prefs.setFeedEnabled(false);
        } else {
            prefs.setFeedKind(FeedKind.values()[kind.ordinal() + 1].name());
        }
        applyFeed();
        topLabel.setText(btnFeed.getContentDescription());
    }

    private void updateClock() {
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(LocalTime.now().format(DateTimeFormatter.ofPattern(pattern)));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        date.setText(capitalize(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)), locale));
    }

    private static String capitalize(String s, Locale locale) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    private void showCachedWeather() {
        Weather w = WeatherRepo.cached(this, prefs.getCityLat(), prefs.getCityLon());
        if (w != null) showWeather(w);
    }

    private void maybeRefreshWeather() {
        long now = System.currentTimeMillis();
        if (weatherLoading) return;
        if (now - weatherFetchedAt < WeatherRepo.REFRESH_MS) return;
        if (now - weatherFailedAt < 5 * 60_000) return;
        double lat = prefs.getCityLat();
        double lon = prefs.getCityLon();
        weatherLoading = true;
        Async.io(() -> {
            Weather fetched;
            try {
                fetched = WeatherRepo.fetch(this, lat, lon);
            } catch (Exception e) {
                fetched = null;
            }
            Weather w = fetched;
            Async.main(() -> {
                weatherLoading = false;
                if (isDestroyed()) return;
                if (lat != prefs.getCityLat() || lon != prefs.getCityLon()) {
                    maybeRefreshWeather();
                    return;
                }
                if (w == null) {
                    weatherFailedAt = System.currentTimeMillis();
                    return;
                }
                weatherFetchedAt = w.fetchedAt;
                showWeather(w);
            });
        });
    }

    private void showWeather(Weather w) {
        weatherFetchedAt = Math.max(weatherFetchedAt, w.fetchedAt);
        weatherBox.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.weatherIcon)).setText(WeatherRepo.emoji(w.symbol));
        ((TextView) findViewById(R.id.weatherTemp)).setText(signed(w.temp) + "°");
        long age = (System.currentTimeMillis() - w.fetchedAt) / 3_600_000;
        String stale = age >= 3 ? " · " + getString(R.string.hours_ago, (int) age) : "";
        ((TextView) findViewById(R.id.weatherFeels)).setText(
                getString(R.string.feels_like, signed(w.feels) + "°", w.wind) + stale);
        ((TextView) findViewById(R.id.weatherToday)).setText(getString(
                R.string.today_range, signed(w.today.min) + "…" + signed(w.today.max) + "°", precip(w.today.precipMm)));

        FitRow row = findViewById(R.id.forecast);
        row.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        for (WeatherDay d : w.next) {
            View card = inflater.inflate(R.layout.item_forecast, row, false);
            ((TextView) card.findViewById(R.id.fDay)).setText(
                    capitalize(d.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale), locale));
            ((TextView) card.findViewById(R.id.fIcon)).setText(WeatherRepo.emoji(d.symbol));
            ((TextView) card.findViewById(R.id.fTemp)).setText(signed(d.min) + "…" + signed(d.max) + "°");
            row.addView(card);
        }
    }

    private String precip(double mm) {
        return mm < 0.1 ? getString(R.string.no_precip)
                : getString(R.string.precip_mm, String.format(Locale.getDefault(), "%.1f", mm));
    }

    private static String signed(int t) {
        return t > 0 ? "+" + t : t < 0 ? "−" + (-t) : "0";
    }

    private void maybeRefreshFeed() {
        if (!prefs.isFeedEnabled()) return;
        FeedRepo.Config config = feedConfig;
        if (config == null) return;
        long now = System.currentTimeMillis();
        if (feedLoading) return;
        FeedRepo.Cached cached = FeedRepo.cached(this, config);
        feedFetchedAt = Math.max(feedFetchedAt, cached != null ? cached.fetchedAt : 0L);
        if (now - feedFetchedAt < FeedRepo.REFRESH_MS) return;
        if (now - feedFailedAt < 10 * 60_000) return;
        feedLoading = true;
        Async.io(() -> {
            List<FeedItem> fetched = null;
            Exception error = null;
            try {
                fetched = FeedRepo.fetch(this, config);
            } catch (Exception e) {
                error = e;
            }
            List<FeedItem> items = fetched;
            Exception failure = error;
            Async.main(() -> {
                feedLoading = false;
                if (isDestroyed()) return;
                // A mode switch during this fetch was skipped by the feedLoading guard.
                if (feedConfig == null || !feedConfig.id().equals(config.id())) {
                    maybeRefreshFeed();
                    return;
                }
                if (failure != null) {
                    feedFailedAt = System.currentTimeMillis();
                    String message = failure.getMessage();
                    if (feedItems.isEmpty() && !favoritesShown) feedCaption.setText(getString(R.string.feed_failed, message != null ? message : ""));
                    return;
                }
                feedFetchedAt = System.currentTimeMillis();
                showFeed(items);
            });
        });
    }

    private void showFeed(List<FeedItem> items) {
        feedItems = items;
        List<FeedItem> favorites = MovieFavorites.list(this);
        favoritesShown = prefs.isFeedFavorites() && !favorites.isEmpty();
        List<FeedItem> shown;
        if (favoritesShown) {
            FeedKind kind = FeedRepo.Config.from(prefs).kind;
            shown = new ArrayList<>();
            for (FeedItem f : favorites) if (kind == FeedKind.BOTH || f.isTv == (kind == FeedKind.TV)) shown.add(f);
        } else {
            shown = FeedRepo.visible(this, items);
        }
        FeedRepo.Config config = feedConfig;
        boolean more = !favoritesShown && config != null && config.source == FeedSource.CUB
            && OpenIn.LAMPA.name().equals(prefs.getOpenIn());
        String switchLabel = favorites.isEmpty() ? null : getString(favoritesShown ? R.string.favorites : R.string.feed_list);
        FeedItem focused = feedList.selectedItem();
        feedList.submit(shown, more, switchLabel);
        if (focused != null) feedList.selectKey(focused.key);
        onFeedSelected(feedList.selectedItem());
    }

    private void onFeedSelected(FeedItem item) {
        if (item == FeedRow.SWITCH) {
            boolean empty = favoritesShown && feedList.getItemCount() == 1;
            boolean series = FeedRepo.Config.from(prefs).kind == FeedKind.TV;
            feedCaption.setText(empty ? getString(series ? R.string.no_favorite_series : R.string.no_favorite_movies)
                : getString(favoritesShown ? R.string.show_feed : R.string.show_favorites));
            return;
        }
        if (item == null) {
            feedCaption.setText(feedList.getItemCount() > 0 ? getString(R.string.feed_more_in_lampa) : "");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String s : List.of(item.title, item.year, getString(item.isTv ? R.string.kind_tv : R.string.kind_movie))) {
            if (!s.trim().isEmpty()) parts.add(s);
        }
        feedCaption.setText(String.join(" · ", parts));
    }

    private void onFeedClick(FeedItem item) {
        if (item == FeedRow.SWITCH) {
            prefs.setFeedFavorites(!favoritesShown);
            showFeed(feedItems);
            return;
        }
        if (item == null) {
            if (feedConfig != null) {
                FeedOpener.openLampaList(this, getString(R.string.app_name), FeedRepo.cubLampaUrl(feedConfig));
            }
            return;
        }
        openTitle(item, favoritesShown ? MovieFavorites.openIn(this, item) : OpenIn.parse(prefs.getOpenIn()), false);
    }

    private void openTitle(FeedItem item, OpenIn target, boolean remember) {
        if (FeedOpener.open(this, item, target)) MovieFavorites.opened(this, item, remember ? target : null);
    }

    private void onFeedLongClick(FeedItem item) {
        OpenIn[] targets = OpenIn.values();
        boolean saved = MovieFavorites.contains(this, item);
        List<String> labels = new ArrayList<>();
        for (OpenIn target : targets) labels.add(getString(R.string.open_in, FeedOpener.appName(this, target)));
        labels.add(getString(saved ? R.string.remove_favorite : R.string.add_favorite));
        if (!favoritesShown) labels.add(getString(R.string.hide));
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < targets.length) {
                        openTitle(item, targets[which], true);
                        return;
                    }
                    if (which == targets.length) {
                        if (saved) MovieFavorites.remove(this, item);
                        else MovieFavorites.add(this, item, OpenIn.parse(prefs.getOpenIn()));
                        // Otherwise the next first favorite would flip the row out of the feed.
                        if (MovieFavorites.list(this).isEmpty()) prefs.setFeedFavorites(false);
                        Toast.makeText(this, saved ? R.string.favorite_removed : R.string.favorite_added, Toast.LENGTH_SHORT).show();
                    } else {
                        FeedRepo.hide(this, item);
                        Toast.makeText(this, R.string.hidden_toast, Toast.LENGTH_SHORT).show();
                    }
                    showFeed(feedItems);
                    feedList.focusSelected();
                })
                .show();
    }

    private void onFavoriteClick(String pkg) {
        // Apps.launch adds RESET_TASK_IF_NEEDED, which with clearTaskOnLaunch would close our own activity at once.
        if (CameraActivity.isCamera(pkg)) startActivity(CameraActivity.intent(this, pkg));
        else Apps.launch(this, pkg);
    }

    private void onFavoriteLongClick(String pkg) {
        String[] items = CameraActivity.isCamera(pkg)
                ? new String[] {getString(R.string.open), getString(R.string.remove_favorite)}
                : new String[] {getString(R.string.open), getString(R.string.remove_favorite), getString(R.string.app_info)};
        new AlertDialog.Builder(this)
                .setTitle(Apps.label(this, pkg))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        onFavoriteClick(pkg);
                    } else if (which == 1) {
                        List<String> favorites = new ArrayList<>(prefs.getFavorites());
                        favorites.remove(pkg);
                        prefs.setFavorites(favorites);
                        prefs.touch();
                        applySettings();
                    } else if (which == 2) {
                        Apps.openAppInfo(this, pkg);
                    }
                })
                .show();
    }

    private void focusRecentApp() {
        if (favList.getPackages().isEmpty()) {
            feedList.focusSelected();
            return;
        }
        int index = Math.max(0, favList.getPackages().indexOf(RecentApp.last(this)));
        favList.focusTile(index);
    }

    private void setupTopButton(ImageButton button, Runnable action) {
        button.setOnClickListener(v -> action.run());
        Views.mouseFriendly(button);
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                topLabel.setText(v.getContentDescription());
                topLabel.setVisibility(View.VISIBLE);
            } else {
                v.post(() -> {
                    if (((View) v.getParent()).findFocus() == null) topLabel.setVisibility(View.INVISIBLE);
                });
            }
        });
    }

    private void showInputs() {
        List<TvInput> inputs = Inputs.list(this);
        if (inputs.isEmpty()) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[inputs.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = inputs.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(R.string.inputs)
                .setItems(labels, (dialog, which) -> Inputs.open(this, inputs.get(which)))
                .show();
    }
}
