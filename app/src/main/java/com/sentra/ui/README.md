# ui

All Jetpack Compose screens for both modes, the shared components and theme, the OpenStreetMap
view, and the single `AppViewModel` that connects them to the managers. Part of [`com.sentra`](../README.md).
UI text is English.

## Contents

| File | What it does |
|---|---|
| `MainActivity.kt` | The only launcher activity: light system bars, asks the permissions the chosen mode needs, hosts `App` |
| `App.kt` | `App` (first-launch `ModeScreen`, then Child mode or SENTRA Care), `ChildApp` (four tabs, a page stack of `ChildPage`s, and the navigation / arrival screens over everything during a trip) |
| `AppViewModel.kt` | One view model for both modes: exposes `ChildManager` and `CareManager` state, route recording (§9.4), invites, linking, test log |
| `HomeScreen.kt` | Home: logo, sleeve card, Start Navigation, Live Location and Routes tiles, Navigation Status with a speaker button; also `RelayLine`, `ObstacleReadout`, `SpeakButton` |
| `RouteScreen.kt` | Routes: saved routes as destinations with a filter box, route detail with map and Start Trip, Route History, and Record Route |
| `NavigationScreen.kt` | Navigation (instruction, map, time and distance left) and You've Arrived; helpers `navInstruction`, `remainingM`, `distanceText`, `etaText` (`WALK_SPEED_MS` = 0.9 m/s) |
| `LiveLocationScreen.kt` | Live Location / Map tab (this phone on the map) and Device Status (sleeve drawing, obstacle, intensity, last ACK, way into Demo Mode) |
| `DemoScreen.kt` | Demo Mode (§9.6): every sleeve command by hand, intensity, automatic route simulation, sleeve replies |
| `SettingsScreen.kt` | Settings of Child mode: alias, parents and relay, simulation switches, CSV test log, sleeve, mode switch |
| `CareScreen.kt` | SENTRA Care: link screen, Dashboard, Live Map, Trip History, Alerts, Settings |
| `SetupChecks.kt` | Finds phone settings that would stop SENTRA working (permissions, GPS off, notifications, battery saver, full-screen intent, internet) and shows them with a fix button |
| `OsmMap.kt` | osmdroid `MapView` in Compose: marker with accuracy circle, recorded route, walked track; follows the marker until the user drags |
| `Components.kt` | Shared pieces: `Card`, `TopBar`, `ScreenTitle`, `PrimaryButton`, `SoftButton`, `ListRow`, `Pill`, `StatusDot`, `IconBubble`, `BottomTabs`, `PageBackground`, and the drawn brand marks `SentraMark` / `SentraLogo` / `Avatar` |
| `theme/Theme.kt` | `Tokens` (colours and gradients: purple for SENTRA, pink for SENTRA Care), the Material 3 light scheme and typography, `SentraTheme` |

## How it works

- **Child mode** tabs: Home, Map, Status, Settings. Routes, route detail, Live Location, Route History, Record Route and Demo Mode open as pages on top (the stack is saved as text so it survives rotation). While a trip runs, `NavigationScreen` covers everything; back hides it until the next trip.
- **SENTRA Care** tabs: Home (Dashboard), History, Alerts, Settings; Live Map opens from the Dashboard.
- Accessibility (§9.7): text under every tab icon, live regions on changing status, headings, and 48 dp touch targets.

## Dependencies & relationships

- Compose Material 3, Material Icons (core and extended), activity-compose, lifecycle; osmdroid for maps (no API key).
- Reads from `ChildManager` and `CareManager` through `AppViewModel`; uses [`ble`](../ble/README.md), [`nav`](../nav/README.md), [`data`](../data/README.md), [`care`](../care/README.md) and [`relay`](../relay/README.md) types.
- `AlarmActivity` in [`care`](../care/README.md) also uses `SentraTheme` and `Tokens`.
