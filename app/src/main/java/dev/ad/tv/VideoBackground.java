package dev.ad.tv;

import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.IOException;

// Released in stop() so the decoder is free while another app is in front.
final class VideoBackground {
    private static final String ASSET = "asset:///";

    private final FrameLayout container;
    private MediaPlayer player;
    private SurfaceView surface;
    private String source;
    private boolean preparing;
    private boolean prepared;
    private int videoW;
    private int videoH;

    VideoBackground(FrameLayout container) {
        this.container = container;
        // The container may be laid out after the first video size arrives.
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l != or - ol || b - t != ob - ot) && videoW > 0) cover(videoW, videoH);
        });
    }

    void start(String uri) {
        if (uri == null) {
            stop();
            source = null;
            return;
        }
        if (player != null && uri.equals(source)) {
            if (prepared) player.start();
            return;
        }
        stop();
        source = uri;
        SurfaceView view = new SurfaceView(container.getContext());
        // Full size from the start: a 0x0 SurfaceView never gets a surface to decode into.
        container.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        surface = view;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setVolume(0f, 0f);
        mp.setLooping(true);
        mp.setOnVideoSizeChangedListener((p, w, h) -> {
            if (p == player) cover(w, h);
        });
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            p.start();
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) Prefs.get(container.getContext()).setVideoError(null);
            return false;
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p == player) fail("MediaPlayer error " + what + ", " + extra);
            return true;
        });
        try {
            setSource(mp, uri);
        } catch (Exception e) {
            fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (mp != player) return;
                mp.setDisplay(holder);
                if (!preparing) {
                    preparing = true;
                    mp.prepareAsync();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mp == player) mp.setDisplay(null);
            }
        });
    }

    private void setSource(MediaPlayer mp, String uri) throws IOException {
        if (uri.startsWith(ASSET)) {
            try (AssetFileDescriptor fd = container.getContext().getAssets().openFd(Uri.decode(uri.substring(ASSET.length())))) {
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
        } else {
            mp.setDataSource(container.getContext(), Uri.parse(uri));
        }
    }

    private void fail(String message) {
        prepared = false;
        if (surface != null) surface.setVisibility(View.GONE);
        Prefs.get(container.getContext()).setVideoError(message);
    }

    void stop() {
        MediaPlayer mp = player;
        player = null;
        preparing = false;
        prepared = false;
        if (mp != null) mp.release();
        if (surface != null) container.removeView(surface);
        surface = null;
        videoW = 0;
        videoH = 0;
    }

    private void cover(int width, int height) {
        SurfaceView view = surface;
        if (view == null || width == 0 || height == 0) return;
        videoW = width;
        videoH = height;
        float cw = container.getWidth();
        if (cw <= 0) return;
        float ch = container.getHeight();
        float videoRatio = (float) width / height;
        float w = cw / ch > videoRatio ? cw : ch * videoRatio;
        float h = cw / ch > videoRatio ? cw / videoRatio : ch;
        view.setLayoutParams(new FrameLayout.LayoutParams((int) w, (int) h, Gravity.CENTER));
    }
}
