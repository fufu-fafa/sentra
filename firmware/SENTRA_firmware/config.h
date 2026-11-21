/*
 * SENTRA - BLE contract and tuning parameters
 * ============================================================================
 * Pins are in pins.h. This file holds:
 *   1. The BLE contract (Bab 8). It MUST match the Android app
 *      (app/src/main/java/com/sentra/ble/SentraProtocol.kt). Do not change it
 *      on one side only.
 *   2. Parameters that are safe to tune (Tabel 17, §7.6).
 * ============================================================================
 */
#pragma once
#include <stdint.h>

// ============================================================================
// 1. BLE CONTRACT (Tabel 18). Change only together with the app.
// ============================================================================
// Change the number when there is more than one sleeve (SENTRA-02, ...).
// The app finds any name starting with "SENTRA".
#define DEVICE_NAME       "SENTRA-01"
#define SERVICE_UUID      "3d0dc036-a075-4f06-ba4a-9991669c7a8c"
#define CMD_CHAR_UUID     "003abf21-74a3-465f-b192-017cea87380b"  // phone -> ESP32, WRITE / WRITE_NR
#define STATUS_CHAR_UUID  "c8ae039a-04b6-42d5-9b16-4ea56ce8eb1b"  // ESP32 -> phone, READ / NOTIFY

constexpr int MAX_MESSAGE_LEN = 20;   // one BLE packet (§8.3)
constexpr int NO_OBSTACLE = 999;      // D:999 = nothing in range

// ============================================================================
// 2. TUNING (Tabel 17)
// ============================================================================
// --- Obstacle zones (cm) ---
constexpr int DIST_FAR    = 150;  // < 150 cm: JAUH   (slow taps)
constexpr int DIST_NEAR   = 100;  // < 100 cm: DEKAT  (fast taps)
constexpr int DIST_DANGER = 50;   // <  50 cm: BAHAYA (continuous, navigation held back)
constexpr int HYSTERESIS  = 8;    // cm to go past a limit before leaving a zone

// --- Sensor ---
constexpr unsigned long SENSOR_INTERVAL_MS = 70;     // >= 60 ms, or the previous echo is still around
constexpr unsigned long ECHO_TIMEOUT_US    = 12000;  // 12 ms ~ 2 m; beyond that counts as "nothing"
constexpr int MIN_VALID_CM = 3;                      // closer than this = false echo

// --- Motors (PWM) ---
constexpr uint32_t PWM_FREQ  = 20000;  // 20 kHz: above hearing, the motors do not whine
constexpr uint8_t  PWM_RES   = 8;      // 8 bit -> duty 0..255
constexpr uint8_t  DUTY_LOW  = 150;    // ~59% (soft)
constexpr uint8_t  DUTY_HIGH = 200;    // ~78% (strong). Max 210: 3 V motors on 5 V
static_assert(DUTY_HIGH <= 210 && DUTY_LOW <= DUTY_HIGH, "config.h: duty too high for 3 V motors on 5 V");

// --- Connection ---
constexpr unsigned long APP_TIMEOUT_MS     = 6000;  // no message for 6 s while navigating -> stop
constexpr unsigned long STATUS_INTERVAL_MS = 250;   // send D:<cm> this often
constexpr unsigned long READVERTISE_DELAY_MS = 300;

// --- Button ---
constexpr unsigned long DEBOUNCE_MS   = 30;
constexpr unsigned long LONG_PRESS_MS = 1000;

// --- Serial Monitor ---
constexpr unsigned long DEBUG_INTERVAL_MS = 500;  // 0 = no periodic status line
