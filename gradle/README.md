# gradle

Build tooling shared by the whole Gradle project.

## Contents

| File | What it is |
|---|---|
| `libs.versions.toml` | Version catalog: AGP 9.1.1, Kotlin 2.3.21, KSP, Compose BOM 2026.09.00 (Material 3, icons core and extended), Room 2.8.5, DataStore, Lifecycle, coroutines, Firebase BOM, Play Services Location, osmdroid 6.1.20, JUnit |
| `wrapper/gradle-wrapper.properties` | Gradle 9.3.1 distribution for `./gradlew` |
| `wrapper/gradle-wrapper.jar` | The wrapper itself |

## Usage notes

Add or bump a library here, then reference it as `libs.<alias>` in [`app/build.gradle.kts`](../app/README.md).
Repositories (Google, Maven Central, Gradle Plugin Portal) are set in the root `settings.gradle.kts`.
