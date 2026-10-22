package dev.ad.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Images {
    private static final long DISK_LIMIT = 50L * 1024 * 1024;
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> memory = new LruCache<>(48 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private static volatile boolean trimmed = false;

    private Images() {}

    static void load(ImageView view, String url) {
        view.setTag(url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        Context context = view.getContext().getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp;
            try {
                bmp = fetch(context, url);
            } catch (Exception e) {
                bmp = null;
            }
            if (bmp == null) return;
            memory.put(url, bmp);
            Bitmap b = bmp;
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    static void prefetch(Context context, String url) {
        pool.execute(() -> {
            try {
                File f = file(context, url);
                if (!f.exists()) Files.write(f.toPath(), Http.getBytes(url));
            } catch (Exception ignored) {
            }
        });
    }

    private static Bitmap fetch(Context context, String url) throws IOException {
        File f = file(context, url);
        if (!f.exists()) {
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), Http.getBytes(url));
            tmp.renameTo(f);
            trim(context);
        } else {
            f.setLastModified(System.currentTimeMillis());
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    private static File dir(Context context) {
        File d = new File(context.getCacheDir(), "img");
        d.mkdirs();
        return d;
    }

    private static File file(Context context, String url) {
        byte[] md;
        try {
            md = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md) sb.append(String.format("%02x", b & 0xff));
        return new File(dir(context), sb.toString());
    }

    private static void trim(Context context) {
        if (trimmed) return;
        trimmed = true;
        try {
            File[] files = dir(context).listFiles();
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            long total = 0;
            for (File f : files) {
                total += f.length();
                if (total > DISK_LIMIT) f.delete();
            }
        } finally {
            main.postDelayed(() -> trimmed = false, 60_000);
        }
    }
}
