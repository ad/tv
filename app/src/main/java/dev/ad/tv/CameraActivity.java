package dev.ad.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String EXTRA_URL = "url";
    private static final String PREFIX = "camera:";
    private static final float[] ZOOMS = {1, 2, 3};
    // Stabilization moves the picture, so it needs a crop margin to hide the edges.
    private static final float STAB_ZOOM = 1.15f;
    private static final float PAN_STEP = 0.1f;

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

    private TextureView video;
    private Surface surface;
    private Rtsp stream;
    private Thread thread;
    private int zoomStep;
    private float panX, panY, shakeX, shakeY;
    private Shake shake;
    private Bitmap sample;
    private final int[] pixels = new int[Shake.W * Shake.H];

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video = new TextureView(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(video, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(root);
        video.setSurfaceTextureListener(this);
        if (Prefs.get(this).isCameraStab()) shake = new Shake();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        play();
    }

    private void play() {
        zoomStep = 0;
        panX = panY = shakeX = shakeY = 0;
        if (shake != null) shake = new Shake();
        apply();
        Rtsp s = new Rtsp(getIntent().getStringExtra(EXTRA_URL));
        Surface out = surface;
        stream = s;
        thread = new Thread(() -> {
            try {
                s.play(out, this::fit);
            } catch (Exception e) {
                if (!s.isClosed()) runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.camera_error) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "rtsp");
        thread.start();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    // TextureView keeps its surface in the background, so the stream follows the activity's visibility.
    @Override protected void onStart() {
        super.onStart();
        if (surface != null && stream == null) play();
    }

    @Override protected void onStop() {
        super.onStop();
        stop();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        stop();
        surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (shake == null) return;
        if (sample == null) sample = Bitmap.createBitmap(Shake.W, Shake.H, Bitmap.Config.ARGB_8888);
        video.getBitmap(sample);
        sample.getPixels(pixels, 0, Shake.W, 0, 0, Shake.W, Shake.H);
        float[] offset = shake.next(pixels);
        shakeX = offset[0] * video.getWidth() / Shake.W;
        shakeY = offset[1] * video.getHeight() / Shake.H;
        apply();
    }

    private float zoom() {
        return shake == null ? ZOOMS[zoomStep] : Math.max(ZOOMS[zoomStep], STAB_ZOOM);
    }

    // View properties, not setTransform: getBitmap must see the raw frame, or stabilization chases its own correction.
    private void apply() {
        float z = zoom();
        video.setScaleX(z);
        video.setScaleY(z);
        video.setTranslationX(Shake.clamp(panX + shakeX * z, z, video.getWidth()));
        video.setTranslationY(Shake.clamp(panY + shakeY * z, z, video.getHeight()));
    }

    private void resetZoom() {
        zoomStep = 0;
        panX = panY = 0;
        apply();
    }

    @Override public void onBackPressed() {
        if (zoomStep > 0) resetZoom();
        else super.onBackPressed();
    }

    private void stop() {
        if (stream == null) return;
        stream.close();
        stream = null;
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            event.startTracking();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) toggleStab();
            return true;
        }
        if (zoomStep > 0) {
            int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? -1 : 0;
            int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? -1 : 0;
            if (dx != 0 || dy != 0) {
                float z = zoom();
                panX = Shake.clamp(panX + dx * PAN_STEP * video.getWidth(), z, video.getWidth());
                panY = Shake.clamp(panY + dy * PAN_STEP * video.getHeight(), z, video.getHeight());
                apply();
                return true;
            }
        }
        int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (step == 0) return super.onKeyDown(keyCode, event);
        List<String> cameras = new ArrayList<>();
        for (String f : Prefs.get(this).getFavorites()) if (isCamera(f)) cameras.add(url(f));
        int i = cameras.indexOf(getIntent().getStringExtra(EXTRA_URL));
        // A held key would reconnect on every repeat.
        if (surface == null || cameras.size() < 2 || i < 0 || event.getRepeatCount() > 0) return true;
        getIntent().putExtra(EXTRA_URL, cameras.get(Math.floorMod(i + step, cameras.size())));
        stop();
        play();
        return true;
    }

    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyLongPress(keyCode, event);
        toggleStab();
        return true;
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyUp(keyCode, event);
        // Untracked: the OK that opened this screen was pressed on the home screen.
        if (!event.isTracking() || (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0) return true;
        if (zoomStep == ZOOMS.length - 1) {
            resetZoom();
        } else {
            zoomStep++;
            apply();
        }
        return true;
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private void toggleStab() {
        boolean on = shake == null;
        Prefs.get(this).setCameraStab(on);
        shake = on ? new Shake() : null;
        shakeX = shakeY = 0;
        apply();
        Toast.makeText(this, on ? R.string.camera_stab_on : R.string.camera_stab_off, Toast.LENGTH_SHORT).show();
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
