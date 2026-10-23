package dev.ad.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CursorService extends AccessibilityService implements CursorHost {
    static volatile boolean running = false;

    private CursorView view;
    private CursorController controller;

    @Override
    public void onServiceConnected() {
        running = true;
        CursorView v = new CursorView(this);
        v.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        getSystemService(WindowManager.class).addView(v, lp);
        view = v;
        controller = new CursorController(v, this, prefs().getCursorSpeed());
    }

    // MODE_MULTI_PROCESS makes the cached prefs re-read the file once Settings in the main process changes it.
    @SuppressWarnings("deprecation")
    private Prefs prefs() {
        getSharedPreferences("prefs", MODE_MULTI_PROCESS);
        return Prefs.get(this);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        CursorController c = controller;
        if (c == null) return false;
        Prefs prefs = prefs();
        if (!CursorMode.EVERYWHERE.name().equals(prefs.getCursorMode())) {
            if (c.isActive()) c.show(false);
            return false;
        }
        if (event.getKeyCode() == prefs.getCursorKey()) {
            if (event.getAction() == KeyEvent.ACTION_UP) c.show(!c.isActive());
            return true;
        }
        return c.onKey(event);
    }

    @Override
    public void click(float x, float y) {
        tap(x, y, 40);
    }

    @Override
    public void longClick(float x, float y) {
        tap(x, y, 800);
    }

    @Override
    public void scroll(float x, float y, int dx, int dy) {
        // Pushing past the right edge means "show more on the right": swipe content to the left.
        float dist = 300f * getResources().getDisplayMetrics().density;
        float sx = x - dx * 60f;
        float sy = y - dy * 60f;
        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(sx - dx * dist, sy - dy * dist);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 250)).build(), null, null);
    }

    private void tap(float x, float y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        running = false;
        if (view != null) {
            try {
                getSystemService(WindowManager.class).removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        super.onDestroy();
    }

    private static String component(Context context) {
        return new ComponentName(context, CursorService.class).flattenToString();
    }

    static boolean isEnabled(Context context) {
        String s = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && Arrays.asList(s.split(":")).contains(component(context));
    }

    // Needs WRITE_SECURE_SETTINGS, granted by the home-screen wizard or setup.sh.
    static void setEnabled(Context context, boolean enabled) {
        ContentResolver cr = context.getContentResolver();
        String me = component(context);
        String s = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> list = new ArrayList<>();
        if (s != null) {
            for (String it : s.split(":")) {
                if (!it.trim().isEmpty() && !it.equals(me)) list.add(it);
            }
        }
        if (enabled) list.add(me);
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", list));
        if (enabled) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }
}
