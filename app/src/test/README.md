# test

JVM unit tests (JUnit 4, no device or emulator) for the parts of the app that are kept free of
Android types. Part of the [`app`](../../README.md) module; tests the code in
[`com.sentra`](../main/java/com/sentra/README.md).

## Contents

| File (`java/com/sentra/`) | What it tests |
|---|---|
| `NavEngineTest.kt` | [`nav`](../main/java/com/sentra/nav/README.md): distance, bearing, turn classification, the §9.5 state machine |
| `SentraProtocolTest.kt` | [`ble.SentraProtocol`](../main/java/com/sentra/ble/README.md): every message of Tabel 19 and 20, exactly as the firmware has them |
| `FakeSleeveTest.kt` | [`ble.FakeSleeve`](../main/java/com/sentra/ble/README.md) behaves like the firmware (coroutines test) |
| `UploaderTest.kt` | [`data.Uploader`](../main/java/com/sentra/data/README.md), what goes over the wire to the relay, and [`alert.AlertRules`](../main/java/com/sentra/alert/README.md) |
| `HistoryViewTest.kt` | [`care`](../main/java/com/sentra/care/README.md) history periods and `ago()` |
| `CareTripsTest.kt` | `care.careTrips`: grouping the event feed into trips |
| `ForegroundStartGateTest.kt` | `care.ForegroundStartGate`: a stop during service start-up waits for `startForeground()` |

## Usage notes

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

`unitTests.isReturnDefaultValues = true` in [`app/build.gradle.kts`](../../README.md) makes Android
stubs such as `android.util.Log` return defaults; the real `org.json` is added for tests only.
