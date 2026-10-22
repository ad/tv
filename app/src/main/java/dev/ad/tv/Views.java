package dev.ad.tv;

import android.graphics.Outline;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

final class Views {
    private Views() {}

    static int dp(View v, int dp) {
        return (int) (dp * v.getResources().getDisplayMetrics().density);
    }

    static void mouseFriendly(View v) {
        mouseFriendly(v, true, null);
    }

    static void mouseFriendly(View v, boolean hoverFocus, Runnable onContextClick) {
        v.setOnHoverListener((view, e) -> {
            // Only fully visible views: focusing a partly hidden one scrolls the list under the pointer.
            if (hoverFocus && e.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER && !view.isFocused() && fullyVisible(view)) {
                view.requestFocus();
            }
            return false;
        });
        v.setOnContextClickListener(view -> {
            if (onContextClick != null) onContextClick.run();
            return onContextClick != null;
        });
    }

    private static boolean fullyVisible(View v) {
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.width() >= v.getWidth() && r.height() >= v.getHeight();
    }

    static void roundCorners(View v, int radiusDp) {
        float r = radiusDp * v.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }
}
