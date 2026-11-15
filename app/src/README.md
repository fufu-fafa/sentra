# src

Source sets of the [`app`](../README.md) module.

## Contents

| Path | What it is |
|---|---|
| `main/AndroidManifest.xml` | Permissions (Bluetooth, location, notifications, foreground services, boot, full-screen intent), `SentraApp`, `MainActivity`, `SentraService`, `CareService`, `AlarmActivity`, `FcmService`, `AckReceiver`, `BootReceiver` and the `FileProvider` |
| [`main/java/com/sentra/`](main/java/com/sentra/README.md) | All Kotlin source |
| [`main/res/`](main/res/README.md) | Icons, colours, theme, strings, file paths |
| `debug/AndroidManifest.xml` | Debug only: allows plain HTTP, so a relay on the local network works without TLS |
| `debug/res/values/relay.xml` | Debug only: `default_relay_url` = `http://10.0.2.2:8787` (the computer running an emulator) |
| [`test/`](test/README.md) | JVM unit tests |

## Key concepts

Release builds have no default relay and require HTTPS; debug builds start at a local relay. A real
phone cannot reach `10.0.2.2`, so it uses **Setelan → Cari relay di WiFi ini** instead.
