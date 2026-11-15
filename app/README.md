# app

The Android application module (`:app`, package `com.sentra`): one APK for both the child's phone
and the parents' phones. Kotlin, Jetpack Compose, minSdk 26, targetSdk 36, compileSdk 37.

## Contents

| Path | What it is |
|---|---|
| `build.gradle.kts` | Module build: plugins (Android application, Kotlin Compose, KSP, optional Google Services), SDK levels, release minify with debug signing, dependencies |
| `proguard-rules.pro` | Keeps source file and line numbers in release stack traces; libraries bring their own rules |
| [`src/`](src/README.md) | Source sets: `main`, `debug`, `test` |
| `google-services.json` | Not in git. Optional Firebase config; when present, the Google Services plugin is applied and push works |

## Usage notes

Build the debug APK and run the tests (Android Studio's bundled JDK):

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is written to `build/outputs/apk/debug/app-debug.apk`. Dependency versions live in
[`gradle/libs.versions.toml`](../gradle/README.md). The release build is signed with the debug key
(`signingConfigs.getByName("debug")`), so it is not ready for a store.
