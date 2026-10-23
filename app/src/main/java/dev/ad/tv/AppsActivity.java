package dev.ad.tv;

import android.app.Activity;
import android.content.Context;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppsActivity extends Activity {
    private static final int COLUMNS = 5;

    private LinearLayout grid;
    private ActivityCursor cursor;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return cursor.onKey(event) || super.dispatchKeyEvent(event);
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        getWindow().getDecorView().setBackground(Backgrounds.drawable(Prefs.get(this).getBackgroundPreset()));
        grid = findViewById(R.id.grid);
        cursor = new ActivityCursor(this);
    }

    @Override protected void onResume() {
        super.onResume();
        String focusedPkg = grid.findFocus() != null ? (String) grid.findFocus().getTag() : null;
        render(Apps.installed(this));
        View target = focusedPkg != null ? grid.findViewWithTag(focusedPkg) : null;
        if (target == null) target = firstTile();
        if (target != null) target.requestFocus();
    }

    private View firstTile() {
        if (grid.getChildCount() == 0) return null;
        ViewGroup row = (ViewGroup) grid.getChildAt(0);
        return row.getChildCount() > 0 ? row.getChildAt(0) : null;
    }

    private void render(List<AppEntry> apps) {
        grid.removeAllViews();
        int gap = Views.dp(grid, 7);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setWeightSum(COLUMNS);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View tile = tile(apps.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Views.dp(grid, 90), 1f);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(tile, lp);
        }
    }

    private View tile(AppEntry app) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_favorite, grid, false);
        View clip = v.findViewById(R.id.tileClip);
        clip.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        clip.setClipToOutline(true);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
            view.setTranslationZ(has ? 8f : 0f);
        });
        ImageView bannerView = v.findViewById(R.id.banner);
        ImageView icon = v.findViewById(R.id.icon);
        TextView label = v.findViewById(R.id.label);
        View fallback = v.findViewById(R.id.fallback);
        Drawable banner = Apps.banner(this, app.pkg);
        bannerView.setVisibility(banner != null ? View.VISIBLE : View.GONE);
        fallback.setVisibility(banner != null ? View.GONE : View.VISIBLE);
        if (banner != null) {
            bannerView.setImageDrawable(banner);
        } else {
            icon.setImageDrawable(Apps.icon(this, app.pkg));
            label.setText(app.label);
        }
        v.setTag(app.pkg);
        v.setContentDescription(app.label);
        v.setOnClickListener(view -> {
            if (Apps.launch(this, app.pkg)) finish();
        });
        v.setOnLongClickListener(view -> {
            showMenu(app);
            return true;
        });
        Views.mouseFriendly(v, true, () -> showMenu(app));
        return v;
    }

    private void showMenu(AppEntry app) {
        Prefs prefs = Prefs.get(this);
        boolean isFav = prefs.getFavorites().contains(app.pkg);
        String[] items = {
            getString(isFav ? R.string.remove_favorite : R.string.add_favorite),
            getString(R.string.app_info),
        };
        new AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    List<String> favorites = new ArrayList<>(prefs.getFavorites());
                    if (isFav) favorites.remove(app.pkg);
                    else favorites.add(app.pkg);
                    prefs.setFavorites(favorites);
                    prefs.touch();
                } else {
                    Apps.openAppInfo(this, app.pkg);
                }
            })
            .show();
    }
}
