# src

Source sets of the [`app`](../README.md) module.

## Contents

| Path | What it is |
|---|---|
| `main/AndroidManifest.xml` | Permissions (Bluetooth, location, notifications, foreground services, boot, full-screen intent), `SentraApp`, `MainActivity`, `SentraService`, `CareService`, `AlarmActivity`, `FcmService`, `AckReceiver`, `BootReceiver` and the `FileProvider` |
| [`main/java/com/sentra/`](main/java/com/sentra/README.md) | All Kotlin source |
| [`main/res/`](main/res/README.md) | Icons, colours, theme, strings, file paths |
| `debug/AndroidManifest.xml` | Debug only: allows plain HTTP, so a relay on the local network works without TLS |
| [`test/`](test/README.md) | JVM unit tests |

## Key concepts

All builds start at the VPS relay, `https://relay.sentra-relay.site`. The address can't be changed in
the app (`default_relay_url`); plain HTTP works in debug builds only.
