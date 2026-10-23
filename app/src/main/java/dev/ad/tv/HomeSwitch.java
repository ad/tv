package dev.ad.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HomeSwitch {
    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    static final String SETUPWRAITH = "com.google.android.tungsten.setupwraith";
    private static final String STOCK_HOME = LAUNCHERX + "/.home.HomeActivity";
    // Kept on the TV, not in our data, so it survives a reinstall and scripts/setup.sh shares it.
    private static final String KEPT_OFF = "/data/local/tmp/tv-home.setup-kept-off";
    private static final String RECORD_KEPT_OFF = "pm list packages -d | grep -qx package:" + LAUNCHERX
            + " || { pm list packages -d | grep -qx package:" + SETUPWRAITH
            + " && touch " + KEPT_OFF + " || rm -f " + KEPT_OFF + "; }";

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
            String wifi = sh.run("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'");
            if (!wifi.contains("cname=") && !wifi.contains("Wi-Fi is")
                    && !"0".equals(sh.run("settings get global wifi_on").trim())) {
                throw new IOException(context.getString(R.string.wifi_unknown));
            }
            Set<String> owners = new HashSet<>();
            Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(wifi);
            while (m.find()) {
                String owner = m.group(1);
                if (owner.equals(LAUNCHERX) || owner.equals(SETUPWRAITH)) owners.add(owner);
            }
            if (!owners.isEmpty()) throw new WifiOwnedException(owners);

            sh.run(RECORD_KEPT_OFF);
            log.accept("set-home-activity");
            expect(sh.run("cmd package set-home-activity " + pkg + "/" + HomeActivity.class.getName()), "Success", "set-home-activity");
            // Both go together: launcherx off with setupwraith on drops HOME into setup's recovery loop.
            log.accept("disable " + SETUPWRAITH);
            String setup = sh.run("pm disable-user --user 0 " + SETUPWRAITH + " 2>&1");
            if (!setup.contains("disabled-user") && !setup.contains("Unknown package")) {
                throw new IOException(SETUPWRAITH + " is still on, " + LAUNCHERX + " was left on: " + setup.trim());
            }
            log.accept("disable " + LAUNCHERX);
            String launcher = sh.run("pm disable-user --user 0 " + LAUNCHERX);
            if (!launcher.contains("disabled-user")) {
                // Left off, the next run would record setupwraith as off before us and revert would never enable it.
                String restored = keptOff(sh) || sh.run("pm enable " + SETUPWRAITH + " 2>&1").contains("new state: enabled")
                        ? "" : "; " + SETUPWRAITH + " is left off";
                throw new IOException("disable " + LAUNCHERX + ": " + launcher.trim() + restored);
            }
        });
    }

    static void useStockLauncher(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            List<String> failed = new ArrayList<>();
            log.accept("enable " + LAUNCHERX);
            if (!sh.run("pm enable " + LAUNCHERX).contains("new state: enabled")) failed.add(LAUNCHERX);
            if (!keptOff(sh)) {
                log.accept("enable " + SETUPWRAITH);
                String setup = sh.run("pm enable " + SETUPWRAITH + " 2>&1");
                if (!setup.contains("new state: enabled") && !setup.contains("Unknown package")) failed.add(SETUPWRAITH);
            }
            if (!sh.run("cmd package set-home-activity " + STOCK_HOME).contains("Success")) failed.add("set-home-activity");
            if (!failed.isEmpty()) throw new IOException("not restored: " + String.join(", ", failed));
            sh.run("rm -f " + KEPT_OFF);
        });
    }

    private static boolean keptOff(Shell sh) throws IOException {
        return sh.run("[ -e " + KEPT_OFF + " ] && echo kept").contains("kept");
    }

    private static void expect(String output, String marker, String step) throws IOException {
        if (!output.contains(marker)) throw new IOException(step + ": " + output.trim());
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
