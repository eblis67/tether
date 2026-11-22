# Building the Android app (this fork)

This fork builds the Tether Android app with GitHub Actions.

- Every push to `main` that touches `android/` runs the `Android APK` workflow.
- The workflow builds `:app:assembleRelease` (no signing key needed: without
  one, the release build falls back to the debug key) and uploads the APK as
  an artifact named `tether-android-apk`.
- Download it from the repo's **Actions** tab > latest run > **Artifacts**.

Manual build (needs JDK 17 + Android SDK):

```sh
cd android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
