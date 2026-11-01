#!/usr/bin/env bash
# Makes TV Home the home screen on a Google TV or Android TV (or reverts it) from a computer.
# The in-app switch (Settings → Home screen) does the same without a computer once paired;
# this script is the fallback.
#
#   scripts/setup.sh [TV_IP:PORT] [path/to/tv-home.apk]   # install (optional) and switch to TV Home
#   scripts/setup.sh --revert [TV_IP:PORT]                  # bring the stock home back
#
# Pair first with Wireless debugging:  adb pair TV_IP:PAIR_PORT   (code from the TV screen)
set -euo pipefail

PKG=dev.ad.tv
HOME_ACTIVITY="$PKG/$PKG.HomeActivity"
LAUNCHERX=com.google.android.apps.tv.launcherx
SETUPWRAITH=com.google.android.tungsten.setupwraith
# Shared with the in-app switch (HomeSwitch.KEPT_OFF): setupwraith was off before the first switch.
KEPT_OFF=/data/local/tmp/tv-home.setup-kept-off
TV_LAUNCHER=com.google.android.tvlauncher
# Crashes on the screensaver while tvlauncher is off (free-my-tv #11).
TV_RECOMMENDATIONS=com.google.android.tvrecommendations
# Shared with HomeSwitch.TV_STATE: the stock HOME component ("none" if unknown), then the packages we disabled.
TV_STATE=/data/local/tmp/tv-home.stock
HOME_INTENT="-a android.intent.action.MAIN -c android.intent.category.HOME"
# As HomeSwitch.DEBLOAT.
DEBLOAT="com.google.android.play.games
  com.miui.tv.analytics com.xiaomi.statistic com.mitv.tvhome.mitvplus com.xm.webcontent
  com.tcl.magiconnectfree com.tcl.miracast com.tcl.messagebox com.tcl.usercenter
  com.tcl.exhibit com.tcl.esticker com.tcl.hotelmenu
  com.tcl.repairguide com.tcl.ocean.instructions com.tcl.logkit
  tv.anoki.acr.anokiacroptin tv.anoki.acr.controller fusion.android.tv.demo com.expressluck.tvhelpmanual
  com.homwee.aipont com.homwee.help com.mediatek.tv.retaildemo com.electronic.manual
  com.smartdevice.epopplayer com.coocaa.demonow.lightining com.smartdevice.recommendation"
# Shared with HomeSwitch.DEBLOATED.
DEBLOATED=/data/local/tmp/tv-home.debloated
# Software under the TV's inputs or the remote's Input key: off, HDMI showed "No signal" (free-my-tv #321).
read -r -d '' INPUT_CHECK <<'EOF2' || true
do d="$(dumpsys package $p)"; if ! printf '%s\n' "$d" | grep -q -F "Package [$p]"; then echo "unknown $p"; elif printf '%s\n' "$d" | grep -q -E 'TV_INPUT_HARDWARE|TV_INPUT_BUTTON|CONNECT_INPUT|AddAccessory'; then echo "keep $p"; else echo "- $p"; fi; done; echo checked
EOF2

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
debloat() {
  local enabled off="" check wifi p
  enabled=$(sh pm list packages -e)
  for p in $DEBLOAT; do grep -qx "package:$p" <<<"$enabled" && off+=" $p"; done
  [[ -n "$off" ]] || return 0
  check=$(sh "for p in $off; $INPUT_CHECK" || true)
  if [[ "$check" != *checked* || "$check" == *"unknown "* ]]; then
    echo "Debloat skipped: could not check what the TV's inputs need."
    return 0
  fi
  wifi=$(sh "dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+' || true")
  if [[ "$wifi" != *cname=* && "$wifi" != *"Wi-Fi is"* ]]; then
    echo "Debloat skipped: could not check which apps saved Wi-Fi networks."
    return 0
  fi
  for p in $off; do
    grep -qx "keep $p" <<<"$check" && continue
    grep -qx "cname=$p" <<<"$wifi" && continue
    sh "grep -qx $p $DEBLOATED 2>/dev/null || echo $p >> $DEBLOATED"
    [[ "$(sh pm disable-user --user 0 "$p" || true)" == *disabled-user* ]] || echo "Left on: $p"
  done
}
restore_debloat() {
  local p ok=1
  for p in $(sh "cat $DEBLOATED 2>/dev/null || true"); do
    [[ "$(sh pm enable "$p" || true)" == *"new state: enabled"* ]] || { failed+=" $p"; ok=0; }
  done
  if [[ $ok == 1 ]]; then sh rm -f "$DEBLOATED"; fi
}
resolve_home() { sh cmd package resolve-activity --brief $HOME_INTENT | tail -1 | tr -d ' ' || true; }

if [[ $revert == 1 ]]; then
  failed=""
  restore_debloat
  state=$(sh "cat $TV_STATE 2>/dev/null || true")
  if [[ -n "$state" ]]; then
    setup=0
    for p in $(tail -n +2 <<<"$state" | sed '1!G;h;$!d'); do
      if [[ "$p" == "$SETUPWRAITH" ]]; then setup=1; continue; fi
      [[ "$(sh pm enable "$p" || true)" == *"new state: enabled"* ]] || failed+=" $p"
    done
    stock=$(head -1 <<<"$state")
    home=0
    if [[ "$stock" != none ]]; then
      if [[ "$(sh cmd package set-home-activity "'$stock'" || true)" == *Success* ]]; then home=1; else failed+=" set-home-activity"; fi
    fi
    # setupwraith only once the TV's home is back, or its recovery screen takes HOME. Another HOME
    # candidate can drop the default, so set it again.
    if [[ $setup == 1 && $home == 1 ]]; then
      [[ "$(sh pm enable "$SETUPWRAITH" || true)" == *"new state: enabled"* ]] || failed+=" $SETUPWRAITH"
      [[ "$(sh cmd package set-home-activity "'$stock'" || true)" == *Success* ]] || failed+=" set-home-activity"
    elif [[ $setup == 1 ]]; then
      # The record stays, so a later revert still knows setupwraith is ours to turn back on.
      echo "$SETUPWRAITH left off: no TV home to restore first."
    fi
    if [[ -n "$failed" ]]; then echo "Not restored:$failed"; exit 1; fi
    if [[ $setup == 0 || $home == 1 ]]; then sh rm -f "$TV_STATE"; fi
    echo "Stock home restored."
    exit 0
  fi
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

google_tv=0
[[ "$(sh pm path "$LAUNCHERX" 2>&1 || true)" == *package:* ]] && google_tv=1
if [[ $google_tv == 1 ]]; then
  disabling="$LAUNCHERX $SETUPWRAITH"
else
  stock=$(resolve_home)
  recorded=$(sh "head -1 $TV_STATE 2>/dev/null || true")
  # After a partial run HOME is already ours, and only the record still names the TV's own home.
  [[ "$stock" == "$PKG/"* && "$recorded" == */* ]] && stock=$recorded
  stock_pkg=""
  # No default home (the chooser), Android's bare fallback or setup's recovery screen: not the TV's own home.
  [[ "$stock" == */* && "$stock" != android/* && "$stock" != *FallbackHome* && "$stock" != "$PKG/"* \
    && "$stock" != "$SETUPWRAITH/"* ]] && stock_pkg=${stock%%/*}
  # Homes that are also the TV's settings, Wi-Fi or Bluetooth (Fire TV, Hotack's LuminaOS): off, the TV is unusable.
  if [[ "$stock_pkg" =~ ^(android|com\.android\.(systemui|settings|tv\.settings)|com\.amazon\..*|com\.htc\.luminaos)$ ]]; then
    echo "$stock_pkg is this TV's home and more: not turned off."
    exit 1
  fi
  candidates=""
  # setupwraith first: with the home off and it on, its RecoveryActivity takes HOME, and a restart there
  # factory-reset a Xiaomi (free-my-tv, MiTV-MOSR4).
  [[ "$(sh cmd package query-activities --brief $HOME_INTENT)" == *"$SETUPWRAITH/"* ]] && candidates+=" $SETUPWRAITH"
  candidates+=" $stock_pkg"
  [[ "$stock_pkg" != "$TV_LAUNCHER" ]] && candidates+=" $TV_LAUNCHER"
  candidates+=" $TV_RECOMMENDATIONS"
  enabled=$(sh pm list packages -e)
  disabling=""
  for p in $candidates; do grep -qx "package:$p" <<<"$enabled" && disabling+=" $p"; done
fi

# Disabling an app deletes the Wi-Fi networks it saved (usually setupwraith, from first setup).
wifi=$(sh "dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+' || true")
if [[ "$wifi" != *cname=* && "$wifi" != *"Wi-Fi is"* && "$(sh settings get global wifi_on)" != 0 ]]; then
  echo "Could not check which app saved the Wi-Fi network, so nothing changed. Try again in a minute."
  exit 1
fi
owners=$(grep -o 'cname=[^ ]*' <<<"$wifi" | sed 's/cname=//' | sort -u || true)
for p in $disabling; do
  if grep -qx "$p" <<<"$owners"; then
    echo "The Wi-Fi network was saved by $p and would be deleted."
    echo "Forget the network in Settings, connect again, then rerun this script."
    echo "Networks out of range are not listed there: adb shell cmd wifi list-networks, then adb shell cmd wifi forget-network ID."
    exit 1
  fi
done

if [[ $google_tv == 0 ]]; then
  # A rerun finds the stock home already off, so the first record is the one to keep.
  first=none
  [[ -n "$stock_pkg" ]] && first=$stock
  sh "[ -e $TV_STATE ] || printf '%s\\n' '$first' $disabling > $TV_STATE"
  [[ "$(sh cmd package set-home-activity "$HOME_ACTIVITY" || true)" == *Success* ]] || { echo "set-home-activity failed"; exit 1; }
  for p in $disabling; do
    [[ "$(sh pm disable-user --user 0 "$p" || true)" == *disabled-user* ]] || { echo "Could not turn off $p"; exit 1; }
  done
  home=$(resolve_home)
  if [[ "$home" != "$PKG/"* ]]; then
    echo "HOME is still $home. Candidates:"
    sh cmd package query-activities --brief $HOME_INTENT
    exit 1
  fi
  debloat || echo "Debloat stopped."
  sh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  echo "TV Home is now the home screen. Revert with: $0 --revert ${target}"
  exit 0
fi

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
debloat || echo "Debloat stopped."
sh am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "TV Home is now the home screen. Revert with: $0 --revert ${target}"
