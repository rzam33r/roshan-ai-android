#!/usr/bin/env bash
# Builds Roshan AI without Gradle. Needs JDK 17, Android SDK build-tools 34.0.0 and platform android-34,
# plus Python 3 and internet for tools/fetch_deps.py. Output: out/RoshanAI-unsigned-aligned.apk
# Signing is a separate step and uses a key that is NOT in this repo: set KEYSTORE and KS_PASS_FILE to sign.
set -eo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"; [ -n "$SDK" ] || { echo "set ANDROID_HOME"; exit 2; }
BT="$SDK/build-tools/34.0.0"; AJ="$SDK/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] && [ -f "$AJ" ] || { echo "missing build-tools 34.0.0 or android-34"; exit 2; }
read -r VCODE VNAME < VERSION
[ -d .deps/jars ] || python3 tools/fetch_deps.py
X=.deps/x
rm -rf out && mkdir -p out/comp out/gen out/cls
for d in $X/*/; do n=$(basename "$d"); [ -d "$d/res" ] && [ -n "$(ls -A "$d/res")" ] && "$BT/aapt2" compile --dir "$d/res" -o out/comp/$n.zip || true; done
"$BT/aapt2" compile --dir res -o out/comp/app.zip
PK=$(for d in $X/*/; do grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2; done | sort -u | grep -v '^com.roshanai' | tr '\n' ':' | sed 's/:$//')
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --java out/gen --extra-packages "$PK" --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34 --version-code "$VCODE" --version-name "$VNAME" $(ls out/comp/*.zip | grep -v app.zip) out/comp/app.zip
CP="$AJ:$(ls .deps/jars/*.jar | tr '\n' ':')"
javac -source 8 -target 8 -nowarn -cp "$CP" -d out/cls $(find out/gen src -name '*.java') > out/javac.log 2>&1 || { cat out/javac.log; echo 'BUILD FAILED: javac'; exit 1; }
(cd out/cls && jar cf ../app.jar .)
"$BT/d8" --release --min-api 24 --lib "$AJ" --output out out/app.jar .deps/jars/*.jar > out/d8.log 2>&1 || { tail -20 out/d8.log; echo 'BUILD FAILED: d8'; exit 1; }
[ -s out/classes.dex ] || { echo 'BUILD FAILED: no classes.dex'; exit 1; }
for c in MainActivity Launcher Sharer SafeShareProvider; do
  [ -f out/cls/com/roshanai/app/$c.class ] || { echo "BUILD FAILED: missing class $c"; exit 1; }
  grep -aqF "Lcom/roshanai/app/$c;" out/classes.dex || { echo "BUILD FAILED: $c not in dex"; exit 1; }
done
cp out/base.apk out/unsigned.apk
(cd out && zip -q -u unsigned.apk classes*.dex)
mkdir -p out/a/assets && cp assets/app.html out/a/assets/ && (cd out/a && zip -qr ../unsigned.apk assets)
"$BT/zipalign" -f 4 out/unsigned.apk out/RoshanAI-unsigned-aligned.apk
if [ -n "$KEYSTORE" ] && [ -n "$KS_PASS_FILE" ]; then
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "file:$KS_PASS_FILE" --out out/RoshanAI-signed.apk out/RoshanAI-unsigned-aligned.apk
  "$BT/apksigner" verify --print-certs out/RoshanAI-signed.apk | head -3
fi
"$BT/aapt" dump badging out/RoshanAI-unsigned-aligned.apk | grep -E 'package:|uses-permission'
sha256sum out/classes.dex out/RoshanAI-unsigned-aligned.apk
