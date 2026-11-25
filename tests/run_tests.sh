#!/usr/bin/env bash
# Runs the Robolectric unit tests against the classes from ./build.sh. Needs JDK 17 and ANDROID_HOME.
# The first run downloads about 500 MB of test libraries (mostly Robolectric's Android 14 runtime).
set -e
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"; AJ="$SDK/platforms/android-34/android.jar"
[ -d out/cls ] || { echo "run ./build.sh first"; exit 2; }
[ -d .testdeps/libs ] || python3 tools/fetch_test_deps.py
rm -rf out/tcls && mkdir -p out/tcls
APPCP="out/cls:"
APPJARS="$(ls .deps/jars/*.jar | tr '\n' ':')"
TESTCP="$(ls .testdeps/libs/*.jar | tr '\n' ':')"
javac -nowarn -cp "$APPCP$TESTCP$APPJARS$AJ" -d out/tcls tests/*.java
fail=0
for t in ProviderTest SharerTest LauncherTest MemTest OverloadTest AttachTest InitSmoke VoiceTest SchedTest AppCheckCfg; do
  res=$(timeout 120 java --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -Drobolectric.offline=true -Drobolectric.dependency.dir=.testdeps/libs -cp "out/tcls:$APPCP$TESTCP$APPJARS$AJ" org.junit.runner.JUnitCore com.roshanai.app.$t 2>&1 | grep -E '^OK|^Failures|^Tests run' | head -2 | tr '\n' ' ')
  echo "$t: ${res:-NO RESULT}"; case "$res" in OK*) ;; *) fail=1;; esac
done
exit $fail
