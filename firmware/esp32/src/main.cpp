#include <Arduino.h>
#include "BluetoothSerial.h"
#include <Update.h>

namespace {
BluetoothSerial SerialBT;

constexpr uint8_t LEFT_ESC_PIN = 25;
constexpr uint8_t RIGHT_ESC_PIN = 26;
constexpr uint8_t ARM_BUTTON_PIN = 17;
constexpr uint8_t STATUS_LED_PIN = 2;
constexpr char BT_DEVICE_NAME[] = "SmartSUP-ESP32";

constexpr uint8_t LEFT_ESC_CHANNEL = 0;
constexpr uint8_t RIGHT_ESC_CHANNEL = 1;
constexpr uint32_t ESC_PWM_FREQ_HZ = 50;
constexpr uint8_t ESC_PWM_RESOLUTION_BITS = 16;

constexpr uint16_t ESC_REVERSE_US = 1000;
constexpr uint16_t ESC_NEUTRAL_US = 1500;
constexpr uint16_t ESC_FORWARD_US = 2000;
constexpr uint16_t THROTTLE_RAMP_US_PER_TICK = 5;
constexpr uint32_t CONTROL_TICK_MS = 20;
constexpr uint32_t ARM_HOLD_MS = 1500;
constexpr uint32_t BT_COMMAND_TIMEOUT_MS = 1000;
constexpr uint32_t BT_STATUS_INTERVAL_MS = 1000;
constexpr uint32_t OTA_TIMEOUT_MS = 15000;
constexpr size_t BT_RX_BUFFER_SIZE = 96;
constexpr size_t OTA_BUFFER_SIZE = 512;

bool armed = false;
uint16_t leftPulseUs = ESC_NEUTRAL_US;
uint16_t rightPulseUs = ESC_NEUTRAL_US;
int8_t requestedLeftPercent = 0;
int8_t requestedRightPercent = 0;
uint32_t lastTickMs = 0;
uint32_t armButtonPressedSinceMs = 0;
uint32_t lastValidBtCommandMs = 0;
uint32_t lastBtStatusMs = 0;
char btRxBuffer[BT_RX_BUFFER_SIZE] = {};
size_t btRxLen = 0;
bool otaInProgress = false;
size_t otaExpectedBytes = 0;
size_t otaWrittenBytes = 0;
uint32_t otaLastDataMs = 0;
uint32_t otaLastProgressMs = 0;

uint32_t pulseUsToDuty(uint16_t pulseUs) {
  const uint32_t maxDuty = (1UL << ESC_PWM_RESOLUTION_BITS) - 1;
  return (static_cast<uint32_t>(pulseUs) * maxDuty) / 20000UL;
}

void writeEsc(uint8_t channel, uint16_t pulseUs) {
  ledcWrite(channel, pulseUsToDuty(pulseUs));
}

uint16_t signedPercentToPulseUs(int8_t percent) {
  const int constrainedPercent = constrain(static_cast<int>(percent), -100, 100);
  if (constrainedPercent >= 0) {
    const uint32_t span = ESC_FORWARD_US - ESC_NEUTRAL_US;
    return ESC_NEUTRAL_US + (span * constrainedPercent) / 100;
  }

  const uint32_t span = ESC_NEUTRAL_US - ESC_REVERSE_US;
  return ESC_NEUTRAL_US - (span * abs(constrainedPercent)) / 100;
}

uint16_t rampToward(uint16_t current, uint16_t target) {
  if (current < target) {
    return min<uint16_t>(current + THROTTLE_RAMP_US_PER_TICK, target);
  }
  if (current > target) {
    return max<int>(current - THROTTLE_RAMP_US_PER_TICK, target);
  }
  return current;
}

void forceNeutralAndDisarm() {
  armed = false;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  leftPulseUs = ESC_NEUTRAL_US;
  rightPulseUs = ESC_NEUTRAL_US;
  writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);
}

bool parseSizeToken(const char* token, const char* prefix, size_t& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const unsigned long parsed = strtoul(token + prefixLen, &end, 10);
  if (end == token + prefixLen || *end != '\0' || parsed == 0) {
    return false;
  }

  outValue = static_cast<size_t>(parsed);
  return true;
}

bool parseMd5Token(const char* token, const char* prefix, char* outMd5, size_t outMd5Size) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0 || outMd5Size < 33) {
    return false;
  }

  const char* md5 = token + prefixLen;
  if (strlen(md5) != 32) {
    return false;
  }

  for (size_t i = 0; i < 32; ++i) {
    const char c = md5[i];
    const bool hex = (c >= '0' && c <= '9') ||
      (c >= 'a' && c <= 'f') ||
      (c >= 'A' && c <= 'F');
    if (!hex) {
      return false;
    }
  }

  strncpy(outMd5, md5, outMd5Size);
  outMd5[32] = '\0';
  return true;
}

void updateArmState() {
  const bool pressed = digitalRead(ARM_BUTTON_PIN) == LOW;
  const uint32_t now = millis();

  if (!pressed) {
    armButtonPressedSinceMs = 0;
    return;
  }

  if (armButtonPressedSinceMs == 0) {
    armButtonPressedSinceMs = now;
    return;
  }

  if (!armed && now - armButtonPressedSinceMs >= ARM_HOLD_MS) {
    armed = true;
    Serial.println("ARMED");
    SerialBT.println("STATUS;ARMED=1;SRC=BUTTON");
  }
}

bool parsePercentToken(const char* token, const char* prefix, int8_t& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const long parsed = strtol(token + prefixLen, &end, 10);
  if (end == token + prefixLen || *end != '\0' || parsed < -100 || parsed > 100) {
    return false;
  }

  outValue = static_cast<int8_t>(parsed);
  return true;
}

bool parseArmToken(const char* token, bool& outArmed) {
  if (strcmp(token, "ARM=0") == 0) {
    outArmed = false;
    return true;
  }
  if (strcmp(token, "ARM=1") == 0) {
    outArmed = true;
    return true;
  }
  return false;
}

void beginOta(char* line) {
  bool sawSize = false;
  bool sawMd5 = false;
  size_t nextSize = 0;
  char nextMd5[33] = {};

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "OTA_BEGIN") == 0) {
      // Header marker.
    } else if (parseSizeToken(token, "SIZE=", nextSize)) {
      sawSize = true;
    } else if (parseMd5Token(token, "MD5=", nextMd5, sizeof(nextMd5))) {
      sawMd5 = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawSize || !sawMd5) {
    SerialBT.println("OTA;ERR=BAD_HEADER");
    Serial.println("OTA rejected: bad header");
    return;
  }

  forceNeutralAndDisarm();

  if (!Update.begin(nextSize)) {
    SerialBT.print("OTA;ERR=BEGIN_FAILED;CODE=");
    SerialBT.println(Update.getError());
    Serial.print("OTA begin failed code=");
    Serial.println(Update.getError());
    return;
  }

  if (!Update.setMD5(nextMd5)) {
    Update.abort();
    SerialBT.println("OTA;ERR=BAD_MD5");
    Serial.println("OTA rejected: bad md5");
    return;
  }

  otaInProgress = true;
  otaExpectedBytes = nextSize;
  otaWrittenBytes = 0;
  otaLastDataMs = millis();
  otaLastProgressMs = 0;
  btRxLen = 0;

  Serial.print("OTA begin size=");
  Serial.println(otaExpectedBytes);
  SerialBT.print("OTA;READY;SIZE=");
  SerialBT.println(otaExpectedBytes);
}

void finishOta() {
  if (!Update.end(true)) {
    SerialBT.print("OTA;ERR=END_FAILED;CODE=");
    SerialBT.println(Update.getError());
    Serial.print("OTA end failed code=");
    Serial.println(Update.getError());
    otaInProgress = false;
    return;
  }

  SerialBT.print("OTA;OK;BYTES=");
  SerialBT.println(otaWrittenBytes);
  Serial.println("OTA complete; rebooting");
  delay(500);
  ESP.restart();
}

void processOtaBytes() {
  uint8_t buffer[OTA_BUFFER_SIZE];
  while (otaInProgress && SerialBT.available() > 0 && otaWrittenBytes < otaExpectedBytes) {
    const size_t remaining = otaExpectedBytes - otaWrittenBytes;
    const size_t toRead = min(remaining, sizeof(buffer));
    const size_t readBytes = SerialBT.readBytes(buffer, toRead);
    if (readBytes == 0) {
      break;
    }

    const size_t written = Update.write(buffer, readBytes);
    otaWrittenBytes += written;
    otaLastDataMs = millis();

    if (written != readBytes) {
      Update.abort();
      otaInProgress = false;
      SerialBT.print("OTA;ERR=WRITE_FAILED;CODE=");
      SerialBT.println(Update.getError());
      Serial.println("OTA write failed");
      return;
    }

    const uint32_t now = millis();
    if (now - otaLastProgressMs >= 1000 || otaWrittenBytes == otaExpectedBytes) {
      otaLastProgressMs = now;
      SerialBT.print("OTA;PROGRESS=");
      SerialBT.print(otaWrittenBytes);
      SerialBT.print("/");
      SerialBT.println(otaExpectedBytes);
    }
  }

  if (otaInProgress && otaWrittenBytes == otaExpectedBytes) {
    otaInProgress = false;
    finishOta();
  }
}

void handleOtaTimeout(uint32_t now) {
  if (!otaInProgress || now - otaLastDataMs <= OTA_TIMEOUT_MS) {
    return;
  }

  Update.abort();
  otaInProgress = false;
  forceNeutralAndDisarm();
  SerialBT.println("OTA;ERR=TIMEOUT");
  Serial.println("OTA timeout; aborted");
}

void applyBluetoothLine(char* line) {
  if (strncmp(line, "OTA_BEGIN", 9) == 0) {
    beginOta(line);
    return;
  }

  bool sawArm = false;
  bool sawLeft = false;
  bool sawRight = false;
  bool nextArmed = false;
  int8_t nextLeftPercent = 0;
  int8_t nextRightPercent = 0;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (parseArmToken(token, nextArmed)) {
      sawArm = true;
    } else if (parsePercentToken(token, "L=", nextLeftPercent)) {
      sawLeft = true;
    } else if (parsePercentToken(token, "R=", nextRightPercent)) {
      sawRight = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawArm || !sawLeft || !sawRight) {
    SerialBT.println("ERR;BAD_COMMAND");
    Serial.println("Bluetooth command rejected");
    return;
  }

  armed = nextArmed;
  requestedLeftPercent = nextArmed ? nextLeftPercent : 0;
  requestedRightPercent = nextArmed ? nextRightPercent : 0;
  lastValidBtCommandMs = millis();

  Serial.print("BT command armed=");
  Serial.print(armed ? 1 : 0);
  Serial.print(" left=");
  Serial.print(requestedLeftPercent);
  Serial.print(" right=");
  Serial.println(requestedRightPercent);
}

void processBluetoothInput() {
  if (otaInProgress) {
    processOtaBytes();
    return;
  }

  while (SerialBT.available() > 0) {
    const char next = static_cast<char>(SerialBT.read());
    if (next == '\r') {
      continue;
    }
    if (next == '\n') {
      btRxBuffer[btRxLen] = '\0';
      if (btRxLen > 0) {
        applyBluetoothLine(btRxBuffer);
      }
      btRxLen = 0;
      continue;
    }

    if (btRxLen < BT_RX_BUFFER_SIZE - 1) {
      btRxBuffer[btRxLen++] = next;
    } else {
      btRxLen = 0;
      SerialBT.println("ERR;LINE_TOO_LONG");
      Serial.println("Bluetooth command too long");
    }
  }
}

bool hasFreshBluetoothCommand(uint32_t now) {
  return lastValidBtCommandMs != 0 && now - lastValidBtCommandMs <= BT_COMMAND_TIMEOUT_MS;
}

void applyCommandTimeout(uint32_t now) {
  if (otaInProgress) {
    return;
  }
  if (armed && !hasFreshBluetoothCommand(now)) {
    armed = false;
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    Serial.println("Bluetooth command timeout; state=DISARMED; throttle=NEUTRAL");
    SerialBT.println("STATUS;ARMED=0;FAULT=BT_TIMEOUT");
  }
}

void publishBluetoothStatus(uint32_t now) {
  if (otaInProgress) {
    return;
  }
  if (now - lastBtStatusMs < BT_STATUS_INTERVAL_MS) {
    return;
  }
  lastBtStatusMs = now;

  SerialBT.print("STATUS;ARMED=");
  SerialBT.print(armed ? 1 : 0);
  SerialBT.print(";L=");
  SerialBT.print(requestedLeftPercent);
  SerialBT.print(";R=");
  SerialBT.print(requestedRightPercent);
  SerialBT.print(";LPWM=");
  SerialBT.print(leftPulseUs);
  SerialBT.print(";RPWM=");
  SerialBT.println(rightPulseUs);
}
}  // namespace

void setup() {
  Serial.begin(115200);
  pinMode(ARM_BUTTON_PIN, INPUT_PULLUP);
  pinMode(STATUS_LED_PIN, OUTPUT);

  ledcSetup(LEFT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcSetup(RIGHT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcAttachPin(LEFT_ESC_PIN, LEFT_ESC_CHANNEL);
  ledcAttachPin(RIGHT_ESC_PIN, RIGHT_ESC_CHANNEL);

  writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);

  if (!SerialBT.begin(BT_DEVICE_NAME)) {
    Serial.println("Bluetooth start failed");
  } else {
    Serial.print("Bluetooth SPP started; name=");
    Serial.println(BT_DEVICE_NAME);
  }

  Serial.println("Smart SUP controller booted; state=DISARMED");
}

void loop() {
  processBluetoothInput();

  const uint32_t now = millis();
  handleOtaTimeout(now);
  if (otaInProgress) {
    digitalWrite(STATUS_LED_PIN, (now / 100) % 2);
    return;
  }

  updateArmState();
  applyCommandTimeout(now);
  publishBluetoothStatus(now);

  if (now - lastTickMs < CONTROL_TICK_MS) {
    return;
  }
  lastTickMs = now;

  const bool canApplyBluetoothThrottle = armed && hasFreshBluetoothCommand(now);
  const uint16_t leftTarget = canApplyBluetoothThrottle ? signedPercentToPulseUs(requestedLeftPercent) : ESC_NEUTRAL_US;
  const uint16_t rightTarget = canApplyBluetoothThrottle ? signedPercentToPulseUs(requestedRightPercent) : ESC_NEUTRAL_US;

  leftPulseUs = rampToward(leftPulseUs, constrain(leftTarget, ESC_REVERSE_US, ESC_FORWARD_US));
  rightPulseUs = rampToward(rightPulseUs, constrain(rightTarget, ESC_REVERSE_US, ESC_FORWARD_US));

  writeEsc(LEFT_ESC_CHANNEL, leftPulseUs);
  writeEsc(RIGHT_ESC_CHANNEL, rightPulseUs);
  digitalWrite(STATUS_LED_PIN, armed ? HIGH : (now / 500) % 2);
}
