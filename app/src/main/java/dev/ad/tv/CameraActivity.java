package dev.ad.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {
    private static final String EXTRA_URL = "url";
    private static final String PREFIX = "camera:";

    // Favorites are stored comma-joined, so the URL is kept percent-encoded inside the entry.
    static String favorite(String url) {
        return PREFIX + Uri.encode(url);
    }

    static boolean isCamera(String favorite) {
        return favorite.startsWith(PREFIX);
    }

    static String label(String favorite) {
        String url = url(favorite);
        return url.substring(Math.max(url.lastIndexOf('@') + 1, url.indexOf("://") + 3));
    }

    static Intent intent(Context context, String favorite) {
        return new Intent(context, CameraActivity.class).putExtra(EXTRA_URL, url(favorite));
    }

    static String url(String favorite) {
        return Uri.decode(favorite.substring(PREFIX.length()));
    }

    private SurfaceView video;
    private SurfaceHolder holder;
    private Rtsp stream;
    private Thread thread;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video = new SurfaceView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(video, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);
        video.getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        this.holder = holder;
        play();
    }

    private void play() {
        Rtsp s = new Rtsp(getIntent().getStringExtra(EXTRA_URL));
        stream = s;
        thread = new Thread(() -> {
            try {
                s.play(holder.getSurface(), this::fit);
            } catch (Exception e) {
                if (!s.isClosed()) runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.camera_error) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "rtsp");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        this.holder = null;
        stop();
    }

    private void stop() {
        stream.close();
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (step == 0) return super.onKeyDown(keyCode, event);
        List<String> cameras = new ArrayList<>();
        for (String f : Prefs.get(this).getFavorites()) if (isCamera(f)) cameras.add(url(f));
        int i = cameras.indexOf(getIntent().getStringExtra(EXTRA_URL));
        // A held key would reconnect on every repeat.
        if (holder == null || cameras.size() < 2 || i < 0 || event.getRepeatCount() > 0) return true;
        getIntent().putExtra(EXTRA_URL, cameras.get(Math.floorMod(i + step, cameras.size())));
        stop();
        play();
        return true;
    }

    private void fit(int w, int h) {
        runOnUiThread(() -> {
            View parent = (View) video.getParent();
            float scale = Math.min((float) parent.getWidth() / w, (float) parent.getHeight() / h);
            ViewGroup.LayoutParams lp = video.getLayoutParams();
            lp.width = Math.round(w * scale);
            lp.height = Math.round(h * scale);
            video.setLayoutParams(lp);
        });
    }
}
