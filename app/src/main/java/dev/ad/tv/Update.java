package dev.ad.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;

final class Update {
    private static final String TAG = "TvHome";
    private static final String LATEST = "https://api.github.com/repos/ad/tv/releases/latest";
    private static final long INTERVAL = 3 * 3_600_000L;
    private static long checked;

    private interface Call<T> {
        T run() throws IOException;
    }

    private Update() {}

    static void check(Activity activity) {
        long now = System.currentTimeMillis();
        if (checked != 0 && now - checked < INTERVAL) return;
        checked = now;
        Async.io(() -> {
            try {
                JSONObject release = new JSONObject(retry(() -> Http.get(LATEST)));
                String tag = release.optString("tag_name");
                if (build(tag) <= BuildConfig.VERSION_CODE) return;
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (!asset.optString("name").endsWith(".apk")) continue;
                    String url = asset.optString("browser_download_url");
                    Async.main(() -> offer(activity, tag, url));
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e.getMessage());
            }
        });
    }

    static int build(String tag) {
        try {
            return Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void offer(Activity activity, String tag, String url) {
        if (activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available, tag))
                .setPositiveButton(R.string.update_install, (d, w) -> install(activity, url))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void install(Activity activity, String url) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            checked = 0;
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(activity, R.string.update_allow_unknown, Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        Context app = activity.getApplicationContext();
        Async.io(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = -1;
            try {
                byte[] apk = retry(() -> Http.getBytes(url));
                id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (OutputStream out = session.openWrite("apk", 0, apk.length)) {
                        out.write(apk);
                        session.fsync(out);
                    }
                    Intent done = new Intent(app, Receiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                    session.commit(PendingIntent.getBroadcast(app, 0, done, flags).getIntentSender());
                }
            } catch (Exception e) {
                Log.w(TAG, "update failed: " + e.getMessage());
                if (id != -1) installer.abandonSession(id);
                Async.main(() -> Toast.makeText(app, app.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    // The TV's first TLS handshake to GitHub is often reset, the second one passes.
    private static <T> T retry(Call<T> call) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.run();
            } catch (SocketException e) {
                if (attempt == 3) throw e;
            }
        }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, context.getString(R.string.update_failed, message), Toast.LENGTH_LONG).show();
            }
        }
    }
}
