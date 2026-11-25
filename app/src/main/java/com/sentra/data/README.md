# data

Everything the app stores on the phone: the Room database (routes, finished trips, and the outbox
of events and GPS fixes for the relay), the settings, the uploader that empties the outbox, and the
CSV test logger. Part of [`com.sentra`](../README.md).

## Contents

| File | What it does |
|---|---|
| `Db.kt` | Room entities `RouteEntity` (recorded route, points stored as `"lat,lon;…"`), `EventEntity` (outbox event; `id` is the `seq` the relay de-duplicates on), `LocationEntity` (queued fix), `TripEntity` (a finished trip for Route History); DAOs `RouteDao`, `OutboxDao`, `TripDao`; `AppDatabase` (`sentra.db`, version 2) |
| `SettingsRepository.kt` | `AppMode` (CHILD / PARENT), `AppSettings` and their DataStore storage (`settings`): mode, sleeve address and name filter, child alias, relay tokens, selected route, simulation switches |
| `Uploader.kt` | `Outbox` and `TelemetrySink` interfaces and `Uploader`, which sends queued events then fixes, oldest first, stopping at the first failure so order is kept |
| `TestLogger.kt` | CSV per trial in `files/recordings/` for the tests of Desain Teknis Bab 10: obstacle readings, command-to-ACK times, GPS, navigation decisions, link changes |

## Key concepts

- **Outbox first.** Events and fixes are written locally before any network call, so navigation never waits for the relay and nothing is lost offline. `ChildManager` trims queued fixes when the relay stays unreachable.
- **Database versions.** Version 2 added the `trips` table; `MIGRATION_1_2` in `Db.kt` creates it. `exportSchema = false`, so there is no schema history in the repo: add a `Migration` by hand for every future change.
- **Trips** are written by `ChildManager.endTrip()` when a trip arrives (`arrived = true`) or is stopped.

## Dependencies & relationships

- AndroidX Room (with KSP), DataStore Preferences.
- Used by `ChildManager`, `CareManager`, [`relay.ChildRelay`](../relay/README.md) (implements `TelemetrySink`) and `AppViewModel`.
- `Uploader` is tested in `UploaderTest` ([`app/src/test`](../../../../../test/README.md)).
