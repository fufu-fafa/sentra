# server_relay

The child's phone (Child mode) uploads trip events, GPS fixes and a heartbeat. The parents' phones
(Parent mode, SENTRA Care) get them over a live Server-Sent Events stream and, for alarms, by
Firebase Cloud Messaging push. Forked from the fall-bracelet relay: Node 22+ with its built-in
SQLite; the one package is `firebase-admin`, and without it or its key the relay runs stream-only.

| | |
|---|---|
| `server.mjs` | The relay: HTTP API, SQLite (`node:sqlite`), SSE stream, offline and re-push timers. `PORT` (default 8787), `HOST` (default all interfaces), `DB_PATH` (default `relay.db` here), `LAN_ONLY` (default on), `LINK_FAILS_PER_WINDOW` and `REGISTRATIONS_PER_WINDOW` (default 10 per 15 min per address); `PHOTON_URL`, `VALHALLA_URL`, `ROUTING_USER_AGENT`, `PLANNING_PER_WINDOW` (default 120 per 15 min per phone) for place search and walking routes; `OFFLINE_NAV_MS`, `OFFLINE_IDLE_MS`, `TICK_MS`, `ESCALATE_AFTER_MS` for testing |
| `web/index.html` | SENTRA Care in a browser, served at `/`: map (Leaflet 1.9.4 + OpenStreetMap, loaded from unpkg), status, history, alarm. Same look as the app's SENTRA Care (lavender, pink): per child a card with status, map, Trip History (trips grouped from the events, like the app) and Alerts. Reads the stream with `fetch` because `EventSource` cannot send the bearer token |
| `push.mjs` | FCM push: high priority for OFF_ROUTE, SLEEVE_DISCONNECTED, OFFLINE; normal for ARRIVED, NAV_START, TEST |
| `test.mjs` | End-to-end test against a throwaway database |
| `fake-child.mjs` | A pretend child phone that walks a route, for trying the relay and SENTRA Care without the app |
| `install-windows.bat`, `run-windows.bat` | Install and start the relay on Windows (see below) |
| `sentra-relay.service` | systemd unit for the VPS; replace `YOUR_USER` before installing |
| `package.json`, `package-lock.json` | Node ≥ 22; `npm start` / `npm test` run the server and the test; the one dependency is `firebase-admin` |

The Android side of this API is in [`app/…/relay`](../app/src/main/java/com/sentra/relay/README.md)
(child's phone) and [`app/…/care`](../app/src/main/java/com/sentra/care/README.md) (parents).

## Run it locally

```bash
node --disable-warning=ExperimentalWarning test.mjs
```

```bash
node --disable-warning=ExperimentalWarning server.mjs
```

Nothing needs installing for this: without `node_modules` the relay says
`push: no Firebase key…` and works over the live stream. Then, in a second terminal:

```bash
node fake-child.mjs --offroute
```

It prints a 6-digit invite code. Open `http://localhost:8787/`, type the code, and watch the
marker walk around Gedung Sate. `--offroute` raises OFF_ROUTE, `--drop` raises SLEEVE_DISCONNECTED and
`--silent` stops sending so the relay raises OFFLINE after 60 s.

## Run it on Windows

Two scripts in this folder, double-click them in Explorer:

| | |
|---|---|
| `install-windows.bat` | Once. Installs Node.js LTS with `winget` if it is missing, checks the version (22.13+ for the built-in SQLite), runs `npm ci --omit=dev`, runs `test.mjs`, and, when started as administrator, offers to open port 8787 in Windows Firewall for private networks |
| `run-windows.bat` | Starts the relay, prints the addresses phones on the same WiFi can use, Ctrl+C stops it. `run-windows.bat 9000` picks another port; `PORT`, `DB_PATH`, `LAN_ONLY`, `FIREBASE_KEY` are passed through |

The WiFi must be set to **Private** in Windows (a Public network blocks the phones). The `.bat`
files are kept with CRLF line endings (`.gitattributes`); don't let an editor save them as LF.

By default the relay only answers the local network (requests from public addresses get `403`).
On the VPS, behind Caddy and HTTPS, set `LAN_ONLY=0` and `HOST=127.0.0.1` so the relay is reachable only through Caddy. Behind Caddy the client address comes from the last `X-Forwarded-For` entry (trusted only from loopback); wrong invite codes and registrations are limited per address and answered with 429 and `Retry-After`.

## The app and a local relay

The app starts at the VPS relay, `https://relay.sentra-relay.site`. It has no address field.
To test with a local relay, change `default_relay_url` in `app/src/main/res/values/strings.xml`,
rebuild, and clear the app's data. Plain HTTP works in debug builds only.

## API

A **child** is the child's phone (Child mode) and a **parent** is a parent's phone (SENTRA Care).
The API was forked from fall-bracelet, which called them wearer and caregiver; a database made
before the rename is migrated on start-up (tables, columns and tokens are kept). Every phone gets a random bearer token; only
its SHA-256 is stored.

| Call | Who | What |
|---|---|---|
| `POST /v1/children` | child phone | Register, returns `token` |
| `PUT /v1/children/me` | child | Rename (the alias the parents see) |
| `POST /v1/invites` | child | 6-digit code, single use, 15 min |
| `POST /v1/link` | parent | `{code, name}`; returns a parent token (send an existing one to follow another child) |
| `GET /v1/parents`, `DELETE /v1/parents/:id` | child | List / remove linked parents |
| `POST /v1/events` | child | `{deviceAddress, seq, type, occurredAt, message, batteryPct?, lat?, lon?}`. Idempotent on (child, deviceAddress, seq); the phone assigns `seq` |
| `POST /v1/locations` | child | `{points: [{at, lat, lon, acc?, speed?, bearing?}]}`, up to 500. Idempotent on (child, at) |
| `POST /v1/heartbeat` | child | `{status, acksSince}`; reply carries acknowledgements and the number of linked parents |
| `GET /v1/dashboard` | parent | Each followed child with status, latest fix and the last 100 events (`?limit=` up to 1000) |
| `GET /v1/children/:id/track` | parent | Fixes oldest first; `?since=` (default last 2 h), `?limit=` up to 5000 |
| `POST /v1/events/:id/ack` | parent | "Seen by …" |
| `PUT /v1/push-token` | parent | `{token}`: this phone's FCM token |
| `GET /v1/places` | either | `?q=` (2+ characters), optional `?lat=&lon=` to prefer nearby places. Forwarded to Photon; returns `{places: [{name, detail, lat, lon}]}` |
| `POST /v1/routes/plan` | either | `{from: {lat, lon}, to: {lat, lon}}`, at most 20 km apart. Forwarded to Valhalla (`pedestrian`, type `blind`, favouring sidewalks, avoiding steps); returns `{lengthM, timeS, shape: [[lat, lon]…], maneuvers: [{at, type, instruction}]}` where `at` indexes `shape`. 422 when no walking route exists |
| `GET /v1/stream` | parent | SSE: `hello`, `event`, `status`, `location`, `ack`, `offline`, `online`, `unlinked`. `Last-Event-ID` replays events of the last 24 h |

`status` is whatever the child's phone sends; the app sends `navigating`, `navState` (IDLE,
NAVIGATING, OFF_ROUTE, ARRIVED), `sleeveConnected`, `batteryPct`, `obstacleCm`, `simulated`, and
during a trip `routeName`, `route` (`[[lat, lon], …]`), `nextPoint`, `totalPoints`, `distanceM`.

## Place search and walking routes

The phones never call map services themselves; the relay forwards to [Photon](https://github.com/komoot/photon)
(search) and [Valhalla](https://github.com/valhalla/valhalla) (routing), both open source on
OpenStreetMap data. The defaults are the free public servers (`photon.komoot.io`,
`valhalla1.openstreetmap.de`), which are for testing and light use only. For real use, run your own
and set `PHOTON_URL` / `VALHALLA_URL`: a Valhalla built from just the city's OpenStreetMap extract
is small enough to serve, but building it needs more memory than the 512 MB VPS has, so build the
tiles elsewhere and copy them over. `test.mjs` runs against a stand-in for both services.

## Event types

| Type | Raised by | On the parents' phones |
|---|---|---|
| `OFF_ROUTE` | app, when the child is 15 m farther from the next point than the closest they have been | Alarm |
| `SLEEVE_DISCONNECTED` | app, when the sleeve has been disconnected for 8 s during a trip | Alarm |
| `OFFLINE` | relay, after 60 s without a word from the child's phone during a trip | Alarm |
| `ARRIVED`, `TEST` | app | Rings once |
| `NAV_START` | app | Quiet notification |
| `TURN_LEFT`, `TURN_RIGHT`, `U_TURN`, `BACK_ON_ROUTE`, `SLEEVE_CONNECTED`, `NAV_STOP` | app | History only |

## Timers

- **Offline.** A child's phone that is navigating and silent for 60 s is marked offline and gets an
  `OFFLINE` alarm event with the last known position. An idle phone is only marked offline after
  15 min, without an alarm. Events, locations and heartbeats all count as a sign of life.
- **Re-push.** An alarm nobody acknowledged is sent again every 2 min, up to 5 times.
- **Alarms that end by themselves.** `SLEEVE_CONNECTED` acknowledges open `SLEEVE_DISCONNECTED` alarms,
  `BACK_ON_ROUTE` and `ARRIVED` acknowledge `OFF_ROUTE`, and a phone that is heard from again
  acknowledges `OFFLINE`. The history shows them as "Seen by automatic: …".
- **Privacy.** Location fixes older than 30 days are deleted hourly.

## Push (Firebase Cloud Messaging), optional

Put the Firebase service-account key next to `server.mjs` as `firebase-service-account.json`
(`chmod 600`, never committed; `FIREBASE_KEY` overrides the path), run `npm ci --omit=dev`, and
build the app with `app/google-services.json` for the package `com.sentra`. Without these, SENTRA
Care keeps a live connection through a foreground service and still rings.
