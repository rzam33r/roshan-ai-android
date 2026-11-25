# Roshan AI (Android)

Personal Android app `com.roshanai.app`. Source of truth for the app code.

## What is here
- `src/`, `res/`, `assets/app.html`, `AndroidManifest.xml`: the app
- `build.sh`: builds without Gradle (aapt2, javac, d8, zipalign). Output is an **unsigned** APK in `out/`
- `deps.lock`, `tools/fetch_deps.py`: pinned dependencies with SHA-256, downloaded from Google Maven and Maven Central
- `tests/`, `tests.lock`, `tests/run_tests.sh`: Robolectric unit tests
- `.github/workflows/build.yml`: manual build and test run, no signing, no secrets

## Build
Needs JDK 17, Android SDK build-tools 34.0.0 and platform android-34, Python 3.
```
export ANDROID_HOME=/path/to/sdk
bash build.sh
bash tests/run_tests.sh
```
`VERSION` holds versionCode and versionName. Raise versionCode for every release.

## Rules
- Same package and same signing certificate, higher versionCode, so the phone can update in place.
- The signing key and its password are never stored in this repository or in CI. Signing happens outside.
- No new permissions, paid services or terms without the owner's approval.
- Firebase client settings in `MainActivity.java` identify the project. They are not signing secrets.
