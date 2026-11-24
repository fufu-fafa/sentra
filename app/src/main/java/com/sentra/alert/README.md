# alert

What a parent's phone (SENTRA Care) does with each event the relay reports: the event types, which
of them ring, and the Android notifications and alarm channels. Part of [`com.sentra`](../README.md).

## Contents

| File | What it does |
|---|---|
| `AlertRules.kt` | `CareEventType` (every relay event type with its English label, `isAlarm`, `isGood`), `CareAlert` (ALARM, RING_ONCE, NOTIFY, HISTORY) and `AlertRules`, which maps a type to a `CareAlert` and gives the alarm screen's title. Free of Android types, so it is unit tested |
| `AlertDispatcher.kt` | Creates the notification channels (`alarm`, `arrival`, `info`, `service`) and posts them: full-screen alarm notifications, ring-once, quiet info, and the "Child's phone offline" notification |

## How it works

| Type | `CareAlert` |
|---|---|
| `OFF_ROUTE`, `SLEEVE_DISCONNECTED`, `OFFLINE` | ALARM: full-screen [`AlarmActivity`](../care/README.md), rings until acknowledged |
| `ARRIVED`, `TEST` | RING_ONCE |
| `NAV_START` | NOTIFY |
| everything else | HISTORY only |

`CareEventType.isAlarm` must match `ALARM_TYPES` in [`server_relay/server.mjs`](../../../../../../../server_relay/README.md).

## Dependencies & relationships

- Uses `care.RemoteEvent` and `care.AlarmActivity`.
- Used by [`care`](../care/README.md) (`CareManager`, `CareService`, `AckReceiver`), `SentraApp` (creates the channels at start-up) and the SENTRA Care screens in [`ui`](../ui/README.md).
- Tested in `UploaderTest` ([`app/src/test`](../../../../../test/README.md)).
