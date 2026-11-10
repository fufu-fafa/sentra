# SENTRA

Android app and relay server for SENTRA, the navigation sleeve for blind children: three vibration
motors and an ultrasonic sensor on a XIAO ESP32-C3 (Desain Teknis v1.0). This repository replaces
the MIT App Inventor app of §9 with a native Kotlin app. The BLE contract of Bab 8 and the
navigation algorithm of §9.5 are kept as specified.

- [`app/`](app): one APK, two modes, chosen on first launch
  - **Anak**: the child's phone. Connects to the sleeve, records routes, navigates with vibration
    and voice, Mode Demo for the booth.
  - **Orang tua (SENTRA Care)**: map, status, history and full-screen alarms.
- [`relay/`](relay): the server between the two ([details](relay/README.md)).

The relay is an add-on. Navigation and the sleeve work with no internet; events and GPS fixes wait
in a queue on the child's phone and are sent when the relay can be reached.

## Build

Built and tested with Android Studio's bundled JDK:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. No Firebase setup is needed to
build; see [Push](relay/README.md#push-firebase-cloud-messaging-optional).

## Try it without a sleeve, without walking and without a server

1. Start the relay on this computer: `cd relay && node --disable-warning=ExperimentalWarning server.mjs`.
2. Install the debug APK on a phone on the same WiFi and choose **Anak**.
3. **Setelan → Uji tanpa alat**: switch on **Sleeve simulasi** and **GPS simulasi**, and tap
   **Tambah rute contoh**.
4. **Setelan → Orang tua**: **Cari relay di WiFi ini**, then **Undang orang tua** for a 6-digit code.
5. Open `http://<this computer>:8787/` in a browser (or a second phone in mode **Orang tua**) and
   type the code.
6. **Beranda → Mulai navigasi**. The position walks along the route; the phone speaks the turns,
   the simulated sleeve shows which motors would vibrate, and the marker moves on the parents' map.
   **Simulasikan keluar rute** raises the KELUAR_RUTE alarm.

The simulated sleeve behaves like the firmware as seen from the phone: ACK for every command but
PING, `D:` every 250 ms, `BTN:` messages, and navigation that stops after 6 s without a message.

## With the real sleeve

Switch **Sleeve simulasi** off and press **Sambungkan**. The app looks for a device that
advertises the SENTRA service or a name starting with `SENTRA`, connects to the first one and
remembers it; after that it reconnects by itself. At the competition, set **Setelan → Sleeve → Hanya sambung ke nama ini** to
`SENTRA-01` so it ignores other sleeves in the room. Only one phone can be connected to a sleeve
at a time, so disconnect nRF Connect first.

## Where things are

| Package | Contents |
|---|---|
| `ble/` | `SentraProtocol` (Bab 8: UUIDs, commands, message parser), `SentraConnection` (GATT client), `BleScanner`, `FakeSleeve` |
| `nav/` | `Geo` (flat-earth distance and bearing, turn classification) and `NavEngine` (the §9.5 state machine). Pure Kotlin, unit tested |
| `loc/` | Fused GPS at 1 s, and the simulated walk |
| `ChildManager` | Mode Anak: sleeve link, 2 s PING, navigation, voice, upload loop |
| `service/` | Foreground service that keeps all of that alive with the screen off |
| `data/` | Room (routes, event and location outbox), settings, `Uploader`, `TestLogger` (CSV for Bab 10) |
| `relay/` | HTTP client for the relay, relay discovery on the local network |
| `care/`, `alert/` | SENTRA Care: live stream, push, alarm screen, notification rules |
| `ui/` | Compose screens for both modes, the OpenStreetMap view |

## Decisions beyond the design document

- **Keluar rute** is sent once, and when the child is back within the limit the app says
  "kembali ke rute" and resumes. The §9.5 blocks would send STOP on every GPS fix.
- The FORWARD that follows a passed point by 4 s is dropped if the next turn was announced in the
  meantime, so it cannot cancel that turn's vibration.
- After the sleeve reconnects during a trip the app sends the current command again, because the
  firmware drops its navigation pattern on disconnect.
- Commands are written with response, so a lost write is noticed. The time from a command to its
  ACK is shown in Mode Demo and logged for §10.3.
- Locations are uploaded only while a trip runs.

## Not done yet

- Never run on a phone or against the real sleeve: so far it compiles, its unit tests pass and the
  relay is tested end to end.
- Deployment of the relay to the VPS, and Firebase push.
- Pairing by QR code (the 6-digit code works).
