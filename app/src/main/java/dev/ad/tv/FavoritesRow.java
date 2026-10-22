package dev.ad.tv;

import android.content.Context;
import android.graphics.Rect;
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
import java.util.List;
import java.util.function.Consumer;

public class FavoritesRow extends HorizontalScrollView {
    private final LinearLayout strip;
    private Consumer<String> onClick;
    private Consumer<String> onLongClick;
    private List<String> packages = Collections.emptyList();
    private TileSize tileSize = TileSize.LARGE;

    public FavoritesRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        strip = new LinearLayout(context);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        for (String pkg : favorites) if (Apps.isInstalled(getContext(), pkg)) next.add(pkg);
        packages = next;
        bindAll();
    }

    void focusTile(int index) {
        if (index < strip.getChildCount()) strip.getChildAt(index).requestFocus();
    }

    private void bindAll() {
        View focused = strip.findFocus();
        int focusedIndex = focused == null ? -1 : strip.indexOfChild(focused);
        int n = packages.isEmpty() ? 1 : packages.size();
        while (strip.getChildCount() < n) strip.addView(newTile());
        if (focusedIndex >= n) focusTile(n - 1);
        while (strip.getChildCount() > n) strip.removeViewAt(strip.getChildCount() - 1);
        for (int i = 0; i < n; i++) bind(strip.getChildAt(i), i < packages.size() ? packages.get(i) : null);
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
        if (pkg == null) {
            banner.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            icon.setImageResource(R.drawable.ic_apps);
            label.setText(R.string.add_favorites);
            tile.setContentDescription(null);
            tile.setOnClickListener(x -> onClick.accept(null));
            tile.setOnLongClickListener(null);
            Views.mouseFriendly(tile);
            return;
        }
        Drawable b = Apps.banner(ctx, pkg);
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

    // HorizontalScrollView ignores padding here; RecyclerView kept the focused tile inside it.
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        Rect r = new Rect(rect);
        r.left -= getPaddingLeft();
        r.right += getPaddingRight();
        return super.computeScrollDeltaToGetChildRectOnScreen(r);
    }
}
