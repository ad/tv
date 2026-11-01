package dev.ad.tv;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    // Crashes on the screensaver while tvlauncher is off (free-my-tv #11).
    private static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    // The stock HOME component, then the packages we disabled. Shared with scripts/setup.sh.
    private static final String TV_STATE = "/data/local/tmp/tv-home.stock";
    private static final String HOME_INTENT = "-a android.intent.action.MAIN -c android.intent.category.HOME";
    // Homes that are also the TV's settings, Wi-Fi or Bluetooth (Fire TV, Hotack's LuminaOS): off, the TV is unusable.
    private static final Pattern UNSAFE_HOME = Pattern.compile("android|com\\.android\\.(systemui|settings|tv\\.settings)|com\\.amazon\\..*|com\\.htc\\.luminaos");
    // free-my-tv's DEBLOAT minus YouTube Music (a default favorite) and HY300's stock home (needs JmLauncher handling).
    private static final List<String> DEBLOAT = List.of(
            "com.google.android.play.games",
            "com.miui.tv.analytics", "com.xiaomi.statistic", "com.mitv.tvhome.mitvplus", "com.xm.webcontent",
            "com.tcl.magiconnectfree", "com.tcl.miracast", "com.tcl.messagebox", "com.tcl.usercenter",
            "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",
            "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",
            "tv.anoki.acr.anokiacroptin", "tv.anoki.acr.controller", "fusion.android.tv.demo", "com.expressluck.tvhelpmanual",
            "com.homwee.aipont", "com.homwee.help", "com.mediatek.tv.retaildemo", "com.electronic.manual",
            "com.smartdevice.epopplayer", "com.coocaa.demonow.lightining", "com.smartdevice.recommendation");
    // Shared with scripts/setup.sh.
    private static final String DEBLOATED = "/data/local/tmp/tv-home.debloated";
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
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && context.getPackageName().equals(home.activityInfo.packageName);
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
            if (!sh.run("pm path " + LAUNCHERX + " 2>&1").contains("package:")) {
                useThisOnAndroidTv(context, sh, log);
                return;
            }
            checkWifi(context, sh, Set.of(LAUNCHERX, SETUPWRAITH));

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
            debloat(sh, log);
        });
    }

    private static void useThisOnAndroidTv(Context context, Shell sh, Consumer<String> log) throws IOException {
        String pkg = context.getPackageName();
        String recorded = sh.run("cat " + TV_STATE + " 2>/dev/null").trim().split("\\s+")[0];
        String stock = lastLine(sh.run("cmd package resolve-activity --brief " + HOME_INTENT));
        // After a partial run HOME is already ours, and only the record still names the TV's own home.
        if (stock.startsWith(pkg + "/") && recorded.contains("/")) stock = recorded;
        // No default home (the chooser), Android's bare fallback or setup's recovery screen: not the TV's own home.
        String stockPkg = stock.contains("/") && !stock.startsWith("android/") && !stock.contains("FallbackHome")
                && !stock.startsWith(SETUPWRAITH + "/") ? stock.substring(0, stock.indexOf('/')) : pkg;
        if (UNSAFE_HOME.matcher(stockPkg).matches()) throw new IOException(stockPkg + " is this TV's home and more: not turned off");
        Set<String> enabled = enabledPackages(sh);
        List<String> off = new ArrayList<>();
        // setupwraith first: with the home off and it on, its RecoveryActivity takes HOME, and a restart there
        // factory-reset a Xiaomi (free-my-tv, MiTV-MOSR4).
        if (sh.run("cmd package query-activities --brief " + HOME_INTENT).contains(SETUPWRAITH + "/")) off.add(SETUPWRAITH);
        if (!stockPkg.equals(pkg)) off.add(stockPkg);
        if (!stockPkg.equals(TV_LAUNCHER)) off.add(TV_LAUNCHER);
        off.add(TV_RECOMMENDATIONS);
        off.removeIf(p -> !enabled.contains(p));
        checkWifi(context, sh, new HashSet<>(off));

        // A rerun finds the stock home already off, so the first record is the one to keep.
        String first = stockPkg.equals(pkg) ? "none" : stock;
        sh.run("[ -e " + TV_STATE + " ] || printf '%s\\n' '" + first + "' " + String.join(" ", off) + " > " + TV_STATE);
        log.accept("set-home-activity");
        expect(sh.run("cmd package set-home-activity " + pkg + "/" + HomeActivity.class.getName()), "Success", "set-home-activity");
        for (String p : off) {
            log.accept("disable " + p);
            expect(sh.run("pm disable-user --user 0 " + p), "disabled-user", "disable " + p);
        }
        String home = lastLine(sh.run("cmd package resolve-activity --brief " + HOME_INTENT));
        if (!home.startsWith(pkg + "/")) {
            throw new IOException("HOME is still " + home + "\n" + sh.run("cmd package query-activities --brief " + HOME_INTENT).trim());
        }
        debloat(sh, log);
    }

    /** Never fails the switch: a package left on is only bloat. */
    private static void debloat(Shell sh, Consumer<String> log) {
        try {
            debloatOrThrow(sh, log);
        } catch (IOException e) {
            log.accept("debloat stopped: " + e.getMessage());
        }
    }

    private static void debloatOrThrow(Shell sh, Consumer<String> log) throws IOException {
        Set<String> enabled = enabledPackages(sh);
        List<String> off = new ArrayList<>(DEBLOAT);
        off.removeIf(p -> !enabled.contains(p));
        if (off.isEmpty()) return;
        // Software under the TV's inputs or the remote's Input key: off, HDMI showed "No signal" (free-my-tv #321).
        // A dump that doesn't name the package means dumpsys is off, not "nothing needed". A line per package keeps
        // the read timeout from running out on a slow TV.
        List<String> check = Arrays.asList(sh.run("for p in " + String.join(" ", off) + "; do d=\"$(dumpsys package $p)\"; "
                + "if ! printf '%s\\n' \"$d\" | grep -q -F \"Package [$p]\"; then echo \"unknown $p\"; "
                + "elif printf '%s\\n' \"$d\" | grep -q -E 'TV_INPUT_HARDWARE|TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory'; "
                + "then echo \"keep $p\"; else echo \"- $p\"; fi; done; echo checked").trim().split("\\s*\n\\s*"));
        if (!check.contains("checked") || check.stream().anyMatch(l -> l.startsWith("unknown "))) {
            log.accept("debloat skipped: could not check what the TV's inputs need");
            return;
        }
        String wifiDump = sh.run("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'");
        if (!wifiDump.contains("cname=") && !wifiDump.contains("Wi-Fi is")) {
            log.accept("debloat skipped: could not check which apps saved Wi-Fi networks");
            return;
        }
        List<String> wifi = Arrays.asList(wifiDump.split("\\s+"));
        off.removeIf(p -> check.contains("keep " + p) || wifi.contains("cname=" + p));
        for (String p : off) {
            sh.run("grep -qx " + p + " " + DEBLOATED + " 2>/dev/null || echo " + p + " >> " + DEBLOATED);
            log.accept("disable " + p);
            if (!sh.run("pm disable-user --user 0 " + p).contains("disabled-user")) log.accept("left on: " + p);
        }
    }

    private static void restoreDebloat(Shell sh, List<String> failed, Consumer<String> log) throws IOException {
        int before = failed.size();
        for (String p : sh.run("cat " + DEBLOATED + " 2>/dev/null").trim().split("\\s+")) {
            if (p.isEmpty()) continue;
            log.accept("enable " + p);
            if (!sh.run("pm enable " + p).contains("new state: enabled")) failed.add(p);
        }
        if (failed.size() == before) sh.run("rm -f " + DEBLOATED);
    }

    private static Set<String> enabledPackages(Shell sh) throws IOException {
        return new HashSet<>(Arrays.asList(sh.run("pm list packages -e").replace("package:", "").split("\\s+")));
    }

    static void useStockLauncher(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            List<String> failed = new ArrayList<>();
            restoreDebloat(sh, failed, log);
            String[] state = sh.run("cat " + TV_STATE + " 2>/dev/null").trim().split("\\s+");
            if (!state[0].isEmpty()) {
                boolean setup = false;
                for (int i = state.length - 1; i > 0; i--) {
                    if (state[i].equals(SETUPWRAITH)) {
                        setup = true;
                        continue;
                    }
                    log.accept("enable " + state[i]);
                    if (!sh.run("pm enable " + state[i]).contains("new state: enabled")) failed.add(state[i]);
                }
                String setHome = "cmd package set-home-activity '" + state[0] + "'";
                boolean home = !state[0].equals("none") && sh.run(setHome).contains("Success");
                if (!home && !state[0].equals("none")) failed.add("set-home-activity");
                // setupwraith only once the TV's home is back, or its recovery screen takes HOME. Another HOME
                // candidate can drop the default, so set it again.
                if (setup && home) {
                    log.accept("enable " + SETUPWRAITH);
                    if (!sh.run("pm enable " + SETUPWRAITH).contains("new state: enabled")) failed.add(SETUPWRAITH);
                    if (!sh.run(setHome).contains("Success")) failed.add("set-home-activity");
                } else if (setup) {
                    // The record stays, so a later revert still knows setupwraith is ours to turn back on.
                    log.accept(SETUPWRAITH + " left off: no TV home to restore first");
                }
                if (!failed.isEmpty()) throw new IOException("not restored: " + String.join(", ", failed));
                if (!setup || home) sh.run("rm -f " + TV_STATE);
                return;
            }
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

    /** Disabling an app deletes the Wi-Fi networks it saved; on Google TV that is usually setupwraith. */
    private static void checkWifi(Context context, Shell sh, Set<String> disabling) throws IOException {
        if (disabling.isEmpty()) return;
        String wifi = sh.run("dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'");
        if (!wifi.contains("cname=") && !wifi.contains("Wi-Fi is")
                && !"0".equals(sh.run("settings get global wifi_on").trim())) {
            throw new IOException(context.getString(R.string.wifi_unknown));
        }
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(wifi);
        while (m.find()) {
            if (disabling.contains(m.group(1))) owners.add(m.group(1));
        }
        if (!owners.isEmpty()) throw new WifiOwnedException(owners);
    }

    private static String lastLine(String output) {
        String[] lines = output.trim().split("\\s*\n\\s*");
        return lines[lines.length - 1];
    }

    private static boolean keptOff(Shell sh) throws IOException {
        return sh.run("[ -e " + KEPT_OFF + " ] && echo kept").contains("kept");
    }

    private static void expect(String output, String marker, String step) throws IOException {
        if (!output.contains(marker)) throw new IOException(step + ": " + output.trim());
    }

    static void optimize(Context context, Consumer<String> log) throws IOException {
        withAdb(context, log, sh -> {
            log.accept("animations 0.5x");
            for (String key : List.of("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
                sh.run("settings put global " + key + " 0.5");
            }
            // Last: on a slow TV it can outlast the read timeout, and the animations are already set by then.
            String df = "df -h /data | tail -1 | awk '{print $4}'";
            String before = sh.run(df).trim();
            sh.run("pm trim-caches 64G");
            log.accept("free on /data: " + before + " -> " + sh.run(df).trim());
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
