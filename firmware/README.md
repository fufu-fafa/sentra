# firmware

Arduino firmware for the sleeve: a Seeed Studio XIAO ESP32-C3, an HC-SR04P / RCWL-1601
ultrasonic sensor, three coin vibration motors through a ULN2003A, an optional status LED and an
optional button. Based on Desain Teknis v1.0 §7.5. It speaks the BLE contract of Bab 8, the same
one the app uses in [`SentraProtocol.kt`](../app/src/main/java/com/sentra/ble/SentraProtocol.kt).

## Contents

| File | What it does |
|---|---|
| `SENTRA_firmware/SENTRA_firmware.ino` | The firmware: BLE server, sensor with median-3 filter and zone hysteresis, vibration engine with the §6.3 priorities, button, LED, connection handling |
| `SENTRA_firmware/pins.h` | **Pin template.** Which GPIO is wired to what, LED and button can be switched off with `-1`, polarity of motors / LED / button. Change only this file when the sleeve is wired |
| `SENTRA_firmware/config.h` | BLE name and UUIDs (must match the app) and the tuning values of Tabel 17 |

## Changing pins

The sleeve has not been built yet, so `pins.h` holds the Tabel 11 defaults. The numbers are
ESP32-C3 **GPIO numbers**, not XIAO pad names, so the same file works on an ESP32-C3-DevKitM-1 in
Wokwi. The pad-to-GPIO table is at the top of the file.

The build stops with an error if a pin is used twice, is outside GPIO 0–21, or is GPIO2/GPIO9
(strapping) or GPIO18/GPIO19 (USB). A button on GPIO8 is refused too, since that pin must be HIGH at
power-on.

## Build and upload

Arduino IDE 2.x with the **esp32 core 3.x** by Espressif (§7.1):

1. Open the `SENTRA_firmware` folder (the folder name must match the `.ino`).
2. Tools → Board → esp32 → **XIAO_ESP32C3**, Tools → **USB CDC On Boot → Enabled**.
3. Upload, then open the Serial Monitor at 115200. It prints the pins in use and one status line
   every 500 ms.

With `arduino-cli`:

```bash
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C3:CDCOnBoot=cdc firmware/SENTRA_firmware
```

## Behaviour (Tabel 14, 15, 19, 20)

| Input | Motors | Sent to the phone |
|---|---|---|
| Power on | Self-test LEFT → CENTER → RIGHT | (advertises as `SENTRA-01`) |
| Phone connects | 2 taps, all | `D:<cm>` every 250 ms (`D:999` = nothing) |
| `FORWARD` | 1 tap LEFT+RIGHT, then silent | `ACK:FORWARD` |
| `LEFT` / `RIGHT` | 2 × 250 ms LEFT / RIGHT, every 1.5 s | `ACK:LEFT` / `ACK:RIGHT` |
| `STOP` | All, 500 ms on / 500 ms off | `ACK:STOP` |
| `ARRIVED` | 3 fast taps, navigation ends | `ACK:ARRIVED` |
| `IDLE`, `TEST`, `LOW`, `HIGH` | none / 2 buzzes / soft / strong | `ACK:…` |
| `PING` | – | nothing (only resets the 6 s timeout) |
| Unknown text | – | `ERR:<text>` (cut to 20 characters) |
| Obstacle 100–150 / 50–100 cm | CENTER tap every 900 / 350 ms | |
| Obstacle < 50 cm | CENTER continuous, everything else held back | |
| No message for 6 s while navigating | 2 buzzes LEFT+RIGHT, navigation ends | |
| Button short / long (≥ 1 s) | – / 1–2 buzzes | `BTN:SHORT` / `BTN:LONG,HIGH` or `BTN:LONG,LOW` |
| Phone disconnects | 1 long buzz LEFT+RIGHT, navigation ends, advertises again | |

Commands are case-insensitive. Zones use 8 cm hysteresis: leaving DANGER needs more than 58 cm.

## Differences from the §7.5 listing

- Pins, polarity and the optional LED/button come from `pins.h`, with compile-time checks.
- BLE writes go into a 4-slot queue instead of one buffer, so two commands that arrive within one
  loop pass (for example `LOW` then `TEST`) are both handled.
- Every message to the phone is cut to 20 characters (`ERR:` could exceed it in §7.5).
- A reading above 999 cm is reported as 999.
- The `BLE2902` descriptor is added only on the Bluedroid stack. Core 3.3+ uses NimBLE, which adds
  it by itself and marks `BLE2902` deprecated (§7.7 mentions this warning).

## Verification

- Compiled with `arduino-cli` 1.6.0 and esp32 core 3.3.12 for `XIAO_ESP32C3` (on the `acer`
  machine, copy in `~/sentra/firmware`), with all warnings on: no warnings, 624 KB flash (47 %),
  18 KB RAM (5 %).
- Compiled on a Mac with clang against stub Arduino and BLE headers, then run through 25 timed
  scenarios (the §7.7 list plus the queue, `ERR:` length, button and pin checks). All passed.
- **Never run on an ESP32**, since the sleeve does not exist yet.
