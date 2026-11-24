# care

Parent mode (SENTRA Care) behind the screens: following one or more children on the relay,
receiving alarms by live stream or push, the full-screen alarm, and helpers for the history views.
Part of [`com.sentra`](../README.md); the screens themselves are `CareScreen.kt` in [`ui`](../ui/README.md).

## Contents

| File | What it does |
|---|---|
| `CareManager.kt` | Data classes from the relay (`RemoteEvent`, `TrackPoint`, `ChildStatus`, `WatchedChild`, `StreamState`) and `CareManager`: link with an invite code, read `/v1/dashboard` and tracks, keep the SSE stream `/v1/stream` open (resuming with `Last-Event-ID`), raise alerts, acknowledge, sign out, send the FCM token. Adapted from fall-bracelet's `CaregiverManager` |
| `CareService.kt` | `CareService`: foreground service that keeps the stream open in the background when push is not available. Also `ForegroundStartGate` (a stop that arrives before `startForeground()` is deferred, or Android kills the app) and `AckReceiver` (the Acknowledge button on an alarm notification) |
| `FcmService.kt` | Firebase Cloud Messaging receiver; passes data-only messages and new tokens to `CareManager`. Only active when the app is built with `app/google-services.json` |
| `AlarmActivity.kt` | Full-screen alarm over the lock screen with "I've seen it" and "View on map" |
| `HistoryView.kt` | Pure helpers: `HistoryMode` and `historyPeriod` (day / week / all, matching the web page), `dayName`, `ago` ("5 min ago"), and `careTrips`, which groups the event feed into trips (NAV_START to ARRIVED or NAV_STOP) for Trip History |

## How it works

`CareManager` (created in `SentraApp`) runs for the life of the process. While the app is visible,
or when no push token exists, it holds the live stream; otherwise alarms arrive by FCM.
`StreamState` (OFF, CONNECTING, LIVE, PUSH) is shown on the Dashboard. Each incoming event goes
through [`alert.AlertRules`](../alert/README.md) to decide alarm, ring once, notify or history only.

## Dependencies & relationships

- Uses [`relay`](../relay/README.md) (`RelayClient`, `RelayFinder`, `RelayDiscovery`), [`alert`](../alert/README.md), [`data.SettingsRepository`](../data/README.md) and [`nav.GeoPoint`](../nav/README.md).
- External: Firebase Messaging (optional at runtime), the relay's `/v1/*` API.
- Used by `AppViewModel` and `CareScreen` in [`ui`](../ui/README.md), and `MainActivity` (tells it when the app is visible).
- Tested in `HistoryViewTest`, `CareTripsTest`, `ForegroundStartGateTest` and `UploaderTest` ([`app/src/test`](../../../../../test/README.md)).
