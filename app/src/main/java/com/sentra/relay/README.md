# relay (app side)

The app's HTTP client for the [relay server](../../../../../../../server_relay/README.md): the child's
phone uploads and invites, and finding a relay on the local WiFi. Part of [`com.sentra`](../README.md).
The parent's side of the relay lives in [`care`](../care/README.md).

## Contents

| File | What it does |
|---|---|
| `RelayClient.kt` | Minimal JSON-over-`HttpURLConnection` client (`get`, `post`, `put`, `delete`, `open` for the SSE stream). `RelayException.status` 0 means the relay could not be reached |
| `ChildRelay.kt` | The child phone's calls: register, rename, upload events and locations (it is the `TelemetrySink` of [`data.Uploader`](../data/README.md)), heartbeat, invite codes, list and remove linked parents |
| `RelayDiscovery.kt` | `RelayDiscovery` asks every address of the phone's private /24 networks for `/v1/health` and keeps the first that answers; `RelayFinder` re-finds the relay after its address changed, at most once a minute |

## Dependencies & relationships

- No HTTP library: platform `HttpURLConnection` and `org.json`.
- Reads tokens and the relay address from [`data.SettingsRepository`](../data/README.md).
- Used by `ChildManager`, `CareManager` (`RelayClient`, `RelayFinder`) and `AppViewModel`.
- The API (a "child" is the child's phone, a "parent" a parent's phone) is documented in [`server_relay/README.md`](../../../../../../../server_relay/README.md#api).
