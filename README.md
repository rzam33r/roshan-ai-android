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

## Releasing an update (the green light)
The app checks this repo's latest GitHub Release when it opens (at most every 6 hours). If it is newer, a green light shows next to "ROSHAN AI". Tapping it shows the notes, then downloads and installs.

A release must be:
1. Tag `v<versionCode>`, for example `v24` for versionCode 24 in `VERSION`. Not a draft or pre-release.
2. One asset named `RoshanAI-<anything>.apk`, **signed with the same key** as the installed app. An unsigned or differently signed APK is refused by the app and by Android.
3. The release description is shown as "What's new" (plain text, first 600 characters).

Steps: raise `VERSION`, build (`bash build.sh`, JDK 17), sign with your key (`KEYSTORE=... KS_PASS_FILE=... bash build.sh`), then create the release on GitHub and attach `out/RoshanAI-signed.apk` renamed to `RoshanAI-<code>.apk`.

The app does not declare `REQUEST_INSTALL_PACKAGES` (owner has not approved it), so UPDATE NOW opens the GitHub release page and the signed APK is installed by hand. If that permission is approved and added to `AndroidManifest.xml`, the app installs the update itself after checking package, version and signing certificate; Android still shows its own confirmation.
