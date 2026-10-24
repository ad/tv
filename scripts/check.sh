#!/usr/bin/env bash
# android.jar holds only stubs that throw, so these checks cover logic that touches neither Android nor org.json.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AJ="$SDK/platforms/android-36/android.jar"
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
[ -d out/classes ] || { echo "Run ./build.sh first: the checks link against its classes"; exit 1; }

rm -rf out/check && mkdir -p out/check
"${JBIN}javac" -nowarn -source 17 -target 17 -classpath "out/classes:$AJ" -encoding UTF-8 -d out/check \
  $(find app/src/test -name '*.java')
"${JBIN}java" -cp "out/check:out/classes:$AJ" dev.ad.tv.Checks
