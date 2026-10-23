#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found: set ANDROID_HOME"; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$SDK/platforms/android-36/android.jar"
[ -f "$AJ" ] || { echo "Missing platform: sdkmanager \"platforms;android-36\""; exit 1; }
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.1.$VERSION_CODE}"
SRC=app/src/main

rm -rf out && mkdir -p out/gen/dev/ad/tv out/classes
cat > out/gen/dev/ad/tv/BuildConfig.java <<EOF
package dev.ad.tv;

public final class BuildConfig {
    public static final String APPLICATION_ID = "dev.ad.tv";
    public static final String VERSION_NAME = "$VERSION_NAME";
    public static final int VERSION_CODE = $VERSION_CODE;
}
EOF

"$BT/aapt2" compile --dir "$SRC/res" -o out/res.zip
# openFd() on an asset works only when it is stored uncompressed.
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest "$SRC/AndroidManifest.xml" --java out/gen out/res.zip \
  --min-sdk-version 31 --target-sdk-version 36 -A video -0 mp4 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --replace-version
"${JBIN}javac" -nowarn -source 17 -target 17 -classpath "$AJ" -encoding UTF-8 -d out/classes \
  $(find "$SRC/java" out/gen -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

if [ -n "${KEYSTORE_FILE:-}" ]; then
  "$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "$KEY_ALIAS" \
    --key-pass env:KEY_PASSWORD --out tv-home.apk out/aligned.apk
else
  [ -f debug.keystore ] || "${JBIN}keytool" -genkeypair -keystore debug.keystore -alias debug -keyalg RSA \
    -keysize 2048 -validity 20000 -storepass android -keypass android -dname "CN=TV Home debug" >/dev/null 2>&1
  "$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out tv-home.apk out/aligned.apk
fi
echo "Built tv-home.apk ($(du -h tv-home.apk | cut -f1))"
