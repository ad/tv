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
# Shared with the in-app switch (HomeSwitch.KEPT_OFF): setupwraith was off before the first switch.
KEPT_OFF=/data/local/tmp/tv-home.setup-kept-off

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
kept_off() { [[ -n "$(sh "[ -e $KEPT_OFF ] && echo kept || true")" ]]; }

if [[ $revert == 1 ]]; then
  failed=""
  [[ "$(sh pm enable "$LAUNCHERX" || true)" == *"new state: enabled"* ]] || failed+=" $LAUNCHERX"
  if ! kept_off; then
    out=$(sh "pm enable $SETUPWRAITH 2>&1" || true)
    [[ "$out" == *"new state: enabled"* || "$out" == *"Unknown package"* ]] || failed+=" $SETUPWRAITH"
  fi
  [[ "$(sh cmd package set-home-activity "$LAUNCHERX/.home.HomeActivity" || true)" == *Success* ]] || failed+=" set-home-activity"
  if [[ -n "$failed" ]]; then echo "Not restored:$failed"; exit 1; fi
  sh rm -f "$KEPT_OFF"
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
wifi=$(sh "dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+' || true")
if [[ "$wifi" != *cname=* && "$wifi" != *"Wi-Fi is"* && "$(sh settings get global wifi_on)" != 0 ]]; then
  echo "Could not check which app saved the Wi-Fi network, so nothing changed. Try again in a minute."
  exit 1
fi
owners=$(grep -o 'cname=[^ ]*' <<<"$wifi" | sed 's/cname=//' | sort -u || true)
for p in $LAUNCHERX $SETUPWRAITH; do
  if grep -qx "$p" <<<"$owners"; then
    echo "The Wi-Fi network was saved by $p and would be deleted."
    echo "Forget the network in Settings, connect again, then rerun this script."
    echo "Networks out of range are not listed there: adb shell cmd wifi list-networks, then adb shell cmd wifi forget-network ID."
    exit 1
  fi
done

sh "pm list packages -d | grep -qx package:$LAUNCHERX || { pm list packages -d | grep -qx package:$SETUPWRAITH && touch $KEPT_OFF || rm -f $KEPT_OFF; }"
[[ "$(sh cmd package set-home-activity "$HOME_ACTIVITY" || true)" == *Success* ]] || { echo "set-home-activity failed"; exit 1; }
# Both together: launcherx off with setupwraith on sends HOME into setup's recovery loop.
out=$(sh "pm disable-user --user 0 $SETUPWRAITH 2>&1" || true)
if [[ "$out" != *disabled-user* && "$out" != *"Unknown package"* ]]; then
  echo "$SETUPWRAITH is still on, $LAUNCHERX was left on: $out"
  exit 1
fi
if [[ "$(sh pm disable-user --user 0 "$LAUNCHERX" || true)" != *disabled-user* ]]; then
  # Left off, the next run would record setupwraith as off before us and revert would never enable it.
  kept_off || sh pm enable "$SETUPWRAITH" >/dev/null || true
  echo "Could not turn off $LAUNCHERX"
  exit 1
fi
sh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "TV Home is now the home screen. Revert with: $0 --revert ${target}"
