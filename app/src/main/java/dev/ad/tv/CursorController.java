package dev.ad.tv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

final class CursorController {
    private final CursorView view;
    private final CursorHost host;
    private final float speed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float d;
    private int dirX = 0;
    private int dirY = 0;
    private long movingSince = 0L;
    private boolean longFired = false;
    private long lastScrollAt = 0L;
    private boolean active = false;

    CursorController(CursorView view, CursorHost host, float speed) {
        this.view = view;
        this.host = host;
        this.speed = speed;
        this.d = view.getResources().getDisplayMetrics().density;
    }

    boolean isActive() {
        return active;
    }

    void show(boolean active) {
        this.active = active;
        view.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) stopMoving();
        view.invalidate();
    }

    boolean onKey(KeyEvent event) {
        if (!active) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> setDir(down, -1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> setDir(down, 1, 0);
            case KeyEvent.KEYCODE_DPAD_UP -> setDir(down, 0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN -> setDir(down, 0, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ok(down, event.getRepeatCount());
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setDir(boolean down, int x, int y) {
        if (down) {
            if (x != 0) dirX = x;
            if (y != 0) dirY = y;
            if (movingSince == 0L) {
                movingSince = SystemClock.uptimeMillis();
                handler.post(step);
            }
        } else {
            if (x != 0 && dirX == x) dirX = 0;
            if (y != 0 && dirY == y) dirY = 0;
            if (dirX == 0 && dirY == 0) stopMoving();
        }
    }

    private void stopMoving() {
        dirX = 0;
        dirY = 0;
        movingSince = 0;
        handler.removeCallbacks(step);
    }

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (dirX == 0 && dirY == 0) return;
            float held = (SystemClock.uptimeMillis() - movingSince) / 1000f;
            float v = (3f + 25f * Math.min(held, 0.8f) / 0.8f) * d * speed;
            float w = view.getWidth();
            float h = view.getHeight();
            float nx = view.cx + dirX * v;
            float ny = view.cy + dirY * v;
            int edgeX = nx < 0 ? -1 : nx > w - 2 ? 1 : 0;
            int edgeY = ny < 0 ? -1 : ny > h - 2 ? 1 : 0;
            view.cx = Math.max(0f, Math.min(nx, w - 2));
            view.cy = Math.max(0f, Math.min(ny, h - 2));
            view.invalidate();
            host.hover(view.cx, view.cy);
            if ((edgeX != 0 || edgeY != 0) && SystemClock.uptimeMillis() - lastScrollAt > 120) {
                lastScrollAt = SystemClock.uptimeMillis();
                host.scroll(view.cx, view.cy, edgeX, edgeY);
            }
            view.postOnAnimation(this);
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            longFired = true;
            host.longClick(view.cx, view.cy);
        }
    };

    private void ok(boolean down, int repeat) {
        if (down && repeat == 0) {
            longFired = false;
            handler.postDelayed(longPress, 600);
        } else if (!down) {
            handler.removeCallbacks(longPress);
            if (!longFired) host.click(view.cx, view.cy);
        }
    }
}
