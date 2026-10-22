package dev.ad.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class RescueActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rescue);
        TextView status = findViewById(R.id.status);

        Button retry = findViewById(R.id.retry);
        retry.setOnClickListener(v -> {
            CrashWatchdog.reset(this);
            finish();
        });
        Button openStock = findViewById(R.id.openStock);
        openStock.setEnabled(Apps.isInstalled(this, HomeSwitch.LAUNCHERX));
        openStock.setOnClickListener(v -> Apps.start(
            this,
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(HomeSwitch.LAUNCHERX, HomeSwitch.LAUNCHERX + ".home.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ));
        Button restore = findViewById(R.id.restore);
        restore.setOnClickListener(v -> {
            status.setText(R.string.working);
            Async.io(() -> {
                String result;
                try {
                    HomeSwitch.useStockLauncher(this, s -> {});
                    result = getString(R.string.stock_restored);
                } catch (Exception e) {
                    result = e.getMessage();
                }
                String text = result;
                Async.main(() -> {
                    if (isDestroyed()) return;
                    status.setText(text);
                    openStock.setEnabled(Apps.isInstalled(this, HomeSwitch.LAUNCHERX));
                });
            });
        });
        Views.mouseFriendly(retry);
        Views.mouseFriendly(openStock);
        Views.mouseFriendly(restore);
        retry.requestFocus();
    }
}
