# server_relay

The child's phone (mode Anak) uploads trip events, GPS fixes and a heartbeat. The parents' phones
(mode Orang tua, SENTRA Care) get them over a live Server-Sent Events stream and, for alarms, by
Firebase Cloud Messaging push. Forked from the fall-bracelet relay: Node 22+ with its built-in
SQLite; the one package is `firebase-admin`, and without it or its key the relay runs stream-only.

| | |
|---|---|
| `server.mjs` | The relay: HTTP API, SQLite (`node:sqlite`), SSE stream, offline and re-push timers. `PORT` (default 8787), `DB_PATH` (default `relay.db` here), `LAN_ONLY` (default on); `OFFLINE_NAV_MS`, `OFFLINE_IDLE_MS`, `TICK_MS`, `ESCALATE_AFTER_MS` for testing |
| `web/index.html` | SENTRA Care in a browser, served at `/`: map (Leaflet 1.9.4 + OpenStreetMap, loaded from unpkg), status, history, alarm. Same look as the app's SENTRA Care (lavender, pink): per child a card with status, map, Riwayat Perjalanan (trips grouped from the events, like the app) and Alarm. Reads the stream with `fetch` because `EventSource` cannot send the bearer token |
| `push.mjs` | FCM push: high priority for KELUAR_RUTE, TERPUTUS, OFFLINE; normal for ARRIVED, NAV_START, TEST |
| `test.mjs` | End-to-end test against a throwaway database |
| `fake-child.mjs` | A pretend child phone that walks a route, for trying the relay and SENTRA Care without the app |
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
marker walk around Gedung Sate. `--offroute` raises KELUAR_RUTE, `--drop` raises TERPUTUS and
`--silent` stops sending so the relay raises OFFLINE after 60 s.

By default the relay only answers the local network (requests from public addresses get `403`).
On the VPS, behind Caddy and HTTPS, set `LAN_ONLY=0`.

## The app and a local relay

Debug builds of the app start at `http://10.0.2.2:8787` (the computer running an emulator) and
allow plain HTTP. A real phone on the same WiFi finds the relay with **Setelan → Cari relay di
WiFi ini**: it asks every address on its network for `/v1/health`.

## API

A **child** is the child's phone (mode Anak) and a **parent** is a parent's phone (SENTRA Care).
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
| `POST /v1/events/:id/ack` | parent | "Dilihat oleh …" |
| `PUT /v1/push-token` | parent | `{token}`: this phone's FCM token |
| `GET /v1/stream` | parent | SSE: `hello`, `event`, `status`, `location`, `ack`, `offline`, `online`, `unlinked`. `Last-Event-ID` replays events of the last 24 h |

`status` is whatever the child's phone sends; the app sends `navigating`, `navState` (IDLE,
NAVIGATING, OFF_ROUTE, ARRIVED), `sleeveConnected`, `batteryPct`, `obstacleCm`, `simulated`, and
during a trip `routeName`, `route` (`[[lat, lon], …]`), `nextPoint`, `totalPoints`, `distanceM`.

## Event types

| Type | Raised by | On the parents' phones |
|---|---|---|
| `KELUAR_RUTE` | app, when the child is 15 m farther from the next point than the closest they have been | Alarm |
| `TERPUTUS` | app, when the sleeve has been disconnected for 8 s during a trip | Alarm |
| `OFFLINE` | relay, after 60 s without a word from the child's phone during a trip | Alarm |
| `ARRIVED`, `TEST` | app | Rings once |
| `NAV_START` | app | Quiet notification |
| `BELOK_KIRI`, `BELOK_KANAN`, `PUTAR_BALIK`, `KEMBALI_RUTE`, `TERSAMBUNG`, `NAV_STOP` | app | History only |

## Timers

- **Offline.** A child's phone that is navigating and silent for 60 s is marked offline and gets an
  `OFFLINE` alarm event with the last known position. An idle phone is only marked offline after
  15 min, without an alarm. Events, locations and heartbeats all count as a sign of life.
- **Re-push.** An alarm nobody acknowledged is sent again every 2 min, up to 5 times.
- **Alarms that end by themselves.** `TERSAMBUNG` acknowledges open `TERPUTUS` alarms,
  `KEMBALI_RUTE` and `ARRIVED` acknowledge `KELUAR_RUTE`, and a phone that is heard from again
  acknowledges `OFFLINE`. The history shows them as "Dilihat oleh otomatis: …".
- **Privacy.** Location fixes older than 30 days are deleted hourly.

## Push (Firebase Cloud Messaging), optional

Put the Firebase service-account key next to `server.mjs` as `firebase-service-account.json`
(`chmod 600`, never committed; `FIREBASE_KEY` overrides the path), run `npm ci --omit=dev`, and
build the app with `app/google-services.json` for the package `com.sentra`. Without these, SENTRA
Care keeps a live connection through a foreground service and still rings.
