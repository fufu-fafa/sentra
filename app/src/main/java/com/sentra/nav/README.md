# nav

The navigation algorithm of Desain Teknis §9.5: flat-earth geometry and the state machine that turns
GPS fixes into sleeve commands, voice prompts and events for the parents. Pure Kotlin with no Android
types, so it is fully unit tested. Part of [`com.sentra`](../README.md).

## Contents

| File | What it does |
|---|---|
| `Geo.kt` | `GeoPoint`, `Turn` (STRAIGHT, LEFT, RIGHT, U_TURN) and `Geo`: distance and bearing on a flat-earth approximation, `turnAt(from, at, to)` to classify a corner, and `offset` (used by the simulated walk) |
| `NavEngine.kt` | `NavState` (IDLE, NAVIGATING, OFF_ROUTE, ARRIVED), `NavAction` (`Send` a command, `Speak` a prompt, `Report` an event), `NavSnapshot` (what the UI shows) and `NavEngine` itself |

## How it works

`NavEngine(route, routeName)` walks the route point by point. The caller feeds it `start()`,
`onFix(position, accuracy, now)`, `onTick(now)` every second and `stop()`; each returns a list of
`NavAction`s for the caller to carry out. `info()` is the sentence read out on a short button press
or the speaker button.

Thresholds (companion object): announce a turn within `R_ANNOUNCE` 15 m, reach a point within
`R_ARRIVE` 6 m, off route after `R_OFF_ROUTE` 15 m farther than the closest approach (with 5 m
hysteresis), ignore fixes worse than 20 m (15 m when recording a route), and send FORWARD 4 s after
passing a point.

Two deliberate departures from the §9.5 blocks are listed in the [root README](../../../../../../../README.md#decisions-beyond-the-design-document).

## Dependencies & relationships

- Uses `ble.SleeveCommand` for the commands it emits.
- Used by `ChildManager` (runs it), `AppViewModel` (route recording thresholds) and the route and navigation screens in [`ui`](../ui/README.md) (`Geo` for distances and turn lists).
- Tested in `NavEngineTest` ([`app/src/test`](../../../../../test/README.md)).
