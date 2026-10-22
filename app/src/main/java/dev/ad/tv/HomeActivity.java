package dev.ad.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
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

    private FrameLayout root;
    private TextView clock;
    private TextView date;
    private View weatherBox;
    private FavoritesRow favList;
    private View feedBox;
    private FeedRow feedList;
    private TextView feedCaption;
    private TextView topLabel;
    private ImageButton btnInputs;

    private VideoBackground video;
    private ActivityCursor cursor;

    private int appliedRevision = -1;
    private boolean weatherLoading;
    private boolean feedLoading;
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
            maybeRefreshWeather(false);
            maybeRefreshFeed(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (CrashWatchdog.tripped(this)) {
            startActivity(new Intent(this, RescueActivity.class));
        }
        prefs = Prefs.get(this);
        setContentView(R.layout.activity_home);

        root = findViewById(R.id.root);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weatherBox = findViewById(R.id.weatherBox);
        favList = findViewById(R.id.favList);
        feedBox = findViewById(R.id.feedBox);
        feedList = findViewById(R.id.feedList);
        feedCaption = findViewById(R.id.feedCaption);
        topLabel = findViewById(R.id.topLabel);
        btnInputs = findViewById(R.id.btnInputs);
        video = new VideoBackground(findViewById(R.id.bgVideo));

        favList.setCallbacks(this::onFavoriteClick, this::onFavoriteLongClick);
        feedList.setCallbacks(this::onFeedClick, this::onFeedLongClick, this::onFeedSelected);

        setupTopButton(btnInputs, this::showInputs);
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
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, filter);
        updateClock();
        if (appliedRevision != -1) applySettings();
        video.start(prefs.getBackgroundVideo());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read icons/banners too: apps may have been installed or removed while away.
        if (appliedRevision != -1) favList.submit(prefs.getFavorites());
        root.post(this::focusRecentApp);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(tick);
        video.stop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: jump back to the favorites.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            feedList.select(0);
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
        if (cursor == null) cursor = new ActivityCursor(this);
        if (cursor.onKey(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }

    private void applySettings() {
        if (appliedRevision == prefs.getRevision()) return;
        appliedRevision = prefs.getRevision();
        root.setBackground(Backgrounds.drawable(prefs.getBackgroundPreset()));
        applyTileSize();
        favList.submit(prefs.getFavorites());
        btnInputs.setVisibility(Inputs.list(this).isEmpty() ? View.GONE : View.VISIBLE);

        boolean feedOn = prefs.isFeedEnabled();
        feedBox.setVisibility(feedOn ? View.VISIBLE : View.GONE);
        applyLayoutMode(feedOn);

        showCachedWeather();
        maybeRefreshWeather(true);

        FeedRepo.Config config = FeedRepo.Config.from(prefs);
        if (feedOn) {
            if (feedConfig == null || !feedConfig.id().equals(config.id())) feedItems = Collections.emptyList();
            feedConfig = config;
            FeedRepo.Cached cached = FeedRepo.cached(this, config);
            if (cached != null) showFeed(cached.items);
            maybeRefreshFeed(feedItems.isEmpty());
            if (!feedItems.isEmpty()) showFeed(feedItems);
        }
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
        findViewById(R.id.favPanel).setPadding(pad, pad, pad, pad);
    }

    private void applyLayoutMode(boolean feedOn) {
        clock.setTextSize(feedOn ? 76f : 132f);
        date.setTextSize(feedOn ? 18f : 24f);
        Space spacer = findViewById(R.id.headerSpacer);
        spacer.setVisibility(feedOn ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) spacer.getLayoutParams()).weight = feedOn ? 0f : 0.6f;
        spacer.requestLayout();
    }

    private void updateClock() {
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(LocalTime.now().format(DateTimeFormatter.ofPattern(pattern)));
        Locale locale = Locale.getDefault();
        date.setText(capitalize(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)), locale));
    }

    private static String capitalize(String s, Locale locale) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    private void showCachedWeather() {
        Weather w = WeatherRepo.cached(this, prefs.getCityLat(), prefs.getCityLon());
        if (w != null) showWeather(w);
    }

    private void maybeRefreshWeather(boolean force) {
        long now = System.currentTimeMillis();
        if (weatherLoading) return;
        if (!force && now - weatherFetchedAt < WeatherRepo.REFRESH_MS) return;
        if (!force && now - weatherFailedAt < 5 * 60_000) return;
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

        LinearLayout row = findViewById(R.id.forecast);
        row.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        Locale locale = Locale.getDefault();
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

    private void maybeRefreshFeed(boolean force) {
        if (!prefs.isFeedEnabled()) return;
        FeedRepo.Config config = feedConfig;
        if (config == null) return;
        long now = System.currentTimeMillis();
        if (feedLoading) return;
        FeedRepo.Cached cached = FeedRepo.cached(this, config);
        feedFetchedAt = Math.max(feedFetchedAt, cached != null ? cached.fetchedAt : 0L);
        if (!force && now - feedFetchedAt < FeedRepo.REFRESH_MS) return;
        if (now - feedFailedAt < 10 * 60_000 && !force) return;
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
                if (failure != null) {
                    feedFailedAt = System.currentTimeMillis();
                    String message = failure.getMessage();
                    if (feedItems.isEmpty()) feedCaption.setText(getString(R.string.feed_failed, message != null ? message : ""));
                    return;
                }
                feedFetchedAt = System.currentTimeMillis();
                if (feedConfig != null && feedConfig.id().equals(config.id())) showFeed(items);
            });
        });
    }

    private void showFeed(List<FeedItem> items) {
        feedItems = items;
        FeedRepo.Config config = feedConfig;
        boolean more = config != null && config.source == FeedSource.CUB && OpenIn.LAMPA.name().equals(prefs.getOpenIn());
        FeedItem focused = feedList.selectedItem();
        feedList.submit(FeedRepo.visible(this, items), more);
        if (focused != null) feedList.selectKey(focused.key);
        onFeedSelected(feedList.selectedItem());
    }

    private void onFeedSelected(FeedItem item) {
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
        if (item == null) {
            if (feedConfig != null) {
                FeedOpener.openLampaList(this, getString(R.string.app_name), FeedRepo.cubLampaUrl(feedConfig));
            }
            return;
        }
        FeedOpener.open(this, item, OpenIn.valueOf(prefs.getOpenIn()));
    }

    private void onFeedLongClick(FeedItem item) {
        OpenIn[] targets = OpenIn.values();
        String[] labels = new String[targets.length + 1];
        for (int i = 0; i < targets.length; i++) {
            labels[i] = getString(R.string.open_in, FeedOpener.appName(this, targets[i]));
        }
        labels[targets.length] = getString(R.string.hide);
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(labels, (dialog, which) -> {
                    if (which < targets.length) {
                        FeedOpener.open(this, item, targets[which]);
                        return;
                    }
                    FeedRepo.hide(this, item);
                    showFeed(feedItems);
                    feedList.focusSelected();
                    Toast.makeText(this, R.string.hidden_toast, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void onFavoriteClick(String pkg) {
        if (pkg == null) {
            startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_PAGE, "favorites"));
        } else {
            Apps.launch(this, pkg);
        }
    }

    private void onFavoriteLongClick(String pkg) {
        String[] items = {getString(R.string.remove_favorite), getString(R.string.app_info)};
        new AlertDialog.Builder(this)
                .setTitle(Apps.label(this, pkg))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        List<String> favorites = new ArrayList<>(prefs.getFavorites());
                        favorites.remove(pkg);
                        prefs.setFavorites(favorites);
                        prefs.touch();
                        applySettings();
                    } else if (which == 1) {
                        Apps.openAppInfo(this, pkg);
                    }
                })
                .show();
    }

    private void focusRecentApp() {
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
