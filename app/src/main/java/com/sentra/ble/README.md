# ble

The phone's side of the Bluetooth Low Energy link to the sleeve (XIAO ESP32-C3), following the
contract of Desain Teknis Bab 8. Also holds a simulated sleeve so everything can be tried without
hardware. Part of [`com.sentra`](../README.md).

## Contents

| File | What it does |
|---|---|
| `SentraProtocol.kt` | The contract: service and characteristic UUIDs, `SleeveCommand` (Tabel 19; the enum name is the text sent), `SleeveMessage` (Tabel 20: `ACK:`, `ERR:`, `D:`, `BTN:`, READY), `Intensity`, `LinkState`, and `encode` / `parse` |
| `SentraConnection.kt` | GATT client for one sleeve, adapted from fall-bracelet's `BraceletConnection`. Serialises every GATT operation, writes with response, and reconnects with autoConnect after a drop or when Bluetooth comes back |
| `BleScanner.kt` | Scans for anything advertising the SENTRA service or a name starting with `SENTRA`; an exact name (e.g. `SENTRA-01`) narrows it to one sleeve |
| `SleeveLink.kt` | `SleeveLink` (the interface the rest of the app uses), `FakeSleeve` (behaves like the firmware as seen from the phone: ACK for every command but PING, `D:` every 250 ms, button presses, navigation that stops after 6 s without a message) and `SleeveHub` (switches between real and fake without the rest of the app noticing) |

## Key concepts

- The rest of the app only sees `SleeveHub`: `link: StateFlow<LinkState>`, `messages: SharedFlow<SleeveMessage>` and `send(command)`.
- The firmware ends navigation after 6 s without a message, so `ChildManager` sends PING every 2 s while connected.
- Only one phone can be connected to a sleeve at a time.

## Dependencies & relationships

- Android Bluetooth APIs and coroutines only.
- Used by `ChildManager` (in [`com.sentra`](../README.md)), [`nav`](../nav/README.md) (the commands it emits) and the Home, Status and Demo screens in [`ui`](../ui/README.md).
- Tested in `SentraProtocolTest` and `FakeSleeveTest` ([`app/src/test`](../../../../../test/README.md)). Change `SentraProtocol` only together with the firmware.
