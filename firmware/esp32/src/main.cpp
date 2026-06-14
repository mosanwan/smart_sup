#include <Arduino.h>
#include "BluetoothSerial.h"
#include <Preferences.h>
#include <Update.h>
#include <Wire.h>
#if __has_include("factory_unit_id.h")
#include "factory_unit_id.h"
#endif

namespace {
BluetoothSerial SerialBT;
Preferences preferences;

constexpr uint8_t LEFT_ESC_PIN = 25;
constexpr uint8_t RIGHT_ESC_PIN = 26;
constexpr uint8_t ARM_BUTTON_PIN = 17;
constexpr uint8_t STATUS_LED_PIN = 2;
constexpr uint8_t IMU_SDA_PIN = 14;
constexpr uint8_t IMU_SCL_PIN = 22;
constexpr int8_t GPS_RX_PIN = 16;
constexpr int8_t GPS_TX_PIN = -1;
constexpr uint8_t GPS_PPS_PIN = 13;
constexpr char BT_DEVICE_NAME_PREFIX[] = "SmartSUP-";
constexpr char NVS_NAMESPACE[] = "smart_sup";
constexpr char NVS_UNIT_ID_KEY[] = "unit_id";
constexpr uint16_t DEFAULT_UNIT_ID = 0;
constexpr uint16_t MAX_UNIT_ID = 999;
#ifndef SMART_SUP_FACTORY_UNIT_ID
#define SMART_SUP_FACTORY_UNIT_ID -1
#endif
constexpr int FACTORY_UNIT_ID = SMART_SUP_FACTORY_UNIT_ID;

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
constexpr uint32_t TURN_CONTROL_TIMEOUT_MS = 8000;
constexpr int8_t VOICE_MAX_FORWARD_PERCENT = 30;
constexpr int8_t VOICE_MAX_REVERSE_PERCENT = 15;
constexpr int8_t VOICE_MAX_TURN_DELTA_PERCENT = 20;
constexpr int8_t TURN_INNER_PERCENT = 8;
constexpr int8_t TURN_MIN_OUTER_PERCENT = 14;
constexpr int8_t TURN_MAX_OUTER_PERCENT = 24;
constexpr float TURN_DONE_DEGREES = 3.0f;
constexpr float TURN_SLOWDOWN_DEGREES = 45.0f;
constexpr int8_t HEADING_LOCK_MAX_CORRECTION_PERCENT = 10;
constexpr float HEADING_LOCK_TOLERANCE_DEGREES = 4.0f;
constexpr float HEADING_LOCK_FULL_CORRECTION_DEGREES = 45.0f;
constexpr uint16_t MAX_VOICE_TURN_ANGLE_DEGREES = 90;
constexpr float ICM20948_GYRO_250DPS_LSB_PER_DPS = 131.0f;
constexpr float IMU_YAW_SIGN = 1.0f;
constexpr size_t BT_RX_BUFFER_SIZE = 128;
constexpr size_t SERIAL_RX_BUFFER_SIZE = 96;
constexpr size_t GPS_RX_BUFFER_SIZE = 128;
constexpr size_t OTA_BUFFER_SIZE = 512;
constexpr size_t BT_DEVICE_NAME_SIZE = 16;
constexpr uint32_t GPS_BAUDS[] = {9600, 38400, 57600, 115200, 4800, 19200};
constexpr uint32_t GPS_BAUD_SCAN_MS = 5000;
constexpr uint32_t GPS_PRESENT_TIMEOUT_MS = 3000;
constexpr uint32_t GPS_PPS_TIMEOUT_MS = 2500;
constexpr uint8_t ICM20948_ADDR_LOW = 0x68;
constexpr uint8_t ICM20948_ADDR_HIGH = 0x69;
constexpr uint8_t ICM20948_WHO_AM_I_VALUE = 0xEA;
constexpr uint8_t ICM20948_REG_BANK_SEL = 0x7F;
constexpr uint8_t ICM20948_BANK_0 = 0x00;
constexpr uint8_t ICM20948_BANK_2 = 0x20;
constexpr uint8_t ICM20948_WHO_AM_I = 0x00;
constexpr uint8_t ICM20948_PWR_MGMT_1 = 0x06;
constexpr uint8_t ICM20948_PWR_MGMT_2 = 0x07;
constexpr uint8_t ICM20948_GYRO_ZOUT_H = 0x37;
constexpr uint8_t ICM20948_GYRO_SMPLRT_DIV = 0x00;
constexpr uint8_t ICM20948_GYRO_CONFIG_1 = 0x01;
constexpr uint8_t ICM20948_GYRO_CONFIG_2 = 0x02;

enum class CommandSource : uint8_t {
  App,
  Voice,
};

enum class CommandMode : uint8_t {
  Throttle,
  TurnAngle,
  HeadingLock,
};

enum class TurnDirection : uint8_t {
  Left,
  Right,
};

bool armed = false;
uint16_t leftPulseUs = ESC_NEUTRAL_US;
uint16_t rightPulseUs = ESC_NEUTRAL_US;
int8_t requestedLeftPercent = 0;
int8_t requestedRightPercent = 0;
CommandSource lastCommandSource = CommandSource::App;
CommandMode lastCommandMode = CommandMode::Throttle;
uint32_t lastTickMs = 0;
uint32_t lastImuUpdateMs = 0;
uint32_t armButtonPressedSinceMs = 0;
uint32_t lastValidBtCommandMs = 0;
uint32_t lastBtStatusMs = 0;
char btRxBuffer[BT_RX_BUFFER_SIZE] = {};
size_t btRxLen = 0;
char serialRxBuffer[SERIAL_RX_BUFFER_SIZE] = {};
size_t serialRxLen = 0;
char gpsRxBuffer[GPS_RX_BUFFER_SIZE] = {};
size_t gpsRxLen = 0;
bool otaInProgress = false;
size_t otaExpectedBytes = 0;
size_t otaWrittenBytes = 0;
uint32_t otaLastDataMs = 0;
uint32_t otaLastProgressMs = 0;
bool nvsReady = false;
uint16_t unitId = DEFAULT_UNIT_ID;
char btDeviceName[BT_DEVICE_NAME_SIZE] = {};
bool unitIdProvisioned = false;
const char* unitIdSource = "FALLBACK";
bool imuAvailable = false;
uint8_t imuAddress = ICM20948_ADDR_LOW;
uint8_t imuFailureCount = 0;
float headingDegrees = 0.0f;
float gyroZBiasDps = 0.0f;
float lastTurnErrorDegrees = 0.0f;
bool turnControlActive = false;
bool turnControlCompleted = false;
uint16_t activeTurnRequestId = 0;
uint16_t completedTurnRequestId = 0;
float turnTargetHeadingDegrees = 0.0f;
uint32_t turnStartedMs = 0;
bool turnLeftEscReversed = false;
bool turnRightEscReversed = false;
bool headingLockActive = false;
uint16_t activeHeadingLockRequestId = 0;
float headingLockTargetDegrees = 0.0f;
float lastHeadingLockErrorDegrees = 0.0f;
int8_t headingLockBasePercent = 0;
int8_t lastHeadingLockCorrectionPercent = 0;
bool headingLockLeftEscReversed = false;
bool headingLockRightEscReversed = false;
uint32_t gpsByteCount = 0;
uint32_t gpsSentenceCount = 0;
uint32_t gpsLastByteMs = 0;
uint32_t gpsLastSentenceMs = 0;
uint32_t gpsLastPpsMs = 0;
uint32_t gpsReportedPpsCount = 0;
uint32_t gpsCurrentBaud = 0;
uint32_t gpsBaudScanStartedMs = 0;
uint32_t gpsScanBytes = 0;
uint32_t gpsScanNewlines = 0;
uint32_t gpsScanDollarSigns = 0;
uint32_t gpsScanOverflows = 0;
size_t gpsBaudIndex = 0;
bool gpsNmeaDetected = false;
bool gpsFixValid = false;
bool gpsAntennaOpen = false;
bool gpsAntennaStatusKnown = false;
uint8_t gpsFixQuality = 0;
uint8_t gpsSatellites = 0;
float gpsLatitudeDegrees = 0.0f;
float gpsLongitudeDegrees = 0.0f;
volatile uint32_t gpsPpsCount = 0;
volatile uint32_t gpsLastPpsMicros = 0;

void IRAM_ATTR handleGpsPps() {
  gpsPpsCount += 1;
  gpsLastPpsMicros = micros();
}

void printPaddedUnitId(Print& output, uint16_t id) {
  if (id < 100) {
    output.print('0');
  }
  if (id < 10) {
    output.print('0');
  }
  output.print(id);
}

void formatBluetoothName() {
  snprintf(btDeviceName, sizeof(btDeviceName), "%s%03u", BT_DEVICE_NAME_PREFIX, unitId);
}

bool isValidUnitId(int id) {
  return id >= 0 && id <= MAX_UNIT_ID;
}

void applyUnitId(uint16_t id, const char* source, bool provisioned) {
  unitId = id;
  unitIdSource = source;
  unitIdProvisioned = provisioned;
  formatBluetoothName();
}

void loadUnitId() {
  nvsReady = preferences.begin(NVS_NAMESPACE, false);
  if (!nvsReady) {
    if (isValidUnitId(FACTORY_UNIT_ID)) {
      applyUnitId(static_cast<uint16_t>(FACTORY_UNIT_ID), "FACTORY_VOLATILE", false);
    } else {
      applyUnitId(DEFAULT_UNIT_ID, "FALLBACK", false);
    }
    Serial.println("NVS start failed; using default unit id");
    return;
  }

  if (preferences.isKey(NVS_UNIT_ID_KEY)) {
    const uint32_t stored = preferences.getUInt(NVS_UNIT_ID_KEY, DEFAULT_UNIT_ID);
    if (stored <= MAX_UNIT_ID) {
      applyUnitId(static_cast<uint16_t>(stored), "NVS", true);
      return;
    }
    Serial.println("Stored unit id invalid; ignoring NVS value");
  }

  if (isValidUnitId(FACTORY_UNIT_ID)) {
    applyUnitId(static_cast<uint16_t>(FACTORY_UNIT_ID), "FACTORY", true);
    preferences.putUInt(NVS_UNIT_ID_KEY, unitId);
    return;
  }

  applyUnitId(DEFAULT_UNIT_ID, "FALLBACK", false);
}

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

const char* commandSourceName(CommandSource source) {
  return source == CommandSource::Voice ? "VOICE" : "APP";
}

const char* commandModeName(CommandMode mode) {
  switch (mode) {
    case CommandMode::TurnAngle:
      return "TURN";
    case CommandMode::HeadingLock:
      return "HEADING_LOCK";
    case CommandMode::Throttle:
    default:
      return "THROTTLE";
  }
}

int8_t clampVoicePercent(int value) {
  if (value > 0) {
    return static_cast<int8_t>(min(value, static_cast<int>(VOICE_MAX_FORWARD_PERCENT)));
  }
  if (value < 0) {
    return static_cast<int8_t>(max(value, -static_cast<int>(VOICE_MAX_REVERSE_PERCENT)));
  }
  return 0;
}

void applyVoiceLimits(int8_t& leftPercent, int8_t& rightPercent) {
  int left = clampVoicePercent(leftPercent);
  int right = clampVoicePercent(rightPercent);
  const int delta = left - right;

  if (delta > VOICE_MAX_TURN_DELTA_PERCENT) {
    left = right + VOICE_MAX_TURN_DELTA_PERCENT;
  } else if (delta < -VOICE_MAX_TURN_DELTA_PERCENT) {
    right = left + VOICE_MAX_TURN_DELTA_PERCENT;
  }

  leftPercent = clampVoicePercent(left);
  rightPercent = clampVoicePercent(right);
}

int8_t clampHeadingLockBasePercent(int value) {
  return clampVoicePercent(value);
}

void applyHeadingLockLimits(int8_t& leftPercent, int8_t& rightPercent) {
  applyVoiceLimits(leftPercent, rightPercent);
}

float normalizeAngle180(float degrees) {
  while (degrees > 180.0f) {
    degrees -= 360.0f;
  }
  while (degrees < -180.0f) {
    degrees += 360.0f;
  }
  return degrees;
}

float shortestAngleError(float targetDegrees, float currentDegrees) {
  return normalizeAngle180(targetDegrees - currentDegrees);
}

bool imuWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(imuAddress);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool imuRead(uint8_t reg, uint8_t* buffer, size_t length) {
  Wire.beginTransmission(imuAddress);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  const size_t readBytes = Wire.requestFrom(static_cast<int>(imuAddress), static_cast<int>(length));
  if (readBytes != length) {
    return false;
  }
  for (size_t i = 0; i < length; ++i) {
    buffer[i] = Wire.read();
  }
  return true;
}

bool imuSelectBank(uint8_t bank) {
  return imuWrite(ICM20948_REG_BANK_SEL, bank);
}

bool imuReadByte(uint8_t reg, uint8_t& value) {
  return imuRead(reg, &value, 1);
}

bool detectImuAddress(uint8_t address) {
  imuAddress = address;
  uint8_t whoAmI = 0;
  if (!imuSelectBank(ICM20948_BANK_0) || !imuReadByte(ICM20948_WHO_AM_I, whoAmI)) {
    return false;
  }
  return whoAmI == ICM20948_WHO_AM_I_VALUE;
}

bool readGyroZRaw(int16_t& rawValue) {
  uint8_t buffer[2] = {};
  if (!imuSelectBank(ICM20948_BANK_0) || !imuRead(ICM20948_GYRO_ZOUT_H, buffer, sizeof(buffer))) {
    return false;
  }
  rawValue = static_cast<int16_t>((static_cast<uint16_t>(buffer[0]) << 8) | buffer[1]);
  return true;
}

bool readGyroZDps(float& gyroZDps) {
  int16_t rawValue = 0;
  if (!readGyroZRaw(rawValue)) {
    return false;
  }
  gyroZDps = (static_cast<float>(rawValue) / ICM20948_GYRO_250DPS_LSB_PER_DPS) * IMU_YAW_SIGN;
  return true;
}

void calibrateGyroBias() {
  float sum = 0.0f;
  uint16_t samples = 0;
  for (uint16_t i = 0; i < 200; ++i) {
    float gyroZDps = 0.0f;
    if (readGyroZDps(gyroZDps)) {
      sum += gyroZDps;
      samples += 1;
    }
    delay(5);
  }
  gyroZBiasDps = samples > 0 ? sum / samples : 0.0f;
}

void setupImu() {
  Wire.begin(IMU_SDA_PIN, IMU_SCL_PIN);
  Wire.setClock(400000);

  if (!detectImuAddress(ICM20948_ADDR_LOW) && !detectImuAddress(ICM20948_ADDR_HIGH)) {
    imuAvailable = false;
    Serial.println("ICM20948 not detected; angle turn disabled");
    return;
  }

  imuSelectBank(ICM20948_BANK_0);
  imuWrite(ICM20948_PWR_MGMT_1, 0x80);
  delay(100);
  imuSelectBank(ICM20948_BANK_0);
  imuWrite(ICM20948_PWR_MGMT_1, 0x01);
  delay(10);
  imuWrite(ICM20948_PWR_MGMT_2, 0x00);
  imuSelectBank(ICM20948_BANK_2);
  imuWrite(ICM20948_GYRO_SMPLRT_DIV, 0x04);
  imuWrite(ICM20948_GYRO_CONFIG_1, 0x01);
  imuWrite(ICM20948_GYRO_CONFIG_2, 0x00);
  imuSelectBank(ICM20948_BANK_0);

  imuAvailable = true;
  imuFailureCount = 0;
  calibrateGyroBias();
  lastImuUpdateMs = millis();
  Serial.print("ICM20948 ready addr=0x");
  Serial.print(imuAddress, HEX);
  Serial.print(" gyro_z_bias=");
  Serial.println(gyroZBiasDps, 3);
}

void startGpsSerial(uint32_t baud) {
  Serial2.end();
  delay(20);
  Serial2.begin(baud, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  gpsCurrentBaud = baud;
  gpsBaudScanStartedMs = millis();
  gpsRxLen = 0;
  gpsScanBytes = 0;
  gpsScanNewlines = 0;
  gpsScanDollarSigns = 0;
  gpsScanOverflows = 0;
  gpsNmeaDetected = false;
  Serial.print("GPS serial started baud=");
  Serial.print(baud);
  Serial.print(" rx=");
  Serial.print(GPS_RX_PIN);
  Serial.print(" pps=");
  Serial.println(GPS_PPS_PIN);
}

void setupGps() {
  pinMode(GPS_PPS_PIN, INPUT);
  attachInterrupt(digitalPinToInterrupt(GPS_PPS_PIN), handleGpsPps, RISING);
  startGpsSerial(GPS_BAUDS[gpsBaudIndex]);
}

bool isLikelyNmeaLine(const char* line) {
  return (line[0] == '$' && line[1] != '\0' && line[2] != '\0' && line[3] != '\0');
}

bool nmeaSentenceIs(const char* line, const char* type) {
  return isLikelyNmeaLine(line) &&
    line[3] == type[0] &&
    line[4] == type[1] &&
    line[5] == type[2];
}

float nmeaCoordinateToDegrees(const char* value, char hemisphere, uint8_t degreeDigits) {
  if (value == nullptr || value[0] == '\0') {
    return 0.0f;
  }

  const float raw = static_cast<float>(atof(value));
  const float degrees = floorf(raw / 100.0f);
  const float minutes = raw - degrees * 100.0f;
  float decimalDegrees = degrees + minutes / 60.0f;
  if (hemisphere == 'S' || hemisphere == 'W') {
    decimalDegrees = -decimalDegrees;
  }
  (void)degreeDigits;
  return decimalDegrees;
}

uint8_t splitNmeaFields(char* line, char* fields[], uint8_t maxFields) {
  uint8_t fieldCount = 0;
  fields[fieldCount++] = line;

  for (char* cursor = line; *cursor != '\0' && fieldCount < maxFields; ++cursor) {
    if (*cursor == '*' || *cursor == ',') {
      const bool checksumStart = *cursor == '*';
      *cursor = '\0';
      if (!checksumStart && fieldCount < maxFields) {
        fields[fieldCount++] = cursor + 1;
      }
      if (checksumStart) {
        break;
      }
    }
  }

  return fieldCount;
}

void parseGpsGga(char* line) {
  const uint8_t maxFields = 10;
  char* fields[maxFields] = {};
  const uint8_t fieldCount = splitNmeaFields(line, fields, maxFields);

  if (fieldCount < 8) {
    return;
  }

  gpsFixQuality = static_cast<uint8_t>(constrain(atoi(fields[6]), 0, 9));
  gpsSatellites = static_cast<uint8_t>(constrain(atoi(fields[7]), 0, 99));
  gpsFixValid = gpsFixQuality > 0;

  if (gpsFixValid && fields[2][0] != '\0' && fields[4][0] != '\0') {
    gpsLatitudeDegrees = nmeaCoordinateToDegrees(fields[2], fields[3][0], 2);
    gpsLongitudeDegrees = nmeaCoordinateToDegrees(fields[4], fields[5][0], 3);
  }
}

void parseGpsRmc(char* line) {
  const uint8_t maxFields = 8;
  char* fields[maxFields] = {};
  const uint8_t fieldCount = splitNmeaFields(line, fields, maxFields);

  if (fieldCount >= 3 && fields[2][0] != '\0') {
    gpsFixValid = fields[2][0] == 'A';
  }
  if (gpsFixValid && fieldCount >= 7 && fields[3][0] != '\0' && fields[5][0] != '\0') {
    gpsLatitudeDegrees = nmeaCoordinateToDegrees(fields[3], fields[4][0], 2);
    gpsLongitudeDegrees = nmeaCoordinateToDegrees(fields[5], fields[6][0], 3);
  }
}

void parseGpsTxt(const char* line) {
  if (strstr(line, "ANTENNA OPEN") != nullptr) {
    gpsAntennaOpen = true;
    gpsAntennaStatusKnown = true;
  } else if (
    strstr(line, "ANTENNA OK") != nullptr ||
    strstr(line, "ANTENNA NORMAL") != nullptr
  ) {
    gpsAntennaOpen = false;
    gpsAntennaStatusKnown = true;
  }
}

void parseGpsNmeaLine(const char* line) {
  char buffer[GPS_RX_BUFFER_SIZE] = {};
  strncpy(buffer, line, sizeof(buffer) - 1);

  if (nmeaSentenceIs(line, "GGA")) {
    parseGpsGga(buffer);
  } else if (nmeaSentenceIs(line, "RMC")) {
    parseGpsRmc(buffer);
  } else if (nmeaSentenceIs(line, "TXT")) {
    parseGpsTxt(line);
  }
}

void logGpsLine(const char* line) {
  if (isLikelyNmeaLine(line)) {
    if (!gpsNmeaDetected) {
      Serial.print("GPS NMEA detected baud=");
      Serial.println(gpsCurrentBaud);
    }
    gpsNmeaDetected = true;
    gpsSentenceCount += 1;
    gpsLastSentenceMs = millis();
    parseGpsNmeaLine(line);
  }
  Serial.print("GPS NMEA ");
  Serial.println(line);
}

void processGpsInput(uint32_t now) {
  while (Serial2.available() > 0) {
    const char next = static_cast<char>(Serial2.read());
    gpsByteCount += 1;
    gpsScanBytes += 1;
    gpsLastByteMs = now;

    if (next == '\r') {
      continue;
    }
    if (next == '\n') {
      gpsScanNewlines += 1;
      gpsRxBuffer[gpsRxLen] = '\0';
      if (gpsRxLen > 0) {
        logGpsLine(gpsRxBuffer);
      }
      gpsRxLen = 0;
      continue;
    }

    if (next == '$') {
      gpsScanDollarSigns += 1;
    }
    if (gpsRxLen < GPS_RX_BUFFER_SIZE - 1) {
      gpsRxBuffer[gpsRxLen++] = next;
    } else {
      gpsRxLen = 0;
      gpsScanOverflows += 1;
    }
  }
}

void updateGpsBaudScan(uint32_t now) {
  if (gpsNmeaDetected || now - gpsBaudScanStartedMs < GPS_BAUD_SCAN_MS) {
    return;
  }

  Serial.print("GPS scan baud=");
  Serial.print(gpsCurrentBaud);
  Serial.print(";bytes=");
  Serial.print(gpsScanBytes);
  Serial.print(";newlines=");
  Serial.print(gpsScanNewlines);
  Serial.print(";dollar=");
  Serial.print(gpsScanDollarSigns);
  Serial.print(";overflows=");
  Serial.println(gpsScanOverflows);

  gpsBaudIndex = (gpsBaudIndex + 1) % (sizeof(GPS_BAUDS) / sizeof(GPS_BAUDS[0]));
  startGpsSerial(GPS_BAUDS[gpsBaudIndex]);
}

void processGpsPps(uint32_t now) {
  noInterrupts();
  const uint32_t nextPpsCount = gpsPpsCount;
  interrupts();

  if (nextPpsCount == gpsReportedPpsCount) {
    return;
  }
  gpsReportedPpsCount = nextPpsCount;
  gpsLastPpsMs = now;
  Serial.print("GPS PPS count=");
  Serial.println(gpsReportedPpsCount);
}

void updateImu(uint32_t now) {
  if (!imuAvailable) {
    return;
  }

  float gyroZDps = 0.0f;
  if (!readGyroZDps(gyroZDps)) {
    imuFailureCount += 1;
    if (imuFailureCount >= 5) {
      imuAvailable = false;
      turnControlActive = false;
      headingLockActive = false;
      requestedLeftPercent = 0;
      requestedRightPercent = 0;
      Serial.println("IMU read failed; angle turn and heading lock disabled");
      SerialBT.println("STATUS;FAULT=IMU_READ_FAILED");
    }
    return;
  }

  imuFailureCount = 0;
  if (lastImuUpdateMs == 0) {
    lastImuUpdateMs = now;
    return;
  }

  const uint32_t elapsedMs = now - lastImuUpdateMs;
  lastImuUpdateMs = now;
  if (elapsedMs == 0 || elapsedMs > 250) {
    return;
  }

  headingDegrees = normalizeAngle180(
    headingDegrees + (gyroZDps - gyroZBiasDps) * (static_cast<float>(elapsedMs) / 1000.0f)
  );
}

int8_t applyTurnEscDirection(int8_t percent, bool reversed) {
  return reversed ? static_cast<int8_t>(-percent) : percent;
}

void cancelTurnControl() {
  turnControlActive = false;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  lastCommandMode = CommandMode::Throttle;
}

void cancelHeadingLockControl() {
  headingLockActive = false;
  lastHeadingLockCorrectionPercent = 0;
  lastCommandMode = CommandMode::Throttle;
}

void cancelAutonomousControl() {
  cancelTurnControl();
  cancelHeadingLockControl();
}

void forceNeutralAndDisarm() {
  armed = false;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  turnControlActive = false;
  turnControlCompleted = false;
  headingLockActive = false;
  lastHeadingLockCorrectionPercent = 0;
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

bool parseSourceToken(const char* token, CommandSource& outSource) {
  if (strcmp(token, "SRC=APP") == 0) {
    outSource = CommandSource::App;
    return true;
  }
  if (strcmp(token, "SRC=VOICE") == 0) {
    outSource = CommandSource::Voice;
    return true;
  }
  return false;
}

bool parseModeToken(const char* token, CommandMode& outMode) {
  if (strcmp(token, "MODE=THROTTLE") == 0) {
    outMode = CommandMode::Throttle;
    return true;
  }
  if (strcmp(token, "MODE=TURN") == 0) {
    outMode = CommandMode::TurnAngle;
    return true;
  }
  if (strcmp(token, "MODE=HEADING_LOCK") == 0) {
    outMode = CommandMode::HeadingLock;
    return true;
  }
  return false;
}

bool parseTurnDirectionToken(const char* token, TurnDirection& outDirection) {
  if (strcmp(token, "DIR=LEFT") == 0) {
    outDirection = TurnDirection::Left;
    return true;
  }
  if (strcmp(token, "DIR=RIGHT") == 0) {
    outDirection = TurnDirection::Right;
    return true;
  }
  return false;
}

bool parseUnsignedToken(const char* token, const char* prefix, uint16_t minValue, uint16_t maxValue, uint16_t& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const unsigned long parsed = strtoul(token + prefixLen, &end, 10);
  if (end == token + prefixLen || *end != '\0' || parsed < minValue || parsed > maxValue) {
    return false;
  }

  outValue = static_cast<uint16_t>(parsed);
  return true;
}

bool parseBoolToken(const char* token, const char* prefix, bool& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }
  if (strcmp(token + prefixLen, "0") == 0) {
    outValue = false;
    return true;
  }
  if (strcmp(token + prefixLen, "1") == 0) {
    outValue = true;
    return true;
  }
  return false;
}

void printIdentity(Print& output) {
  output.print("ID;VALUE=");
  printPaddedUnitId(output, unitId);
  output.print(";BT=");
  output.print(btDeviceName);
  output.print(";SRC=");
  output.print(unitIdSource);
  output.print(";PROVISIONED=");
  output.println(unitIdProvisioned ? 1 : 0);
}

bool applyIdentityLine(char* line, Print& response) {
  if (strcmp(line, "ID?") == 0 || strcmp(line, "ID") == 0) {
    printIdentity(response);
    return true;
  }

  if (strncmp(line, "ID_SET", 6) == 0) {
    response.println("ID;ERR=FACTORY_ONLY");
    Serial.println("ID set rejected: factory provisioning only");
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

void applyTurnAngleCommand(
  TurnDirection direction,
  uint16_t angleDegrees,
  uint16_t requestId,
  bool leftEscReversed,
  bool rightEscReversed
) {
  if (!imuAvailable) {
    SerialBT.println("ERR;IMU_UNAVAILABLE");
    Serial.println("Turn angle rejected: IMU unavailable");
    return;
  }

  const uint32_t now = millis();
  lastValidBtCommandMs = now;
  lastCommandSource = CommandSource::Voice;
  lastCommandMode = CommandMode::TurnAngle;

  if (turnControlActive && requestId == activeTurnRequestId) {
    return;
  }
  if (turnControlCompleted && requestId == completedTurnRequestId) {
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    return;
  }

  const float signedAngle = direction == TurnDirection::Left
    ? -static_cast<float>(angleDegrees)
    : static_cast<float>(angleDegrees);

  turnTargetHeadingDegrees = normalizeAngle180(headingDegrees + signedAngle);
  turnStartedMs = now;
  activeTurnRequestId = requestId;
  turnControlActive = true;
  turnControlCompleted = false;
  headingLockActive = false;
  turnLeftEscReversed = leftEscReversed;
  turnRightEscReversed = rightEscReversed;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;

  Serial.print("Turn angle start tid=");
  Serial.print(requestId);
  Serial.print(" dir=");
  Serial.print(direction == TurnDirection::Left ? "LEFT" : "RIGHT");
  Serial.print(" angle=");
  Serial.print(angleDegrees);
  Serial.print(" current=");
  Serial.print(headingDegrees, 1);
  Serial.print(" target=");
  Serial.println(turnTargetHeadingDegrees, 1);
  SerialBT.print("STATUS;TURN=START;TID=");
  SerialBT.print(requestId);
  SerialBT.print(";TARGET=");
  SerialBT.println(turnTargetHeadingDegrees, 1);
}

void updateTurnControl(uint32_t now) {
  if (!turnControlActive) {
    return;
  }

  const bool freshCommand = lastValidBtCommandMs != 0 && now - lastValidBtCommandMs <= BT_COMMAND_TIMEOUT_MS;
  if (!armed || !freshCommand) {
    cancelTurnControl();
    return;
  }

  if (!imuAvailable) {
    forceNeutralAndDisarm();
    SerialBT.println("STATUS;ARMED=0;FAULT=IMU_UNAVAILABLE");
    return;
  }

  if (now - turnStartedMs > TURN_CONTROL_TIMEOUT_MS) {
    cancelTurnControl();
    turnControlCompleted = true;
    completedTurnRequestId = activeTurnRequestId;
    SerialBT.print("STATUS;TURN=TIMEOUT;TID=");
    SerialBT.println(activeTurnRequestId);
    return;
  }

  const float errorDegrees = shortestAngleError(turnTargetHeadingDegrees, headingDegrees);
  lastTurnErrorDegrees = errorDegrees;
  const float absError = fabs(errorDegrees);
  if (absError <= TURN_DONE_DEGREES) {
    cancelTurnControl();
    turnControlCompleted = true;
    completedTurnRequestId = activeTurnRequestId;
    SerialBT.print("STATUS;TURN=DONE;TID=");
    SerialBT.print(activeTurnRequestId);
    SerialBT.print(";ERR=");
    SerialBT.println(errorDegrees, 1);
    return;
  }

  const float speedRatio = min(absError, TURN_SLOWDOWN_DEGREES) / TURN_SLOWDOWN_DEGREES;
  const int8_t outerPercent = static_cast<int8_t>(
    TURN_MIN_OUTER_PERCENT + (TURN_MAX_OUTER_PERCENT - TURN_MIN_OUTER_PERCENT) * speedRatio
  );
  int8_t rawLeft = TURN_INNER_PERCENT;
  int8_t rawRight = outerPercent;
  if (errorDegrees > 0.0f) {
    rawLeft = outerPercent;
    rawRight = TURN_INNER_PERCENT;
  }

  requestedLeftPercent = applyTurnEscDirection(rawLeft, turnLeftEscReversed);
  requestedRightPercent = applyTurnEscDirection(rawRight, turnRightEscReversed);
  applyVoiceLimits(requestedLeftPercent, requestedRightPercent);
}

void applyHeadingLockCommand(
  bool enabled,
  int8_t basePercent,
  uint16_t requestId,
  bool leftEscReversed,
  bool rightEscReversed,
  CommandSource source
) {
  const uint32_t now = millis();
  lastValidBtCommandMs = now;
  lastCommandSource = source;

  if (!enabled) {
    cancelHeadingLockControl();
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    Serial.println("Heading lock cancelled by command");
    SerialBT.println("STATUS;HLOCK=OFF");
    return;
  }

  if (!imuAvailable) {
    SerialBT.println("ERR;IMU_UNAVAILABLE");
    Serial.println("Heading lock rejected: IMU unavailable");
    return;
  }

  lastCommandMode = CommandMode::HeadingLock;
  turnControlActive = false;
  headingLockBasePercent = clampHeadingLockBasePercent(basePercent);
  headingLockLeftEscReversed = leftEscReversed;
  headingLockRightEscReversed = rightEscReversed;

  if (headingLockActive && requestId == activeHeadingLockRequestId) {
    return;
  }

  activeHeadingLockRequestId = requestId;
  headingLockTargetDegrees = headingDegrees;
  lastHeadingLockErrorDegrees = 0.0f;
  lastHeadingLockCorrectionPercent = 0;
  headingLockActive = true;

  Serial.print("Heading lock start hid=");
  Serial.print(requestId);
  Serial.print(" base=");
  Serial.print(headingLockBasePercent);
  Serial.print(" target=");
  Serial.println(headingLockTargetDegrees, 1);
  SerialBT.print("STATUS;HLOCK=START;HID=");
  SerialBT.print(requestId);
  SerialBT.print(";TARGET=");
  SerialBT.println(headingLockTargetDegrees, 1);
}

void updateHeadingLockControl(uint32_t now) {
  if (!headingLockActive) {
    return;
  }

  const bool freshCommand = lastValidBtCommandMs != 0 && now - lastValidBtCommandMs <= BT_COMMAND_TIMEOUT_MS;
  if (!armed || !freshCommand) {
    cancelHeadingLockControl();
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    return;
  }

  if (!imuAvailable) {
    forceNeutralAndDisarm();
    SerialBT.println("STATUS;ARMED=0;FAULT=IMU_UNAVAILABLE");
    return;
  }

  const float errorDegrees = shortestAngleError(headingLockTargetDegrees, headingDegrees);
  lastHeadingLockErrorDegrees = errorDegrees;
  const float absError = fabs(errorDegrees);

  int8_t correction = 0;
  if (absError > HEADING_LOCK_TOLERANCE_DEGREES) {
    const float activeError = min(absError, HEADING_LOCK_FULL_CORRECTION_DEGREES) - HEADING_LOCK_TOLERANCE_DEGREES;
    const float activeRange = HEADING_LOCK_FULL_CORRECTION_DEGREES - HEADING_LOCK_TOLERANCE_DEGREES;
    const float ratio = activeRange > 0.0f ? activeError / activeRange : 1.0f;
    correction = static_cast<int8_t>(max(1, static_cast<int>(roundf(HEADING_LOCK_MAX_CORRECTION_PERCENT * ratio))));
    if (errorDegrees < 0.0f) {
      correction = -correction;
    }
  }
  lastHeadingLockCorrectionPercent = correction;

  int rawLeft = headingLockBasePercent + correction;
  int rawRight = headingLockBasePercent - correction;
  if (headingLockBasePercent > 0) {
    rawLeft = max(0, rawLeft);
    rawRight = max(0, rawRight);
  } else if (headingLockBasePercent < 0) {
    rawLeft = min(0, rawLeft);
    rawRight = min(0, rawRight);
  }

  int8_t nextLeft = static_cast<int8_t>(constrain(rawLeft, -100, 100));
  int8_t nextRight = static_cast<int8_t>(constrain(rawRight, -100, 100));
  nextLeft = applyTurnEscDirection(nextLeft, headingLockLeftEscReversed);
  nextRight = applyTurnEscDirection(nextRight, headingLockRightEscReversed);
  applyHeadingLockLimits(nextLeft, nextRight);

  requestedLeftPercent = nextLeft;
  requestedRightPercent = nextRight;
}

void applyBluetoothLine(char* line) {
  if (strncmp(line, "OTA_BEGIN", 9) == 0) {
    beginOta(line);
    return;
  }
  if (applyIdentityLine(line, SerialBT)) {
    return;
  }

  bool sawArm = false;
  bool sawLeft = false;
  bool sawRight = false;
  bool sawSource = false;
  bool sawMode = false;
  bool sawDirection = false;
  bool sawAngle = false;
  bool sawTurnRequestId = false;
  bool sawHeadingLock = false;
  bool sawHeadingLockRequestId = false;
  bool badSource = false;
  bool badMode = false;
  bool badTurnToken = false;
  bool badHeadingLockToken = false;
  bool nextArmed = false;
  int8_t nextLeftPercent = 0;
  int8_t nextRightPercent = 0;
  int8_t nextHeadingLockBasePercent = 0;
  CommandSource nextSource = CommandSource::App;
  CommandMode nextMode = CommandMode::Throttle;
  TurnDirection nextDirection = TurnDirection::Left;
  uint16_t nextAngleDegrees = 0;
  uint16_t nextTurnRequestId = 0;
  uint16_t nextHeadingLockRequestId = 0;
  bool nextHeadingLockEnabled = false;
  bool nextLeftEscReversed = false;
  bool nextRightEscReversed = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (parseArmToken(token, nextArmed)) {
      sawArm = true;
    } else if (parsePercentToken(token, "L=", nextLeftPercent)) {
      sawLeft = true;
    } else if (parsePercentToken(token, "R=", nextRightPercent)) {
      sawRight = true;
    } else if (parseSourceToken(token, nextSource)) {
      badSource = badSource || sawSource;
      sawSource = true;
    } else if (parseModeToken(token, nextMode)) {
      badMode = badMode || sawMode;
      sawMode = true;
    } else if (parseTurnDirectionToken(token, nextDirection)) {
      badTurnToken = badTurnToken || sawDirection;
      sawDirection = true;
    } else if (parseUnsignedToken(token, "ANGLE=", 1, MAX_VOICE_TURN_ANGLE_DEGREES, nextAngleDegrees)) {
      badTurnToken = badTurnToken || sawAngle;
      sawAngle = true;
    } else if (parseUnsignedToken(token, "TID=", 1, 65535, nextTurnRequestId)) {
      badTurnToken = badTurnToken || sawTurnRequestId;
      sawTurnRequestId = true;
    } else if (parseBoolToken(token, "HLOCK=", nextHeadingLockEnabled)) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLock;
      sawHeadingLock = true;
    } else if (parsePercentToken(token, "BASE=", nextHeadingLockBasePercent)) {
      // Optional base throttle for heading lock.
    } else if (parseUnsignedToken(token, "HID=", 1, 65535, nextHeadingLockRequestId)) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockRequestId;
      sawHeadingLockRequestId = true;
    } else if (parseBoolToken(token, "LREV=", nextLeftEscReversed)) {
      // Optional Android-side ESC direction setting for autonomous angle turns.
    } else if (parseBoolToken(token, "RREV=", nextRightEscReversed)) {
      // Optional Android-side ESC direction setting for autonomous angle turns.
    } else if (strncmp(token, "SRC=", 4) == 0) {
      badSource = true;
    } else if (strncmp(token, "MODE=", 5) == 0) {
      badMode = true;
    } else if (
      strncmp(token, "DIR=", 4) == 0 ||
      strncmp(token, "ANGLE=", 6) == 0 ||
      strncmp(token, "TID=", 4) == 0 ||
      strncmp(token, "LREV=", 5) == 0 ||
      strncmp(token, "RREV=", 5) == 0
    ) {
      badTurnToken = true;
    } else if (
      strncmp(token, "HLOCK=", 6) == 0 ||
      strncmp(token, "BASE=", 5) == 0 ||
      strncmp(token, "HID=", 4) == 0
    ) {
      badHeadingLockToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (badSource) {
    SerialBT.println("ERR;BAD_SRC");
    Serial.println("Bluetooth command rejected: bad source");
    return;
  }

  if (badMode || badTurnToken) {
    SerialBT.println("ERR;BAD_TURN_COMMAND");
    Serial.println("Bluetooth command rejected: bad turn token");
    return;
  }

  if (badHeadingLockToken) {
    SerialBT.println("ERR;BAD_HEADING_LOCK_COMMAND");
    Serial.println("Bluetooth command rejected: bad heading lock token");
    return;
  }

  if (nextMode == CommandMode::TurnAngle) {
    if (nextSource != CommandSource::Voice) {
      SerialBT.println("ERR;TURN_REQUIRES_VOICE_SRC");
      Serial.println("Turn angle rejected: source is not voice");
      return;
    }
    if (!sawArm || !nextArmed) {
      SerialBT.println("ERR;TURN_REQUIRES_ARM");
      Serial.println("Turn angle rejected: missing ARM=1");
      return;
    }
    if (!armed) {
      SerialBT.println("ERR;VOICE_CANNOT_ARM");
      Serial.println("Voice turn rejected: cannot arm from locked state");
      return;
    }
    if (!sawDirection || !sawAngle || !sawTurnRequestId) {
      SerialBT.println("ERR;BAD_TURN_COMMAND");
      Serial.println("Turn angle rejected: missing direction, angle, or request id");
      return;
    }
    applyTurnAngleCommand(
      nextDirection,
      nextAngleDegrees,
      nextTurnRequestId,
      nextLeftEscReversed,
      nextRightEscReversed
    );
    return;
  }

  if (nextMode == CommandMode::HeadingLock) {
    if (!sawArm || !nextArmed) {
      SerialBT.println("ERR;HEADING_LOCK_REQUIRES_ARM");
      Serial.println("Heading lock rejected: missing ARM=1");
      return;
    }
    if (!armed) {
      SerialBT.println("ERR;VOICE_CANNOT_ARM");
      Serial.println("Heading lock rejected: cannot arm from locked state");
      return;
    }
    if (!sawHeadingLock) {
      SerialBT.println("ERR;BAD_HEADING_LOCK_COMMAND");
      Serial.println("Heading lock rejected: missing HLOCK");
      return;
    }
    if (nextHeadingLockEnabled && !sawHeadingLockRequestId) {
      SerialBT.println("ERR;BAD_HEADING_LOCK_COMMAND");
      Serial.println("Heading lock rejected: missing HID");
      return;
    }
    applyHeadingLockCommand(
      nextHeadingLockEnabled,
      nextHeadingLockBasePercent,
      nextHeadingLockRequestId,
      nextLeftEscReversed,
      nextRightEscReversed,
      nextSource
    );
    return;
  }

  if (!sawArm || !sawLeft || !sawRight) {
    SerialBT.println("ERR;BAD_COMMAND");
    Serial.println("Bluetooth command rejected");
    return;
  }

  if (nextSource == CommandSource::Voice && nextArmed && !armed) {
    SerialBT.println("ERR;VOICE_CANNOT_ARM");
    Serial.println("Voice command rejected: cannot arm from locked state");
    return;
  }

  if (nextSource == CommandSource::Voice && nextArmed) {
    applyVoiceLimits(nextLeftPercent, nextRightPercent);
  }

  cancelAutonomousControl();
  armed = nextArmed;
  requestedLeftPercent = nextArmed ? nextLeftPercent : 0;
  requestedRightPercent = nextArmed ? nextRightPercent : 0;
  lastCommandSource = nextSource;
  lastCommandMode = CommandMode::Throttle;
  lastValidBtCommandMs = millis();

  Serial.print("BT command armed=");
  Serial.print(armed ? 1 : 0);
  Serial.print(" src=");
  Serial.print(commandSourceName(lastCommandSource));
  Serial.print(" left=");
  Serial.print(requestedLeftPercent);
  Serial.print(" right=");
  Serial.println(requestedRightPercent);
}

void applySerialLine(char* line) {
  if (applyIdentityLine(line, Serial)) {
    return;
  }

  Serial.println("ERR;UNKNOWN_SERIAL_COMMAND");
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

void processSerialInput() {
  while (Serial.available() > 0) {
    const char next = static_cast<char>(Serial.read());
    if (next == '\r') {
      continue;
    }
    if (next == '\n') {
      serialRxBuffer[serialRxLen] = '\0';
      if (serialRxLen > 0) {
        applySerialLine(serialRxBuffer);
      }
      serialRxLen = 0;
      continue;
    }

    if (serialRxLen < SERIAL_RX_BUFFER_SIZE - 1) {
      serialRxBuffer[serialRxLen++] = next;
    } else {
      serialRxLen = 0;
      Serial.println("ERR;SERIAL_LINE_TOO_LONG");
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
    cancelAutonomousControl();
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
  SerialBT.print(";CMD_SRC=");
  SerialBT.print(commandSourceName(lastCommandSource));
  SerialBT.print(";MODE=");
  SerialBT.print(commandModeName(lastCommandMode));
  SerialBT.print(";LPWM=");
  SerialBT.print(leftPulseUs);
  SerialBT.print(";RPWM=");
  SerialBT.print(rightPulseUs);
  SerialBT.print(";IMU=");
  SerialBT.print(imuAvailable ? 1 : 0);
  SerialBT.print(";HDG=");
  SerialBT.print(headingDegrees, 1);
  SerialBT.print(";GPS=");
  SerialBT.print(gpsLastByteMs != 0 && now - gpsLastByteMs <= GPS_PRESENT_TIMEOUT_MS ? 1 : 0);
  SerialBT.print(";PPS=");
  SerialBT.print(gpsLastPpsMs != 0 && now - gpsLastPpsMs <= GPS_PPS_TIMEOUT_MS ? 1 : 0);
  SerialBT.print(";GPS_SENT=");
  SerialBT.print(gpsSentenceCount);
  SerialBT.print(";GPS_BAUD=");
  SerialBT.print(gpsCurrentBaud);
  SerialBT.print(";GPS_FIX=");
  SerialBT.print(gpsFixValid ? 1 : 0);
  SerialBT.print(";GPS_SAT=");
  SerialBT.print(gpsSatellites);
  SerialBT.print(";GPS_ANT=");
  if (!gpsAntennaStatusKnown) {
    SerialBT.print("UNKNOWN");
  } else {
    SerialBT.print(gpsAntennaOpen ? "OPEN" : "OK");
  }
  if (gpsFixValid) {
    SerialBT.print(";GPS_LAT=");
    SerialBT.print(gpsLatitudeDegrees, 6);
    SerialBT.print(";GPS_LON=");
    SerialBT.print(gpsLongitudeDegrees, 6);
  }
  if (turnControlActive) {
    SerialBT.print(";TURN=ACTIVE;TID=");
    SerialBT.print(activeTurnRequestId);
    SerialBT.print(";TARGET=");
    SerialBT.print(turnTargetHeadingDegrees, 1);
    SerialBT.print(";TERR=");
    SerialBT.print(lastTurnErrorDegrees, 1);
  }
  if (headingLockActive) {
    SerialBT.print(";HLOCK=ACTIVE;HID=");
    SerialBT.print(activeHeadingLockRequestId);
    SerialBT.print(";TARGET=");
    SerialBT.print(headingLockTargetDegrees, 1);
    SerialBT.print(";HERR=");
    SerialBT.print(lastHeadingLockErrorDegrees, 1);
    SerialBT.print(";HCORR=");
    SerialBT.print(lastHeadingLockCorrectionPercent);
  }
  SerialBT.print(";ID=");
  printPaddedUnitId(SerialBT, unitId);
  SerialBT.print(";BT=");
  SerialBT.print(btDeviceName);
  SerialBT.print(";ID_SRC=");
  SerialBT.println(unitIdSource);
}
}  // namespace

void setup() {
  Serial.begin(115200);
  loadUnitId();
  pinMode(ARM_BUTTON_PIN, INPUT_PULLUP);
  pinMode(STATUS_LED_PIN, OUTPUT);

  ledcSetup(LEFT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcSetup(RIGHT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcAttachPin(LEFT_ESC_PIN, LEFT_ESC_CHANNEL);
  ledcAttachPin(RIGHT_ESC_PIN, RIGHT_ESC_CHANNEL);

  writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);
  setupImu();
  setupGps();

  if (!SerialBT.begin(btDeviceName)) {
    Serial.println("Bluetooth start failed");
  } else {
    Serial.print("Bluetooth SPP started; name=");
    Serial.println(btDeviceName);
  }

  Serial.print("Smart SUP controller booted; state=DISARMED; id=");
  printPaddedUnitId(Serial, unitId);
  Serial.print("; bt=");
  Serial.print(btDeviceName);
  Serial.print("; id_src=");
  Serial.print(unitIdSource);
  Serial.print("; provisioned=");
  Serial.println(unitIdProvisioned ? 1 : 0);
}

void loop() {
  processSerialInput();
  processBluetoothInput();

  const uint32_t now = millis();
  handleOtaTimeout(now);
  if (otaInProgress) {
    digitalWrite(STATUS_LED_PIN, (now / 100) % 2);
    return;
  }

  updateImu(now);
  processGpsInput(now);
  updateGpsBaudScan(now);
  processGpsPps(now);
  updateArmState();
  applyCommandTimeout(now);
  updateTurnControl(now);
  updateHeadingLockControl(now);
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
