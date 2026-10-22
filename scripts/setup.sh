#!/usr/bin/env bash
# Makes TV Home the home screen on a Google TV (or reverts it) from a computer.
# The in-app switch (Settings → Home screen) does the same without a computer once paired;
# this script is the fallback.
#
#   scripts/setup.sh [TV_IP:PORT] [path/to/tv-home.apk]   # install (optional) and switch to TV Home
#   scripts/setup.sh --revert [TV_IP:PORT]                  # bring the Google TV home back
#
# Pair first with Wireless debugging:  adb pair TV_IP:PAIR_PORT   (code from the TV screen)
set -euo pipefail

PKG=dev.ad.tv
HOME_ACTIVITY="$PKG/$PKG.HomeActivity"
LAUNCHERX=com.google.android.apps.tv.launcherx
SETUPWRAITH=com.google.android.tungsten.setupwraith

revert=0
if [[ "${1:-}" == "--revert" ]]; then revert=1; shift; fi
target="${1:-}"
apk="${2:-}"

if [[ -n "$target" ]]; then
  adb connect "$target"
  export ANDROID_SERIAL="$target"
fi
adb get-state >/dev/null

sh() { adb shell "$@" | tr -d '\r'; }

if [[ $revert == 1 ]]; then
  sh pm enable "$LAUNCHERX"
  sh pm enable "$SETUPWRAITH"
  sh cmd package set-home-activity "$LAUNCHERX/.home.HomeActivity" || true
  echo "Google TV home restored."
  exit 0
fi

if [[ -n "$apk" ]]; then
  adb install -r "$apk"
fi
sh pm path "$PKG" >/dev/null || { echo "$PKG is not installed"; exit 1; }

sh pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
sh appops set "$PKG" GET_USAGE_STATS allow

# Disabling an app deletes the Wi-Fi networks it saved (usually setupwraith, from first setup).
owners=$(sh "dumpsys wifi | grep -o 'cname=[^ ]*' || true" | sed 's/cname=//' | sort -u)
for p in $LAUNCHERX $SETUPWRAITH; do
  if grep -qx "$p" <<<"$owners"; then
    echo "The Wi-Fi network was saved by $p and would be deleted."
    echo "Forget the network in Settings, connect again, then rerun this script."
    exit 1
  fi
done

sh cmd package set-home-activity "$HOME_ACTIVITY" || true
# Both together: launcherx off with setupwraith on sends HOME into setup's recovery loop.
sh pm disable-user --user 0 "$SETUPWRAITH"
sh pm disable-user --user 0 "$LAUNCHERX"
sh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "TV Home is now the home screen. Revert with: $0 --revert ${target}"
