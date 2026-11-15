# com.sentra

The Kotlin source of the Android app: one APK with two modes, **Anak** (the child's phone, talks to
the sleeve and navigates) and **Orang tua / SENTRA Care** (a parent's phone, follows the child
through the relay). This folder holds the application wiring, the mode Anak engine, and the
packages below.

## Contents

| Path | What it is |
|---|---|
| `SentraApp.kt` | The `Application`: configures osmdroid, creates `AlertDispatcher` channels, `SettingsRepository`, the database, `ChildManager` and `CareManager`, and starts both. `Context.child` / `Context.care` give any component access to them |
| `ChildManager.kt` | Mode Anak's engine for the life of the process: sleeve link and 2 s PING, scanning, GPS source choice, `NavEngine` and its actions, voice, trip history, Mode Demo sequence, CSV test log, and the relay loop (outbox upload, heartbeat with status, acknowledgements) |
| `loc/LocationSource.kt` | `Fix`, `gpsFixes()` (fused location every 1 s, high accuracy) and `simulatedWalk()` (a pretend walk along a route at 3 m/s, optionally drifting off route) |
| `service/SentraService.kt` | Foreground service (types connectedDevice and location) that keeps the sleeve link, GPS and navigation running with the screen off (§9.8); `BootReceiver` recreates the app on boot and after an update so it reconnects |
| `voice/Speaker.kt` | Short `TextToSpeech` prompts in Bahasa Indonesia; a new prompt replaces one still being spoken (§9.7) |
| [`alert/`](alert/README.md) | Event types and what a parent's phone does with each |
| [`ble/`](ble/README.md) | BLE contract with the sleeve, GATT client, scanner, simulated sleeve |
| [`care/`](care/README.md) | SENTRA Care: live stream, push, alarm screen, history helpers |
| [`data/`](data/README.md) | Room database, settings, outbox uploader, CSV test logger |
| [`nav/`](nav/README.md) | Geometry and the §9.5 navigation state machine (pure Kotlin) |
| [`relay/`](relay/README.md) | HTTP client for the relay and relay discovery on the WiFi |
| [`ui/`](ui/README.md) | Compose screens for both modes, theme, map, `AppViewModel` |

## How it works

Both managers exist on every phone; the stored `AppMode` decides which one does anything.

```
sleeve ⇄ ble.SleeveHub ⇄ ChildManager ⇄ nav.NavEngine
                              │  GPS (loc) · voice · data (Room outbox, trips)
                              ▼
                        relay.ChildRelay ──HTTP──▶ relay server ──SSE / FCM──▶ care.CareManager ──▶ alert, AlarmActivity
                                                                                   ▲
ui (Compose) ◀── AppViewModel ──────────────────────────────────────────────────────┘ and ChildManager
```

`ChildManager.startNavigation` creates a `NavEngine`; every GPS fix and 1 s tick produces
`NavAction`s that `ChildManager` carries out: send to the sleeve, speak, or record an event in the
outbox. The relay loop uploads the outbox every 3 s during a trip (30 s idle) and sends a heartbeat
every 20 s (5 min idle). `SentraService` runs while a sleeve is connected or a trip is active.

## Dependencies & relationships

- External: AndroidX (Compose, Lifecycle, Room, DataStore), Google Play Services Location, Firebase Messaging, osmdroid, kotlinx-coroutines. Versions in [`gradle/libs.versions.toml`](../../../../../../gradle/README.md).
- Components are declared in [`app/src/main/AndroidManifest.xml`](../../../../README.md).
- Tests: [`app/src/test`](../../../../test/README.md).
