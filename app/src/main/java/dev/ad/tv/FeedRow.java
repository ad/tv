package dev.ad.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class FeedRow extends HorizontalScrollView {
    private static final int WIDE = 300;
    private static final int NARROW = 113;
    // An overlay, not alpha, so a video background never shows through.
    private static final float DIM = 0.35f;

    private final LinearLayout strip;
    private Consumer<FeedItem> onClick;
    private Consumer<FeedItem> onLongClick;
    private Consumer<FeedItem> onSelected;
    private List<FeedItem> items = Collections.emptyList();
    private boolean more;
    private int selected;
    private boolean alignPending;
    private ValueAnimator animator;

    public FeedRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        // HorizontalScrollView makes itself focusable: an empty row would take focus.
        setFocusable(false);
        strip = new LinearLayout(context);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setCallbacks(Consumer<FeedItem> onClick, Consumer<FeedItem> onLongClick, Consumer<FeedItem> onSelected) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
        this.onSelected = onSelected;
    }

    void submit(List<FeedItem> list, boolean withMore) {
        items = list;
        more = withMore && !list.isEmpty();
        selected = Math.max(0, Math.min(selected, getItemCount() - 1));
        bindAll();
    }

    FeedItem selectedItem() {
        return item(selected);
    }

    int getItemCount() {
        return items.size() + (more ? 1 : 0);
    }

    void select(int position) {
        selected = position;
        bindAll();
    }

    void selectKey(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) {
                select(i);
                return;
            }
        }
    }

    void focusSelected() {
        if (selected < strip.getChildCount()) strip.getChildAt(selected).requestFocus();
    }

    private FeedItem item(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private void bindAll() {
        if (animator != null) animator.cancel();
        boolean hadFocus = strip.hasFocus();
        int n = getItemCount();
        while (strip.getChildCount() < n) strip.addView(newCard());
        // Before removing: a removed focused view hands focus to the first focusable in the window.
        if (hadFocus) focusSelected();
        while (strip.getChildCount() > n) strip.removeViewAt(strip.getChildCount() - 1);
        for (int i = 0; i < n; i++) bind(i);
        alignPending = true;
        requestLayout();
    }

    private View newCard() {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.item_feed, strip, false);
        View clip = v.findViewById(R.id.cardClip);
        Views.roundCorners(clip, 10);
        // Rounded outline on the root too, so the focus shadow follows the card's corners.
        v.setOutlineProvider(clip.getOutlineProvider());
        v.setOnClickListener(card -> {
            int pos = strip.indexOfChild(card);
            if (pos < 0) return;
            if (pos != selected) {
                animateSelection(selected, pos);
                onSelected.accept(item(pos));
                card.requestFocus();
            } else {
                onClick.accept(item(pos));
            }
        });
        v.setOnLongClickListener(card -> {
            FeedItem item = item(strip.indexOfChild(card));
            if (item != null) onLongClick.accept(item);
            return item != null;
        });
        v.setOnFocusChangeListener((card, hasFocus) -> {
            card.setPivotX(0);
            card.setPivotY(card.getHeight() / 2f);
            float scale = hasFocus ? 1.03f : 1f;
            card.animate().scaleX(scale).scaleY(scale)
                    .translationZ(hasFocus ? Views.dp(card, 12) : 0f).setDuration(150).start();
            int pos = strip.indexOfChild(card);
            if (!hasFocus || pos < 0) return;
            if (pos != selected) {
                card.post(() -> {
                    if (pos < getItemCount()) animateSelection(selected, pos);
                });
            }
            onSelected.accept(item(pos));
        });
        return v;
    }

    private void bind(int position) {
        View v = strip.getChildAt(position);
        FeedItem item = item(position);
        boolean wide = position == selected;
        v.getLayoutParams().width = Views.dp(this, wide ? WIDE : NARROW);
        v.requestLayout();
        dim(v, wide ? 0f : 1f);
        bindImage(v, item, wide);
        // No hover focus here: the selected card slides away, which would select the next one under the pointer.
        Views.mouseFriendly(v, false, item == null ? null : () -> onLongClick.accept(item));
    }

    private void bindImage(View card, FeedItem item, boolean wide) {
        ImageView image = card.findViewById(R.id.image);
        TextView title = card.findViewById(R.id.title);
        card.setContentDescription(item == null ? card.getContext().getString(R.string.more) : item.title);
        if (item == null) {
            image.setScaleType(ImageView.ScaleType.CENTER);
            image.setImageResource(R.drawable.ic_more);
            image.setTag(null);
            title.setText(R.string.more);
            title.setVisibility(View.VISIBLE);
            return;
        }
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String url = wide ? (item.backdrop != null ? item.backdrop : item.poster)
                : (item.poster != null ? item.poster : item.backdrop);
        Images.load(image, url);
        title.setText(item.title);
        title.setVisibility(url == null ? View.VISIBLE : View.GONE);
    }

    private static void dim(View card, float amount) {
        FrameLayout clip = card.findViewById(R.id.cardClip);
        ColorDrawable overlay;
        if (clip.getForeground() instanceof ColorDrawable d) {
            overlay = d;
        } else {
            overlay = new ColorDrawable(Color.BLACK);
            clip.setForeground(overlay);
        }
        overlay.setAlpha((int) (amount * DIM * 255));
    }

    // From layout params: during an animation the laid-out positions lag a frame behind.
    private int leftOf(int position) {
        int x = 0;
        for (int i = 0; i < position; i++) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) strip.getChildAt(i).getLayoutParams();
            x += lp.getMarginStart() + lp.width + lp.getMarginEnd();
        }
        return x + ((ViewGroup.MarginLayoutParams) strip.getChildAt(position).getLayoutParams()).getMarginStart();
    }

    private void animateSelection(int old, int next) {
        if (old == next) return;
        if (animator != null) animator.end();
        selected = next;
        int wide = Views.dp(this, WIDE);
        int narrow = Views.dp(this, NARROW);
        View oldCard = strip.getChildAt(old);
        View newCard = strip.getChildAt(next);
        int startOffset = leftOf(next) - getScrollX();
        bindImage(oldCard, item(old), false);
        bindImage(newCard, item(next), true);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            oldCard.getLayoutParams().width = (int) (wide + (narrow - wide) * f);
            newCard.getLayoutParams().width = (int) (narrow + (wide - narrow) * f);
            oldCard.requestLayout();
            newCard.requestLayout();
            dim(oldCard, f);
            dim(newCard, 1 - f);
            scrollTo(leftOf(next) - (int) (startOffset * (1 - f)), 0);
        });
        animator.start();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (alignPending && selected < strip.getChildCount()) {
            alignPending = false;
            scrollTo(leftOf(selected), 0);
        }
    }

    // Focus entering from another row lands on the wide card, not the geometrically nearest one.
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus() && selected < strip.getChildCount()) {
            View entry = strip.getChildAt(selected);
            if (entry.isFocusable()) {
                views.add(entry);
                return;
            }
        }
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    // The scroll view's own arrow handling would page-scroll at the row ends.
    @Override
    public boolean executeKeyEvent(KeyEvent event) {
        return false;
    }
}
