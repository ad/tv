package dev.ad.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class FavoritesRow extends HorizontalScrollView {
    private final LinearLayout strip;
    private Consumer<String> onClick;
    private Consumer<String> onLongClick;
    private List<String> packages = Collections.emptyList();
    private TileSize tileSize = TileSize.LARGE;
    private final Map<String, Bitmap> snapshots = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final Runnable refresh = this::refreshCameras;

    public FavoritesRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        strip = new LinearLayout(context);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The scroll view clips the strip to the strip's bounds, so the focus room must be inside the strip.
        strip.setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom());
        setPadding(0, 0, 0, 0);
    }

    void setCallbacks(Consumer<String> onClick, Consumer<String> onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
    }

    List<String> getPackages() {
        return packages;
    }

    void setTileSize(TileSize size) {
        if (tileSize != size) {
            tileSize = size;
            bindAll();
        }
    }

    void submit(List<String> favorites) {
        List<String> next = new ArrayList<>();
        for (String pkg : favorites) if (CameraActivity.isCamera(pkg) || Apps.isInstalled(getContext(), pkg)) next.add(pkg);
        packages = next;
        bindAll();
        removeCallbacks(refresh);
        post(refresh);
    }

    void focusTile(int index) {
        if (index < strip.getChildCount()) strip.getChildAt(index).requestFocus();
    }

    private void bindAll() {
        View focused = strip.findFocus();
        int focusedIndex = focused == null ? -1 : strip.indexOfChild(focused);
        int n = packages.size();
        while (strip.getChildCount() < n) strip.addView(newTile());
        if (focusedIndex >= n && n > 0) focusTile(n - 1);
        while (strip.getChildCount() > n) strip.removeViewAt(strip.getChildCount() - 1);
        for (int i = 0; i < n; i++) bind(strip.getChildAt(i), packages.get(i));
    }

    private View newTile() {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.item_favorite, strip, false);
        Views.roundCorners(v.findViewById(R.id.tileClip), 10);
        v.setOnFocusChangeListener((tile, hasFocus) -> {
            float scale = hasFocus ? 1.1f : 1f;
            tile.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            tile.setTranslationZ(hasFocus ? 8f : 0f);
        });
        return v;
    }

    private void bind(View tile, String pkg) {
        Context ctx = getContext();
        ImageView banner = tile.findViewById(R.id.banner);
        ImageView icon = tile.findViewById(R.id.icon);
        TextView label = tile.findViewById(R.id.label);
        View fallback = tile.findViewById(R.id.fallback);
        tile.getLayoutParams().width = Views.dp(tile, tileSize.width);
        tile.getLayoutParams().height = Views.dp(tile, tileSize.height);
        tile.requestLayout();
        boolean compact = tileSize == TileSize.COMPACT;
        icon.getLayoutParams().width = Views.dp(icon, compact ? 28 : 40);
        icon.getLayoutParams().height = icon.getLayoutParams().width;
        icon.requestLayout();
        label.setTextSize(compact ? 11f : 14f);
        Bitmap snapshot = snapshots.get(pkg);
        Drawable b = snapshot != null ? new BitmapDrawable(getResources(), snapshot) : Apps.banner(ctx, pkg);
        if (b != null) {
            banner.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            banner.setImageDrawable(b);
        } else {
            banner.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            icon.setImageDrawable(Apps.icon(ctx, pkg));
            label.setText(Apps.label(ctx, pkg));
        }
        tile.setContentDescription(Apps.label(ctx, pkg));
        tile.setOnClickListener(x -> onClick.accept(pkg));
        tile.setOnLongClickListener(x -> {
            onLongClick.accept(pkg);
            return true;
        });
        Views.mouseFriendly(tile, true, () -> onLongClick.accept(pkg));
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(refresh);
        if (visibility == VISIBLE) post(refresh);
    }

    // The screensaver hides the panel with alpha, which isShown() does not see.
    private boolean visible() {
        for (View v = this; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getVisibility() != VISIBLE || v.getAlpha() == 0f) return false;
        }
        return true;
    }

    private void refreshCameras() {
        Rect onScreen = new Rect();
        for (int i = 0; i < packages.size() && i < strip.getChildCount(); i++) {
            String pkg = packages.get(i);
            if (!CameraActivity.isCamera(pkg) || !visible() || !strip.getChildAt(i).getLocalVisibleRect(onScreen) || !loading.add(pkg)) continue;
            String url = CameraActivity.url(pkg);
            Async.io(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = Rtsp.snapshot(url, 480);
                } catch (Exception ignored) {
                }
                Bitmap result = bitmap;
                Async.main(() -> {
                    loading.remove(pkg);
                    if (result == null) return;
                    snapshots.put(pkg, result);
                    int index = packages.indexOf(pkg);
                    if (index >= 0 && index < strip.getChildCount()) bind(strip.getChildAt(index), pkg);
                });
            });
        }
        postDelayed(refresh, 60_000);
    }

    // Scroll far enough to show the strip's padding and the 7dp tile margin from item_favorite.xml,
    // so the 1.1x focus scale is not clipped at the edge.
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        Rect r = new Rect(rect);
        int margin = Views.dp(this, 7);
        r.left -= strip.getPaddingLeft() + margin;
        r.right += strip.getPaddingRight() + margin;
        return super.computeScrollDeltaToGetChildRectOnScreen(r);
    }
}
