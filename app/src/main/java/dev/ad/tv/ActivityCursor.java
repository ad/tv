package dev.ad.tv;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

final class ActivityCursor implements CursorHost {
    private final Activity activity;
    private final ViewGroup decor;
    private final CursorView view;
    private final CursorController controller;

    ActivityCursor(Activity activity) {
        this.activity = activity;
        decor = (ViewGroup) activity.getWindow().getDecorView();
        view = new CursorView(activity);
        view.setVisibility(View.GONE);
        decor.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setElevation(1000f);
        controller = new CursorController(view, this, Prefs.get(activity).getCursorSpeed());
    }

    boolean onKey(KeyEvent event) {
        Prefs prefs = Prefs.get(activity);
        // In "everywhere" mode CursorService handles keys before they reach us.
        if (!CursorMode.LAUNCHER.name().equals(prefs.getCursorMode())) {
            if (controller.isActive()) controller.show(false);
            return false;
        }
        if (event.getKeyCode() == prefs.getCursorKey()) {
            if (event.getAction() == KeyEvent.ACTION_UP) toggle();
            return true;
        }
        if (controller.isActive() && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            controller.show(false);
            return true;
        }
        if (controller.isActive() && event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return controller.onKey(event);
    }

    void toggle() {
        controller.show(!controller.isActive());
        view.bringToFront();
    }

    @Override
    public void hover(float x, float y) {
        decor.dispatchGenericMotionEvent(mouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, SystemClock.uptimeMillis(), null));
    }

    @Override
    public void click(float x, float y) {
        long now = SystemClock.uptimeMillis();
        decor.dispatchTouchEvent(mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, now, null));
        decor.dispatchTouchEvent(mouse(MotionEvent.ACTION_UP, x, y, 0, now, null));
    }

    @Override
    public void longClick(float x, float y) {
        // Synthetic right clicks need a hidden API, so find the long-clickable view under the cursor.
        View v = longClickable(decor, (int) x, (int) y);
        if (v != null) v.performLongClick();
    }

    private View longClickable(View v, int x, int y) {
        if (v == view || v.getVisibility() != View.VISIBLE) return null;
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null;
        if (v instanceof ViewGroup g) {
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View found = longClickable(g.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        return v.isLongClickable() ? v : null;
    }

    @Override
    public void scroll(float x, float y, int dx, int dy) {
        decor.dispatchGenericMotionEvent(mouse(MotionEvent.ACTION_SCROLL, x, y, 0, SystemClock.uptimeMillis(), new float[] {-dx, -dy}));
    }

    private static MotionEvent mouse(int action, float x, float y, int buttons, long down, float[] axes) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
        if (axes != null) {
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, axes[0]);
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, axes[1]);
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[] {props}, new MotionEvent.PointerCoords[] {coords},
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }
}
