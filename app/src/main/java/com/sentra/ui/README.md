# ui

All Jetpack Compose screens for both modes, the shared components and theme, the OpenStreetMap
view, and the single `AppViewModel` that connects them to the managers. Part of [`com.sentra`](../README.md).
UI text is Bahasa Indonesia.

## Contents

| File | What it does |
|---|---|
| `MainActivity.kt` | The only launcher activity: light system bars, asks the permissions the chosen mode needs, hosts `App` |
| `App.kt` | `App` (first-launch `ModeScreen`, then mode Anak or SENTRA Care), `ChildApp` (four tabs, a page stack of `ChildPage`s, and the navigation / arrival screens over everything during a trip) |
| `AppViewModel.kt` | One view model for both modes: exposes `ChildManager` and `CareManager` state, route recording (§9.4), invites, linking, test log |
| `HomeScreen.kt` | Beranda: logo, sleeve card, Mulai Navigasi, Lokasi Live and Rute tiles, Status Navigasi with a speaker button; also `RelayLine`, `ObstacleReadout`, `SpeakButton` |
| `RouteScreen.kt` | Rute: saved routes as destinations with a filter box, route detail with map and Mulai Perjalanan, Riwayat Rute, and Rekam Rute |
| `NavigationScreen.kt` | Navigasi (instruction, map, time and distance left) and Tiba di Tujuan; helpers `navInstruction`, `remainingM`, `distanceText`, `etaText` (`WALK_SPEED_MS` = 0.9 m/s) |
| `LiveLocationScreen.kt` | Lokasi Live / Peta tab (this phone on the map) and Status Perangkat (sleeve drawing, obstacle, intensity, last ACK, way into Mode Demo) |
| `DemoScreen.kt` | Mode Demo (§9.6): every sleeve command by hand, intensity, automatic route simulation, sleeve replies |
| `SettingsScreen.kt` | Setelan of mode Anak: alias, parents and relay, simulation switches, CSV test log, sleeve, mode switch |
| `CareScreen.kt` | SENTRA Care: link screen, Dashboard, Peta Live, Riwayat Perjalanan, Alarm, Setelan |
| `SetupChecks.kt` | Finds phone settings that would stop SENTRA working (permissions, GPS off, notifications, battery saver, full-screen intent, internet) and shows them with a fix button |
| `OsmMap.kt` | osmdroid `MapView` in Compose: marker with accuracy circle, recorded route, walked track; follows the marker until the user drags |
| `Components.kt` | Shared pieces: `Card`, `TopBar`, `ScreenTitle`, `PrimaryButton`, `SoftButton`, `ListRow`, `Pill`, `StatusDot`, `IconBubble`, `BottomTabs`, `PageBackground`, and the drawn brand marks `SentraMark` / `SentraLogo` / `Avatar` |
| `theme/Theme.kt` | `Tokens` (colours and gradients: purple for SENTRA, pink for SENTRA Care), the Material 3 light scheme and typography, `SentraTheme` |

## How it works

- **Mode Anak** tabs: Beranda, Peta, Status, Setelan. Rute, route detail, Lokasi Live, Riwayat Rute, Rekam Rute and Mode Demo open as pages on top (the stack is saved as text so it survives rotation). While a trip runs, `NavigationScreen` covers everything; back hides it until the next trip.
- **SENTRA Care** tabs: Beranda (Dashboard), Riwayat, Alarm, Setelan; Peta Live opens from the Dashboard.
- Accessibility (§9.7): text under every tab icon, live regions on changing status, headings, and 48 dp touch targets.

## Dependencies & relationships

- Compose Material 3, Material Icons (core and extended), activity-compose, lifecycle; osmdroid for maps (no API key).
- Reads from `ChildManager` and `CareManager` through `AppViewModel`; uses [`ble`](../ble/README.md), [`nav`](../nav/README.md), [`data`](../data/README.md), [`care`](../care/README.md) and [`relay`](../relay/README.md) types.
- `AlarmActivity` in [`care`](../care/README.md) also uses `SentraTheme` and `Tokens`.
