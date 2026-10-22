package dev.ad.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HomeSwitch {
    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    static final String SETUPWRAITH = "com.google.android.tungsten.setupwraith";
    private static final String STOCK_HOME = LAUNCHERX + "/.home.HomeActivity";

    static final class WifiOwnedException extends IOException {
        final Set<String> owners;

        WifiOwnedException(Set<String> owners) {
            super("Wi-Fi network saved by " + String.join(", ", owners));
            this.owners = owners;
        }
    }

    private interface Shell {
        String run(String command) throws IOException;
    }

    private interface Block {
        void run(Shell sh) throws IOException;
    }

    private HomeSwitch() {}

    static boolean isOurs(Context context) {
        return !Apps.isInstalled(context, LAUNCHERX);
    }

    static boolean canWriteSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isPaired(Context context) {
        return new File(context.getFilesDir(), "adb.paired").exists();
    }

    /** adbd listens here only after `adb tcpip 5555`; it does not survive a reboot. */
    static boolean isLegacyPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(Adb.HOST, Adb.PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void authorize(Context context) throws IOException {
        withAdb(context, line -> {}, sh -> {});
    }

    static void useThisLauncher(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            String pkg = context.getPackageName();
            log.accept("pm grant WRITE_SECURE_SETTINGS");
            sh.run("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            sh.run("appops set " + pkg + " GET_USAGE_STATS allow");

            // Disabling an app deletes the Wi-Fi networks it saved; on Google TV that is usually setupwraith.
            Set<String> owners = new HashSet<>();
            Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(sh.run("dumpsys wifi | grep -o 'cname=[^ ]*'"));
            while (m.find()) {
                String owner = m.group(1);
                if (owner.equals(LAUNCHERX) || owner.equals(SETUPWRAITH)) owners.add(owner);
            }
            if (!owners.isEmpty()) throw new WifiOwnedException(owners);

            log.accept("set-home-activity");
            sh.run("cmd package set-home-activity " + pkg + "/" + HomeActivity.class.getName());
            // Both go together: launcherx off with setupwraith on drops HOME into setup's recovery loop.
            log.accept("disable " + SETUPWRAITH);
            sh.run("pm disable-user --user 0 " + SETUPWRAITH);
            log.accept("disable " + LAUNCHERX);
            sh.run("pm disable-user --user 0 " + LAUNCHERX);
        });
    }

    static void useStockLauncher(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            log.accept("enable " + LAUNCHERX);
            sh.run("pm enable " + LAUNCHERX);
            log.accept("enable " + SETUPWRAITH);
            sh.run("pm enable " + SETUPWRAITH);
            sh.run("cmd package set-home-activity " + STOCK_HOME);
        });
    }

    static void grantPermissions(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            sh.run("pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
            sh.run("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
        });
    }

    private static void withAdb(Context context, Consumer<String> log, Block block) throws IOException {
        log.accept("connect");
        try (Adb adb = Adb.connect(context, 120_000)) {
            try (java.io.FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "adb.paired"))) {
                out.write(Long.toString(System.currentTimeMillis()).getBytes());
            }
            block.run(adb::shell);
        }
    }
}
