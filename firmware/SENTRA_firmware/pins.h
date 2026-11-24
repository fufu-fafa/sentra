/*
 * SENTRA - pin template
 * ============================================================================
 * The sleeve has not been built yet. When the wiring is final, change the
 * numbers in THIS FILE ONLY; the rest of the firmware reads them from here.
 *
 * The numbers are ESP32-C3 GPIO numbers, not XIAO pad names (D1, D2, ...),
 * so the same file also works on an ESP32-C3-DevKitM-1 (for example in Wokwi).
 *
 * XIAO ESP32-C3 pad -> GPIO:
 *   D0=2  D1=3  D2=4  D3=5  D4=6  D5=7  D6=21  D7=20  D8=8  D9=9  D10=10
 *
 * Do not use (the build stops with an error if you do):
 *   GPIO2, GPIO9   strapping pins: the chip may not start (§5.1). D9 is also BOOT.
 *   GPIO18, GPIO19 USB D-/D+ (Serial Monitor and uploading go through them).
 * Use with care:
 *   GPIO8          strapping pin too. An LED + resistor to GND is fine (as in
 *                  §5.1); never a button or a sensor output.
 *
 * Default values = Tabel 11 (Desain Teknis §5.1).
 * ============================================================================
 */
#pragma once

// --- Ultrasonic sensor (HC-SR04P / RCWL-1601, VCC 3.3 V) --------------------
constexpr int PIN_TRIG = 3;    // XIAO D1 -> TRIG
constexpr int PIN_ECHO = 4;    // XIAO D2 <- ECHO (must be 3.3 V logic)

// --- Vibration motors via ULN2003A (PWM) ------------------------------------
constexpr int PIN_MOTOR_L = 5; // XIAO D3 -> ULN2003A IN1 -> motor LEFT
constexpr int PIN_MOTOR_C = 6; // XIAO D4 -> ULN2003A IN2 -> motor CENTER (obstacle)
constexpr int PIN_MOTOR_R = 7; // XIAO D5 -> ULN2003A IN3 -> motor RIGHT

// --- Status LED (optional): set to -1 if there is no LED --------------------
constexpr int PIN_LED = 8;     // XIAO D8 -> 330 ohm -> LED -> GND

// --- Button (optional): set to -1 if there is no button ---------------------
constexpr int PIN_BUTTON = 10; // XIAO D10 <- button to GND

// --- Electrical polarity -----------------------------------------------------
// ULN2003A: a HIGH input switches the motor on. Set to false only for a driver
// where LOW means on (for example a P-MOSFET).
constexpr bool MOTOR_ACTIVE_HIGH = true;
// LED from the pin to GND: true. LED from 3V3 to the pin: false.
constexpr bool LED_ACTIVE_HIGH = true;
// Button from the pin to GND with the internal pull-up: true.
// Button from the pin to 3V3 with an external pull-down: false.
constexpr bool BUTTON_ACTIVE_LOW = true;

// ============================================================================
// Checks done while compiling. Nothing to change below this line.
// ============================================================================
namespace pincheck {
constexpr int USED[] = {PIN_TRIG, PIN_ECHO, PIN_MOTOR_L, PIN_MOTOR_C, PIN_MOTOR_R, PIN_LED, PIN_BUTTON};
constexpr int N = sizeof(USED) / sizeof(USED[0]);

constexpr bool forbidden(int p) { return p == 2 || p == 9 || p == 18 || p == 19; }
constexpr bool validGpio(int p) { return p == -1 || (p >= 0 && p <= 21); }

constexpr bool allAllowed() {
  for (int i = 0; i < N; i++)
    if (!validGpio(USED[i]) || forbidden(USED[i])) return false;
  return true;
}
constexpr bool allDistinct() {
  for (int i = 0; i < N; i++)
    for (int j = i + 1; j < N; j++)
      if (USED[i] != -1 && USED[i] == USED[j]) return false;
  return true;
}
}  // namespace pincheck

static_assert(PIN_TRIG >= 0 && PIN_ECHO >= 0, "The sensor pins are required");
static_assert(PIN_MOTOR_L >= 0 && PIN_MOTOR_C >= 0 && PIN_MOTOR_R >= 0, "All three motor pins are required");
static_assert(pincheck::allAllowed(), "pins.h: a pin is not GPIO 0..21, or is GPIO2/9 (strapping) or GPIO18/19 (USB)");
static_assert(pincheck::allDistinct(), "pins.h: two functions use the same GPIO");
static_assert(PIN_BUTTON != 8, "pins.h: GPIO8 is a strapping pin; a button there can stop the chip from starting");
