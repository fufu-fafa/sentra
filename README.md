# SENTRA

Android app and relay server for SENTRA, the navigation sleeve for blind children: three vibration
motors and an ultrasonic sensor on a XIAO ESP32-C3 (Desain Teknis v1.0). This repository replaces
the MIT App Inventor app of §9 with a native Kotlin app. The BLE contract of Bab 8 and the
navigation algorithm of §9.5 are kept as specified.

- [`app/`](app/README.md): one APK, two modes, chosen on first launch, both in the same purple
  SENTRA design
  - **Child** (SENTRA): the child's phone. Connects to the sleeve, records routes, navigates with
    vibration and voice, keeps a trip history, Demo Mode for the booth.
  - **Parent** (SENTRA Care, pink): dashboard, live map, trip history and full-screen alarms.
- [`server_relay/`](server_relay/README.md): the server between the two.
- [`firmware/`](firmware/README.md): Arduino firmware for the sleeve (XIAO ESP32-C3). Pins are a
  template in `firmware/SENTRA_firmware/pins.h` until the sleeve is wired.

The relay is an add-on. Navigation and the sleeve work with no internet; events and GPS fixes wait
in a queue on the child's phone and are sent when the relay can be reached.

## Tech stack

| Part | Stack |
|---|---|
| Android app | Kotlin 2.3, Jetpack Compose (Material 3), Coroutines/Flow, Room + KSP, DataStore, Play Services Location, osmdroid (OpenStreetMap, no API key), Android TextToSpeech, optional Firebase Cloud Messaging. minSdk 26, targetSdk 36. Gradle 9.3.1, AGP 9.1.1 |
| Relay | Node.js ≥ 22 with built-in `node:sqlite` and `node:http`, Server-Sent Events, optional `firebase-admin` for push |
| Web SENTRA Care | One HTML page served by the relay, Leaflet 1.9.4 |
| Sleeve firmware | Arduino C++ for the XIAO ESP32-C3, esp32 core 3.x, built-in BLE library |

## Directory map

| Path | What it is |
|---|---|
| [`app/`](app/README.md) | Android module: build file, ProGuard rules |
| [`app/src/`](app/src/README.md) | Source sets: manifest, debug overrides (local relay, plain HTTP) |
| [`app/src/main/java/com/sentra/`](app/src/main/java/com/sentra/README.md) | `SentraApp`, `ChildManager` (Child mode engine), GPS source, foreground service, voice |
| [`…/alert/`](app/src/main/java/com/sentra/alert/README.md) | Event types and how a parent's phone reacts to each |
| [`…/ble/`](app/src/main/java/com/sentra/ble/README.md) | BLE contract (Bab 8), GATT client, scanner, simulated sleeve |
| [`…/care/`](app/src/main/java/com/sentra/care/README.md) | SENTRA Care: live stream, push, alarm screen, history helpers |
| [`…/data/`](app/src/main/java/com/sentra/data/README.md) | Room (routes, trips, outbox), settings, uploader, CSV test log |
| [`…/nav/`](app/src/main/java/com/sentra/nav/README.md) | Geometry and the §9.5 navigation state machine |
| [`…/relay/`](app/src/main/java/com/sentra/relay/README.md) | HTTP client for the relay, relay discovery on the WiFi |
| [`…/ui/`](app/src/main/java/com/sentra/ui/README.md) | Compose screens for both modes, theme, map, view model |
| [`app/src/main/res/`](app/src/main/res/README.md) | Icons, colours, window theme |
| [`app/src/test/`](app/src/test/README.md) | JVM unit tests |
| [`gradle/`](gradle/README.md) | Version catalog and wrapper |
| [`server_relay/`](server_relay/README.md) | Relay server, browser SENTRA Care, push, end-to-end test, fake child |
| [`firmware/`](firmware/README.md) | Sleeve firmware, pin template (`pins.h`), BLE contract and tuning (`config.h`) |

Root files: `build.gradle.kts` (plugins only), `settings.gradle.kts` (repositories, `:app`),
`gradle.properties` (4 GB heap, build cache, AndroidX), `gradlew` / `gradlew.bat`, `.gitignore`
(also keeps keys, databases and CSV logs out of git).

## Build

Built and tested with Android Studio's bundled JDK:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. No Firebase setup is needed to
build; see [Push](server_relay/README.md#push-firebase-cloud-messaging-optional).

## Try it without a sleeve, without walking and without a server

1. Start the relay on this computer: `cd server_relay && node --disable-warning=ExperimentalWarning server.mjs`.
2. Install the debug APK on a phone on the same WiFi and choose **Child**.
3. **Settings → Test without hardware**: switch on **Simulated sleeve** and **Simulated GPS**, and tap
   **Add sample route**.
4. **Settings → Parents**: **Find relay on this WiFi**, then **Invite parent** for a 6-digit code.
5. Open `http://<this computer>:8787/` in a browser (or a second phone in mode **Parent**) and
   type the code.
6. **Home → Start Navigation** (it starts the selected route; with none selected it opens **Routes**). The position walks along the route; the phone speaks the turns,
   the simulated sleeve shows which motors would vibrate, and the marker moves on the parents' map.
   **Simulate going off route** raises the OFF_ROUTE alarm.

The simulated sleeve behaves like the firmware as seen from the phone: ACK for every command but
PING, `D:` every 250 ms, `BTN:` messages, and navigation that stops after 6 s without a message.

## With the real sleeve

Switch **Simulated sleeve** off and press **Connect**. The app looks for a device that
advertises the SENTRA service or a name starting with `SENTRA`, connects to the first one and
remembers it; after that it reconnects by itself. At the competition, set **Settings → Sleeve → Only connect to this name** to
`SENTRA-01` so it ignores other sleeves in the room. Only one phone can be connected to a sleeve
at a time, so disconnect nRF Connect first.

## Decisions beyond the design document

- **Off route** is sent once, and when the child is back within the limit the app says
  "back on route" and resumes. The §9.5 blocks would send STOP on every GPS fix.
- The FORWARD that follows a passed point by 4 s is dropped if the next turn was announced in the
  meantime, so it cannot cancel that turn's vibration.
- After the sleeve reconnects during a trip the app sends the current command again, because the
  firmware drops its navigation pattern on disconnect.
- Commands are written with response, so a lost write is noticed. The time from a command to its
  ACK is shown in Demo Mode and logged for §10.3.
- Locations are uploaded only while a trip runs.

## Not done yet

- The sleeve firmware compiles for the XIAO ESP32-C3 (esp32 core 3.3.12) but has never run on
  one; the sleeve is not built yet.
- Never run against the real sleeve. Child mode has run on one phone (Samsung SM-G990E) with the
  simulated sleeve and GPS; SENTRA Care has not been checked on a phone since the redesign.
- Deployment of the relay to the VPS, and Firebase push.
- Pairing by QR code (the 6-digit code works).
