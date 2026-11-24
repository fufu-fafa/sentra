/*
 * ============================================================================
 *  SENTRA - Smart Haptic Sleeve
 *  Firmware v1.1 (based on Desain Teknis v1.0 §7.5)
 * ----------------------------------------------------------------------------
 *  Board   : Seeed Studio XIAO ESP32C3 (or any ESP32-C3 board)
 *  Core    : "esp32" by Espressif Systems 3.x (Arduino IDE 2.x)
 *  Setting : Tools > Board > esp32 > XIAO_ESP32C3
 *            Tools > USB CDC On Boot > Enabled   (so Serial Monitor works)
 *
 *  Files in this folder:
 *    pins.h    which GPIO is wired to what  <- change this when the sleeve is built
 *    config.h  BLE contract (must match the app) and tuning parameters
 *
 *  What it does:
 *    1. Reads the obstacle distance from the ultrasonic sensor.
 *    2. Takes navigation commands from the Android app over BLE
 *       (FORWARD, LEFT, RIGHT, STOP, ARRIVED, IDLE, TEST, LOW, HIGH, PING).
 *    3. Drives 3 vibration motors (LEFT, CENTER, RIGHT) through a ULN2003A.
 *    4. An obstacle warning ALWAYS wins over navigation (§6.3).
 *
 *  Everything is non-blocking (millis(), no delay() in loop()).
 * ============================================================================
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#if !defined(CONFIG_NIMBLE_ENABLED)
#include <BLE2902.h>  // Bluedroid (core < 3.3) needs the 2902 descriptor added by hand
#endif

#include "pins.h"
#include "config.h"

// ============================================================================
// 1. TYPES & GLOBALS
// ============================================================================
enum NavCmd { NAV_NONE, NAV_FORWARD, NAV_LEFT, NAV_RIGHT, NAV_STOP };
enum Zone   { ZONE_CLEAR, ZONE_FAR, ZONE_NEAR, ZONE_DANGER };

const uint8_t M_L = 0b001;  // motor bit masks
const uint8_t M_C = 0b010;
const uint8_t M_R = 0b100;
const uint8_t M_ALL = M_L | M_C | M_R;

// One-shot vibration (connected, arrived, ...)
struct Effect {
  bool active;
  unsigned long t0;
  uint16_t onMs, offMs;
  uint8_t count;
  uint8_t mask;
};

NavCmd nav = NAV_NONE;
unsigned long navT0 = 0;

Zone zone = ZONE_CLEAR;
unsigned long zoneT0 = 0;
int distanceCm = NO_OBSTACLE;
int distBuf[3] = {NO_OBSTACLE, NO_OBSTACLE, NO_OBSTACLE};
uint8_t distIdx = 0;

Effect fx = {false, 0, 0, 0, 0, 0};
uint8_t dutyOn = DUTY_HIGH;

// BLE
BLEServer*         pServer = nullptr;
BLECharacteristic* pStatusChar = nullptr;
volatile bool deviceConnected = false;
bool wasConnected = false;
unsigned long lastRxMs = 0;
unsigned long disconnectMs = 0;
bool needReAdvertise = false;

// Commands arrive on the BLE task and are handled in loop(). A small queue
// (instead of one buffer) so two writes close together, e.g. LEFT then PING,
// are both handled.
constexpr int CMD_QUEUE_LEN = 4;
constexpr int CMD_BUF_LEN = 24;
char cmdQueue[CMD_QUEUE_LEN][CMD_BUF_LEN];
volatile uint8_t cmdHead = 0, cmdTail = 0;  // head: next to write, tail: next to read
portMUX_TYPE cmdMux = portMUX_INITIALIZER_UNLOCKED;

// ============================================================================
// 2. PATTERN HELPERS
// ============================================================================
// True if the motor should be ON at time t (ms since the pattern started).
// 'count' pulses (onMs on, offMs off), then silent until cycleMs is over, repeat.
bool burst(unsigned long t, uint16_t onMs, uint16_t offMs, uint8_t count, uint16_t cycleMs) {
  t %= cycleMs;
  unsigned long period = (unsigned long)onMs + offMs;
  if (t >= period * count) return false;
  return (t % period) < onMs;
}

void playEffect(uint8_t mask, uint16_t onMs, uint16_t offMs, uint8_t count) {
  fx.active = true;
  fx.t0 = millis();
  fx.onMs = onMs;
  fx.offMs = offMs;
  fx.count = count;
  fx.mask = mask;
}

void setNav(NavCmd n) {
  if (n != nav) {
    nav = n;
    navT0 = millis();
  }
}

void motorWrite(int pin, uint8_t duty) {
  ledcWrite(pin, MOTOR_ACTIVE_HIGH ? duty : (uint8_t)(255 - duty));
}

void writeMotors(uint8_t mask) {
  motorWrite(PIN_MOTOR_L, (mask & M_L) ? dutyOn : 0);
  motorWrite(PIN_MOTOR_C, (mask & M_C) ? dutyOn : 0);
  motorWrite(PIN_MOTOR_R, (mask & M_R) ? dutyOn : 0);
}

const char* navName(NavCmd n) {
  switch (n) {
    case NAV_FORWARD: return "FORWARD";
    case NAV_LEFT:    return "LEFT";
    case NAV_RIGHT:   return "RIGHT";
    case NAV_STOP:    return "STOP";
    default:          return "NONE";
  }
}

const char* zoneName(Zone z) {
  switch (z) {
    case ZONE_FAR:    return "FAR";
    case ZONE_NEAR:   return "NEAR";
    case ZONE_DANGER: return "DANGER";
    default:          return "CLEAR";
  }
}

// ============================================================================
// 3. BLE: send status & callbacks
// ============================================================================
void sendStatus(const char* msg) {
  if (!deviceConnected || pStatusChar == nullptr) return;
  char out[MAX_MESSAGE_LEN + 1];
  strncpy(out, msg, MAX_MESSAGE_LEN);  // never more than one packet
  out[MAX_MESSAGE_LEN] = '\0';
  pStatusChar->setValue(out);
  pStatusChar->notify();
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { deviceConnected = true; }
  void onDisconnect(BLEServer* s) override { deviceConnected = false; }
};

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    // .c_str() works with core 2.x (std::string) and 3.x (String)
    String v = String(c->getValue().c_str());
    v.trim();
    v.toUpperCase();
    if (v.length() == 0) return;

    portENTER_CRITICAL(&cmdMux);
    uint8_t next = (cmdHead + 1) % CMD_QUEUE_LEN;
    if (next != cmdTail) {  // queue full: drop (the app re-sends every 2 s anyway)
      v.toCharArray(cmdQueue[cmdHead], CMD_BUF_LEN);
      cmdHead = next;
    }
    portEXIT_CRITICAL(&cmdMux);
  }
};

bool popCommand(char* out) {
  bool got = false;
  portENTER_CRITICAL(&cmdMux);
  if (cmdTail != cmdHead) {
    strncpy(out, cmdQueue[cmdTail], CMD_BUF_LEN);
    out[CMD_BUF_LEN - 1] = '\0';
    cmdTail = (cmdTail + 1) % CMD_QUEUE_LEN;
    got = true;
  }
  portEXIT_CRITICAL(&cmdMux);
  return got;
}

void setupBLE() {
  BLEDevice::init(DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  BLECharacteristic* pCmdChar = pService->createCharacteristic(
      CMD_CHAR_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  pCmdChar->setCallbacks(new CmdCallbacks());

  pStatusChar = pService->createCharacteristic(
      STATUS_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if !defined(CONFIG_NIMBLE_ENABLED)
  // NimBLE (core 3.3+) adds this descriptor itself because of PROPERTY_NOTIFY,
  // and marks BLE2902 deprecated; Bluedroid needs it, or the phone can't
  // subscribe to notifications.
  pStatusChar->addDescriptor(new BLE2902());
#endif
  pStatusChar->setValue("READY");

  pService->start();

  BLEAdvertising* pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] Advertising as " DEVICE_NAME);
}

// ============================================================================
// 4. COMMANDS FROM THE APP (Tabel 19)
// ============================================================================
void handleCommand(const char* cmd) {
  lastRxMs = millis();

  if (strcmp(cmd, "PING") == 0) {
    return;  // only "the app is alive", no ACK
  } else if (strcmp(cmd, "FORWARD") == 0) {
    if (nav != NAV_FORWARD) playEffect(M_L | M_R, 150, 0, 1);  // one short tap left+right
    setNav(NAV_FORWARD);
  } else if (strcmp(cmd, "LEFT") == 0) {
    setNav(NAV_LEFT);
  } else if (strcmp(cmd, "RIGHT") == 0) {
    setNav(NAV_RIGHT);
  } else if (strcmp(cmd, "STOP") == 0) {
    setNav(NAV_STOP);
  } else if (strcmp(cmd, "ARRIVED") == 0) {
    setNav(NAV_NONE);
    playEffect(M_ALL, 100, 100, 3);  // 3 fast taps, all motors
  } else if (strcmp(cmd, "IDLE") == 0) {
    setNav(NAV_NONE);
  } else if (strcmp(cmd, "TEST") == 0) {
    playEffect(M_ALL, 300, 200, 2);
  } else if (strcmp(cmd, "LOW") == 0) {
    dutyOn = DUTY_LOW;
  } else if (strcmp(cmd, "HIGH") == 0) {
    dutyOn = DUTY_HIGH;
  } else {
    Serial.printf("[BLE] Unknown command: %s\n", cmd);
    char err[MAX_MESSAGE_LEN + 1];
    snprintf(err, sizeof(err), "ERR:%s", cmd);
    sendStatus(err);
    return;
  }
  Serial.printf("[BLE] Command: %s\n", cmd);
  char ack[MAX_MESSAGE_LEN + 1];
  snprintf(ack, sizeof(ack), "ACK:%s", cmd);
  sendStatus(ack);
}

// ============================================================================
// 5. ULTRASONIC SENSOR
// ============================================================================
int readDistanceOnce() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);

  unsigned long dur = pulseIn(PIN_ECHO, HIGH, ECHO_TIMEOUT_US);  // 0 on timeout
  if (dur == 0) return NO_OBSTACLE;
  int cm = dur / 58;  // ~343 m/s -> 58 us per cm (there and back)
  if (cm < MIN_VALID_CM) return NO_OBSTACLE;  // too close / false echo
  if (cm > NO_OBSTACLE) return NO_OBSTACLE;
  return cm;
}

int median3(int a, int b, int c) {
  if ((a <= b && b <= c) || (c <= b && b <= a)) return b;
  if ((b <= a && a <= c) || (c <= a && a <= b)) return a;
  return c;
}

Zone classify(int d, Zone current) {
  // Hysteresis: to LEAVE a more dangerous zone the distance must pass limit + HYSTERESIS
  int danger = DIST_DANGER + (current >= ZONE_DANGER ? HYSTERESIS : 0);
  int near   = DIST_NEAR   + (current >= ZONE_NEAR   ? HYSTERESIS : 0);
  int far    = DIST_FAR    + (current >= ZONE_FAR    ? HYSTERESIS : 0);
  if (d < danger) return ZONE_DANGER;
  if (d < near)   return ZONE_NEAR;
  if (d < far)    return ZONE_FAR;
  return ZONE_CLEAR;
}

void updateSensor(unsigned long now) {
  static unsigned long lastMeasure = 0;
  if (now - lastMeasure < SENSOR_INTERVAL_MS) return;
  lastMeasure = now;

  distBuf[distIdx] = readDistanceOnce();
  distIdx = (distIdx + 1) % 3;
  distanceCm = median3(distBuf[0], distBuf[1], distBuf[2]);  // drops one odd reading

  Zone z = classify(distanceCm, zone);
  if (z != zone) {
    zone = z;
    zoneT0 = now;
  }
}

// ============================================================================
// 6. VIBRATION ENGINE (priority, §6.3)
// ============================================================================
void updateHaptics(unsigned long now) {
  uint8_t mask = 0;

  // --- Layer 1: NAVIGATION (left/right motors) ---
  unsigned long tn = now - navT0;
  switch (nav) {
    case NAV_LEFT:  if (burst(tn, 250, 150, 2, 1500)) mask |= M_L; break;
    case NAV_RIGHT: if (burst(tn, 250, 150, 2, 1500)) mask |= M_R; break;
    case NAV_STOP:  if (burst(tn, 500, 500, 1, 1000)) mask |= M_ALL; break;
    default: break;  // FORWARD & NONE: silent (silence = keep walking)
  }

  // --- Layer 2: OBSTACLE (centre motor) ---
  unsigned long tz = now - zoneT0;
  switch (zone) {
    case ZONE_FAR:  if (burst(tz, 80, 0, 1, 900)) mask |= M_C; break;
    case ZONE_NEAR: if (burst(tz, 80, 0, 1, 350)) mask |= M_C; break;
    case ZONE_DANGER:
      mask = M_C;  // HIGHEST PRIORITY: navigation held back, only centre, continuous
      break;
    default: break;
  }

  // --- Layer 3: one-shot EFFECT (except in DANGER) ---
  if (fx.active) {
    unsigned long tf = now - fx.t0;
    unsigned long period = (unsigned long)fx.onMs + fx.offMs;
    if (tf >= period * fx.count) {
      fx.active = false;
    } else if (zone != ZONE_DANGER) {
      mask = ((tf % period) < fx.onMs) ? fx.mask : 0;
    }
  }

  writeMotors(mask);
}

// ============================================================================
// 7. BUTTON (short: ask the phone for info, long: switch intensity)
// ============================================================================
void updateButton(unsigned long now) {
  if (PIN_BUTTON < 0) return;

  static bool lastRaw = false;
  static unsigned long changeMs = 0;
  static unsigned long pressMs = 0;
  static bool pressed = false;

  bool raw = digitalRead(PIN_BUTTON) == (BUTTON_ACTIVE_LOW ? LOW : HIGH);  // true = held down
  if (raw != lastRaw) {
    lastRaw = raw;
    changeMs = now;
  }
  if (now - changeMs < DEBOUNCE_MS) return;

  if (raw && !pressed) {
    pressed = true;
    pressMs = now;
  } else if (!raw && pressed) {
    pressed = false;
    unsigned long dur = now - pressMs;
    if (dur >= LONG_PRESS_MS) {
      dutyOn = (dutyOn == DUTY_HIGH) ? DUTY_LOW : DUTY_HIGH;
      playEffect(M_ALL, 150, 150, (dutyOn == DUTY_HIGH) ? 2 : 1);
      sendStatus(dutyOn == DUTY_HIGH ? "BTN:LONG,HIGH" : "BTN:LONG,LOW");
      Serial.println("[BTN] Long press -> intensity switched");
    } else {
      sendStatus("BTN:SHORT");  // the app reads out the info
      if (!deviceConnected) playEffect(M_C, 100, 0, 1);
      Serial.println("[BTN] Short press");
    }
  }
}

// ============================================================================
// 8. BLE CONNECTION: connect, disconnect, timeout
// ============================================================================
void updateConnection(unsigned long now) {
  if (deviceConnected && !wasConnected) {
    wasConnected = true;
    lastRxMs = now;
    playEffect(M_ALL, 120, 120, 2);  // 2 taps = connected
    Serial.println("[BLE] Connected");
  }

  if (!deviceConnected && wasConnected) {
    wasConnected = false;
    setNav(NAV_NONE);
    playEffect(M_L | M_R, 700, 0, 1);  // 1 long buzz = disconnected
    disconnectMs = now;
    needReAdvertise = true;
    Serial.println("[BLE] Disconnected");
  }

  // REQUIRED: after a disconnect the ESP32 must advertise again to be found
  if (needReAdvertise && now - disconnectMs > READVERTISE_DELAY_MS) {
    needReAdvertise = false;
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Advertising again");
  }

  // Connected but the app has been silent too long (screen off / app crashed)
  if (deviceConnected && nav != NAV_NONE && now - lastRxMs > APP_TIMEOUT_MS) {
    setNav(NAV_NONE);
    playEffect(M_L | M_R, 400, 200, 2);
    Serial.println("[BLE] App not responding -> navigation stopped");
  }

  // Send the distance to the phone periodically
  static unsigned long lastStatus = 0;
  if (deviceConnected && now - lastStatus >= STATUS_INTERVAL_MS) {
    lastStatus = now;
    char msg[MAX_MESSAGE_LEN + 1];
    snprintf(msg, sizeof(msg), "D:%d", distanceCm);
    sendStatus(msg);
  }
}

void updateLed(unsigned long now) {
  if (PIN_LED < 0) return;
  // Connected: steady on. Not connected: blink at 1 Hz.
  bool on = deviceConnected ? true : ((now / 500) % 2 == 0);
  digitalWrite(PIN_LED, (on == LED_ACTIVE_HIGH) ? HIGH : LOW);
}

void debugPrint(unsigned long now) {
  if (DEBUG_INTERVAL_MS == 0) return;
  static unsigned long last = 0;
  if (now - last < DEBUG_INTERVAL_MS) return;
  last = now;
  Serial.printf("dist=%4d cm | zone=%-6s | nav=%-7s | ble=%s\n",
                distanceCm, zoneName(zone), navName(nav),
                deviceConnected ? "ON" : "off");
}

// ============================================================================
// 9. SETUP & LOOP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== SENTRA firmware v1.1 ===");
  Serial.printf("Pins: TRIG=%d ECHO=%d MOTOR L/C/R=%d/%d/%d LED=%d BUTTON=%d\n",
                PIN_TRIG, PIN_ECHO, PIN_MOTOR_L, PIN_MOTOR_C, PIN_MOTOR_R, PIN_LED, PIN_BUTTON);

  pinMode(PIN_TRIG, OUTPUT);
  digitalWrite(PIN_TRIG, LOW);
  pinMode(PIN_ECHO, INPUT);
  if (PIN_LED >= 0) pinMode(PIN_LED, OUTPUT);
  if (PIN_BUTTON >= 0) pinMode(PIN_BUTTON, BUTTON_ACTIVE_LOW ? INPUT_PULLUP : INPUT);

  // Arduino-ESP32 core 3.x: ledcAttach(pin, frequency, resolution)
  ledcAttach(PIN_MOTOR_L, PWM_FREQ, PWM_RES);
  ledcAttach(PIN_MOTOR_C, PWM_FREQ, PWM_RES);
  ledcAttach(PIN_MOTOR_R, PWM_FREQ, PWM_RES);
  writeMotors(0);

  // Self-test at power on: left -> centre -> right
  const int pins[3] = {PIN_MOTOR_L, PIN_MOTOR_C, PIN_MOTOR_R};
  for (int i = 0; i < 3; i++) {
    motorWrite(pins[i], dutyOn);
    delay(150);
    motorWrite(pins[i], 0);
    delay(100);
  }

  setupBLE();
}

void loop() {
  unsigned long now = millis();

  char cmd[CMD_BUF_LEN];
  while (popCommand(cmd)) handleCommand(cmd);

  updateSensor(now);
  updateConnection(now);
  updateButton(now);
  updateHaptics(now);
  updateLed(now);
  debugPrint(now);
}
