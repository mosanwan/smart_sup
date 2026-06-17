#include <Arduino.h>
#include "BluetoothSerial.h"
#include "esp_partition.h"
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
constexpr char NVS_TRACK_SESSION_KEY[] = "track_sid";
constexpr char NVS_MAG_CAL_VALID_KEY[] = "mag_cal";
constexpr char NVS_MAG_OFFSET_X_KEY[] = "mag_off_x";
constexpr char NVS_MAG_OFFSET_Y_KEY[] = "mag_off_y";
constexpr char NVS_MAG_SCALE_X_KEY[] = "mag_scl_x";
constexpr char NVS_MAG_SCALE_Y_KEY[] = "mag_scl_y";
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
constexpr uint8_t MIN_VOICE_POWER_LIMIT_PERCENT = 5;
constexpr uint8_t MAX_VOICE_POWER_LIMIT_PERCENT = 100;
constexpr uint8_t DEFAULT_VOICE_POWER_LIMIT_PERCENT = 70;
constexpr int8_t VOICE_MAX_TURN_DELTA_PERCENT = 20;
constexpr int8_t TURN_INNER_PERCENT = 8;
constexpr int8_t TURN_MIN_OUTER_PERCENT = 14;
constexpr int8_t TURN_MAX_OUTER_PERCENT = 24;
constexpr float TURN_DONE_DEGREES = 3.0f;
constexpr float TURN_SLOWDOWN_DEGREES = 45.0f;
constexpr int8_t HEADING_LOCK_MIN_CORRECTION_PERCENT = 3;
constexpr int8_t HEADING_LOCK_MAX_CORRECTION_PERCENT = 70;
constexpr int8_t VOICE_HEADING_LOCK_MAX_OUTPUT_PERCENT = 100;
constexpr int8_t APP_HEADING_LOCK_MAX_OUTPUT_PERCENT = 100;
constexpr uint16_t DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES = 4;
constexpr uint16_t DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES = 6;
constexpr uint8_t DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = 70;
constexpr uint16_t MIN_HEADING_LOCK_TOLERANCE_DEGREES = 1;
constexpr uint16_t MAX_HEADING_LOCK_TOLERANCE_DEGREES = 20;
constexpr uint16_t MIN_HEADING_LOCK_FULL_CORRECTION_DEGREES = 5;
constexpr uint16_t MAX_HEADING_LOCK_FULL_CORRECTION_DEGREES = 180;
constexpr uint8_t MIN_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = 0;
constexpr uint8_t MAX_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = 100;
constexpr uint16_t MAX_VOICE_TURN_ANGLE_DEGREES = 90;
constexpr int16_t MIN_HEADING_LOCK_TARGET_OFFSET_DEGREES = -90;
constexpr int16_t MAX_HEADING_LOCK_TARGET_OFFSET_DEGREES = 90;
constexpr float ICM20948_GYRO_250DPS_LSB_PER_DPS = 131.0f;
constexpr float IMU_YAW_SIGN = 1.0f;
constexpr float RADIANS_TO_DEGREES = 57.2957795f;
constexpr float MAG_HEADING_OFFSET_DEGREES = 0.0f;
constexpr float MAG_HEADING_FILTER_ALPHA = 0.22f;
constexpr uint32_t MAG_HEADING_INTERVAL_MS = 20;
constexpr uint32_t PHONE_HEADING_TIMEOUT_MS = 1000;
constexpr uint16_t MAG_CAL_MIN_SAMPLES = 80;
constexpr int32_t MAG_CAL_MIN_RANGE = 100;
constexpr float MAG_CAL_MIN_SCALE = 0.2f;
constexpr float MAG_CAL_MAX_SCALE = 5.0f;
constexpr uint8_t IMU_FAILURE_LIMIT = 10;
constexpr size_t BT_RX_BUFFER_SIZE = 192;
constexpr size_t SERIAL_RX_BUFFER_SIZE = 96;
constexpr size_t GPS_RX_BUFFER_SIZE = 128;
constexpr size_t OTA_BUFFER_SIZE = 512;
constexpr size_t BT_DEVICE_NAME_SIZE = 16;
constexpr uint32_t GPS_BAUDS[] = {9600, 38400, 57600, 115200, 4800, 19200};
constexpr uint32_t GPS_BAUD_SCAN_MS = 5000;
constexpr uint32_t GPS_PRESENT_TIMEOUT_MS = 3000;
constexpr uint32_t GPS_PPS_TIMEOUT_MS = 2500;
constexpr char TRACKLOG_PARTITION_LABEL[] = "tracklog";
constexpr uint8_t TRACKLOG_PARTITION_SUBTYPE = 0x40;
constexpr size_t TRACKLOG_RECORD_SIZE = 16;
constexpr size_t TRACKLOG_FLASH_SECTOR_SIZE = 4096;
constexpr uint32_t TRACKLOG_INTERVAL_MS = 1000;
constexpr uint32_t TRACKLOG_NO_FIX_RESET_MS = 30000;
constexpr uint8_t TRACKLOG_STABLE_POINTS_REQUIRED = 3;
constexpr uint8_t TRACKLOG_MIN_SATELLITES = 4;
constexpr float TRACKLOG_MAX_SPEED_MPS = 5.0f;
constexpr uint32_t TRACKLOG_INVALID_SEQ24 = 0xFFFFFF;
constexpr uint32_t TRACKLOG_MAX_SEQ24 = 0xFFFFFE;
constexpr uint32_t TRACKLOG_MIN_VALID_UTC = 1609459200UL;
constexpr uint32_t TRACKLOG_STALE_FUTURE_SECONDS = 7UL * 24UL * 3600UL;
constexpr uint8_t ICM20948_ADDR_LOW = 0x68;
constexpr uint8_t ICM20948_ADDR_HIGH = 0x69;
constexpr uint8_t ICM20948_WHO_AM_I_VALUE = 0xEA;
constexpr uint8_t ICM20948_REG_BANK_SEL = 0x7F;
constexpr uint8_t ICM20948_BANK_0 = 0x00;
constexpr uint8_t ICM20948_BANK_2 = 0x20;
constexpr uint8_t ICM20948_WHO_AM_I = 0x00;
constexpr uint8_t ICM20948_PWR_MGMT_1 = 0x06;
constexpr uint8_t ICM20948_PWR_MGMT_2 = 0x07;
constexpr uint8_t ICM20948_INT_PIN_CFG = 0x0F;
constexpr uint8_t ICM20948_USER_CTRL = 0x03;
constexpr uint8_t ICM20948_GYRO_ZOUT_H = 0x37;
constexpr uint8_t ICM20948_GYRO_SMPLRT_DIV = 0x00;
constexpr uint8_t ICM20948_GYRO_CONFIG_1 = 0x01;
constexpr uint8_t ICM20948_GYRO_CONFIG_2 = 0x02;
constexpr uint8_t AK09916_ADDR = 0x0C;
constexpr uint8_t AK09916_WIA2 = 0x01;
constexpr uint8_t AK09916_WIA2_VALUE = 0x09;
constexpr uint8_t AK09916_ST1 = 0x10;
constexpr uint8_t AK09916_CNTL2 = 0x31;
constexpr uint8_t AK09916_CNTL3 = 0x32;
constexpr uint8_t AK09916_MODE_CONTINUOUS_50HZ = 0x06;

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

struct __attribute__((packed)) TrackLogRecord {
  uint32_t utcSeconds;
  int32_t latE7;
  int32_t lonE7;
  uint8_t sessionId;
  uint8_t seq24[3];
};

static_assert(sizeof(TrackLogRecord) == TRACKLOG_RECORD_SIZE, "track log record must stay 16 bytes");

bool armed = false;
uint16_t leftPulseUs = ESC_NEUTRAL_US;
uint16_t rightPulseUs = ESC_NEUTRAL_US;
int8_t requestedLeftPercent = 0;
int8_t requestedRightPercent = 0;
CommandSource lastCommandSource = CommandSource::App;
CommandMode lastCommandMode = CommandMode::Throttle;
uint32_t lastTickMs = 0;
uint32_t lastImuUpdateMs = 0;
uint32_t lastMagHeadingReadMs = 0;
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
bool magnetometerAvailable = false;
bool headingFilterInitialized = false;
bool magCalibrationValid = false;
bool magCalibrationActive = false;
bool phoneHeadingEnabled = false;
uint8_t imuAddress = ICM20948_ADDR_LOW;
uint8_t imuFailureCount = 0;
float headingDegrees = 0.0f;
float phoneHeadingDegrees = 0.0f;
uint32_t lastPhoneHeadingMs = 0;
float magOffsetX = 0.0f;
float magOffsetY = 0.0f;
float magScaleX = 1.0f;
float magScaleY = 1.0f;
int32_t magCalibrationMinX = 0;
int32_t magCalibrationMaxX = 0;
int32_t magCalibrationMinY = 0;
int32_t magCalibrationMaxY = 0;
uint16_t magCalibrationSamples = 0;
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
bool headingLockOffsetRequestConsumed = false;
uint16_t consumedHeadingLockOffsetRequestId = 0;
float headingLockTargetDegrees = 0.0f;
float lastHeadingLockErrorDegrees = 0.0f;
int8_t headingLockBasePercent = 0;
int8_t lastHeadingLockCorrectionPercent = 0;
uint16_t headingLockToleranceDegrees = DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES;
uint16_t headingLockFullCorrectionDegrees = DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES;
uint8_t headingLockNeutralReversePercent = DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT;
CommandSource headingLockSource = CommandSource::App;
bool headingLockLeftEscReversed = false;
bool headingLockRightEscReversed = false;
uint8_t activeVoicePowerLimitPercent = DEFAULT_VOICE_POWER_LIMIT_PERCENT;
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
bool gpsRmcStatusValid = false;
bool gpsUtcValid = false;
uint8_t gpsFixQuality = 0;
uint8_t gpsSatellites = 0;
uint32_t gpsUtcSeconds = 0;
float gpsLatitudeDegrees = 0.0f;
float gpsLongitudeDegrees = 0.0f;
float gpsSpeedKmh = 0.0f;
volatile uint32_t gpsPpsCount = 0;
volatile uint32_t gpsLastPpsMicros = 0;
const esp_partition_t* trackLogPartition = nullptr;
bool trackLogReady = false;
uint8_t trackLogSessionId = 0;
size_t trackLogCapacityRecords = 0;
size_t trackLogWriteIndex = 0;
size_t trackLogOldestIndex = 0;
uint32_t trackLogCount = 0;
uint32_t trackLogOldestSeq = 0;
uint32_t trackLogNewestSeq = 0;
uint32_t trackLogNextSeq = 1;
uint32_t trackLogLastAttemptMs = 0;
uint32_t trackLogLastCandidateMs = 0;
uint32_t trackLogLastConsideredUtc = 0;
uint32_t trackLogLastRecordedUtc = 0;
uint32_t trackLogDroppedInvalid = 0;
uint32_t trackLogDroppedDrift = 0;
uint32_t trackLogWriteErrors = 0;
uint32_t trackLogSanitizeCount = 0;
uint8_t trackLogStablePoints = 0;
bool trackLogNeedsRestabilize = true;
bool trackLogHasLastRecorded = false;
bool trackLogHasLastCandidate = false;
bool trackLogSanitizedThisBoot = false;
TrackLogRecord trackLogLastRecorded = {};
TrackLogRecord trackLogLastCandidate = {};

void forceNeutralAndDisarm();

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

int8_t clampVoicePercent(int value, uint8_t voicePowerLimitPercent = activeVoicePowerLimitPercent) {
  const int limit = constrain(
    static_cast<int>(voicePowerLimitPercent),
    static_cast<int>(MIN_VOICE_POWER_LIMIT_PERCENT),
    static_cast<int>(MAX_VOICE_POWER_LIMIT_PERCENT)
  );
  if (value > 0) {
    return static_cast<int8_t>(min(value, limit));
  }
  if (value < 0) {
    return static_cast<int8_t>(max(value, -limit));
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

int8_t clampHeadingLockBasePercent(int value, CommandSource source) {
  if (source == CommandSource::Voice) {
    return clampVoicePercent(value);
  }
  return static_cast<int8_t>(constrain(value, -100, 100));
}

void applyHeadingLockLimits(int8_t& leftPercent, int8_t& rightPercent) {
  const int8_t maxOutput = headingLockSource == CommandSource::Voice
    ? static_cast<int8_t>(activeVoicePowerLimitPercent)
    : APP_HEADING_LOCK_MAX_OUTPUT_PERCENT;
  leftPercent = static_cast<int8_t>(
    constrain(static_cast<int>(leftPercent), -maxOutput, maxOutput)
  );
  rightPercent = static_cast<int8_t>(
    constrain(static_cast<int>(rightPercent), -maxOutput, maxOutput)
  );
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

float normalizeCompass360(float degrees) {
  while (degrees >= 360.0f) {
    degrees -= 360.0f;
  }
  while (degrees < 0.0f) {
    degrees += 360.0f;
  }
  return degrees;
}

bool hasFreshPhoneHeading(uint32_t now) {
  return phoneHeadingEnabled && lastPhoneHeadingMs != 0 && now - lastPhoneHeadingMs <= PHONE_HEADING_TIMEOUT_MS;
}

bool hasUsableHeading(uint32_t now) {
  return hasFreshPhoneHeading(now) || (!phoneHeadingEnabled && imuAvailable);
}

const char* activeHeadingSourceName(uint32_t now) {
  if (hasFreshPhoneHeading(now)) {
    return "PHONE";
  }
  if (!phoneHeadingEnabled && magnetometerAvailable) {
    return "MAG";
  }
  return "NONE";
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

bool magWrite(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(AK09916_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

bool magRead(uint8_t reg, uint8_t* buffer, size_t length) {
  Wire.beginTransmission(AK09916_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  const size_t readBytes = Wire.requestFrom(static_cast<int>(AK09916_ADDR), static_cast<int>(length));
  if (readBytes != length) {
    return false;
  }
  for (size_t i = 0; i < length; ++i) {
    buffer[i] = Wire.read();
  }
  return true;
}

bool magReadByte(uint8_t reg, uint8_t& value) {
  return magRead(reg, &value, 1);
}

bool setupMagnetometer() {
  imuSelectBank(ICM20948_BANK_0);
  imuWrite(ICM20948_USER_CTRL, 0x00);
  imuWrite(ICM20948_INT_PIN_CFG, 0x02);
  delay(10);

  magWrite(AK09916_CNTL3, 0x01);
  delay(100);

  uint8_t whoAmI = 0;
  if (!magReadByte(AK09916_WIA2, whoAmI) || whoAmI != AK09916_WIA2_VALUE) {
    Serial.print("AK09916 not detected who=0x");
    Serial.println(whoAmI, HEX);
    return false;
  }

  if (!magWrite(AK09916_CNTL2, AK09916_MODE_CONTINUOUS_50HZ)) {
    Serial.println("AK09916 continuous mode setup failed");
    return false;
  }
  delay(10);
  return true;
}

bool isSaneMagScale(float value) {
  return isfinite(value) && value >= MAG_CAL_MIN_SCALE && value <= MAG_CAL_MAX_SCALE;
}

const char* magCalibrationStateName() {
  if (magCalibrationActive) {
    return "ACTIVE";
  }
  return magCalibrationValid ? "SAVED" : "NONE";
}

void loadMagCalibration() {
  magCalibrationValid = false;
  magOffsetX = 0.0f;
  magOffsetY = 0.0f;
  magScaleX = 1.0f;
  magScaleY = 1.0f;

  if (!nvsReady || !preferences.getBool(NVS_MAG_CAL_VALID_KEY, false)) {
    return;
  }

  const float storedOffsetX = preferences.getFloat(NVS_MAG_OFFSET_X_KEY, 0.0f);
  const float storedOffsetY = preferences.getFloat(NVS_MAG_OFFSET_Y_KEY, 0.0f);
  const float storedScaleX = preferences.getFloat(NVS_MAG_SCALE_X_KEY, 1.0f);
  const float storedScaleY = preferences.getFloat(NVS_MAG_SCALE_Y_KEY, 1.0f);
  if (!isfinite(storedOffsetX) || !isfinite(storedOffsetY) || !isSaneMagScale(storedScaleX) || !isSaneMagScale(storedScaleY)) {
    Serial.println("Stored magnetometer calibration invalid; ignoring NVS value");
    return;
  }

  magOffsetX = storedOffsetX;
  magOffsetY = storedOffsetY;
  magScaleX = storedScaleX;
  magScaleY = storedScaleY;
  magCalibrationValid = true;
}

void clearMagCalibrationParameters() {
  magCalibrationValid = false;
  magOffsetX = 0.0f;
  magOffsetY = 0.0f;
  magScaleX = 1.0f;
  magScaleY = 1.0f;
  if (nvsReady) {
    preferences.remove(NVS_MAG_CAL_VALID_KEY);
    preferences.remove(NVS_MAG_OFFSET_X_KEY);
    preferences.remove(NVS_MAG_OFFSET_Y_KEY);
    preferences.remove(NVS_MAG_SCALE_X_KEY);
    preferences.remove(NVS_MAG_SCALE_Y_KEY);
  }
}

void resetMagCalibrationSession() {
  magCalibrationMinX = 0;
  magCalibrationMaxX = 0;
  magCalibrationMinY = 0;
  magCalibrationMaxY = 0;
  magCalibrationSamples = 0;
}

void updateMagCalibrationSample(int16_t rawX, int16_t rawY) {
  if (!magCalibrationActive) {
    return;
  }

  if (magCalibrationSamples == 0) {
    magCalibrationMinX = rawX;
    magCalibrationMaxX = rawX;
    magCalibrationMinY = rawY;
    magCalibrationMaxY = rawY;
  } else {
    magCalibrationMinX = min<int32_t>(magCalibrationMinX, rawX);
    magCalibrationMaxX = max<int32_t>(magCalibrationMaxX, rawX);
    magCalibrationMinY = min<int32_t>(magCalibrationMinY, rawY);
    magCalibrationMaxY = max<int32_t>(magCalibrationMaxY, rawY);
  }

  if (magCalibrationSamples < UINT16_MAX) {
    magCalibrationSamples += 1;
  }
}

bool saveMagCalibrationFromSession() {
  const int32_t rangeX = magCalibrationMaxX - magCalibrationMinX;
  const int32_t rangeY = magCalibrationMaxY - magCalibrationMinY;
  if (
    magCalibrationSamples < MAG_CAL_MIN_SAMPLES ||
    rangeX < MAG_CAL_MIN_RANGE ||
    rangeY < MAG_CAL_MIN_RANGE
  ) {
    return false;
  }

  const float radiusX = static_cast<float>(rangeX) * 0.5f;
  const float radiusY = static_cast<float>(rangeY) * 0.5f;
  const float averageRadius = (radiusX + radiusY) * 0.5f;
  const float nextScaleX = averageRadius / radiusX;
  const float nextScaleY = averageRadius / radiusY;
  if (!isSaneMagScale(nextScaleX) || !isSaneMagScale(nextScaleY)) {
    return false;
  }

  magOffsetX = (static_cast<float>(magCalibrationMinX) + static_cast<float>(magCalibrationMaxX)) * 0.5f;
  magOffsetY = (static_cast<float>(magCalibrationMinY) + static_cast<float>(magCalibrationMaxY)) * 0.5f;
  magScaleX = nextScaleX;
  magScaleY = nextScaleY;
  magCalibrationValid = true;

  if (nvsReady) {
    preferences.putFloat(NVS_MAG_OFFSET_X_KEY, magOffsetX);
    preferences.putFloat(NVS_MAG_OFFSET_Y_KEY, magOffsetY);
    preferences.putFloat(NVS_MAG_SCALE_X_KEY, magScaleX);
    preferences.putFloat(NVS_MAG_SCALE_Y_KEY, magScaleY);
    preferences.putBool(NVS_MAG_CAL_VALID_KEY, true);
  }
  return true;
}

void printMagCalibrationStatus(Print& output) {
  const int32_t rangeX = magCalibrationSamples > 0 ? magCalibrationMaxX - magCalibrationMinX : 0;
  const int32_t rangeY = magCalibrationSamples > 0 ? magCalibrationMaxY - magCalibrationMinY : 0;

  output.print("STATUS;MCAL=");
  output.print(magCalibrationStateName());
  output.print(";MCNT=");
  output.print(magCalibrationSamples);
  output.print(";MRX=");
  output.print(rangeX);
  output.print(";MRY=");
  output.print(rangeY);
  output.print(";MOX=");
  output.print(magOffsetX, 1);
  output.print(";MOY=");
  output.print(magOffsetY, 1);
  output.print(";MSX=");
  output.print(magScaleX, 3);
  output.print(";MSY=");
  output.println(magScaleY, 3);
}

bool readMagHeadingDegrees(float& heading, bool& hasNewHeading) {
  hasNewHeading = false;

  uint8_t buffer[9] = {};
  if (!magRead(AK09916_ST1, buffer, sizeof(buffer))) {
    return false;
  }

  const uint8_t status1 = buffer[0];
  const uint8_t status2 = buffer[8];
  if ((status1 & 0x01) == 0) {
    return true;
  }
  if ((status2 & 0x08) != 0) {
    return false;
  }

  const int16_t rawX = static_cast<int16_t>((static_cast<uint16_t>(buffer[2]) << 8) | buffer[1]);
  const int16_t rawY = static_cast<int16_t>((static_cast<uint16_t>(buffer[4]) << 8) | buffer[3]);
  if (rawX == 0 && rawY == 0) {
    return true;
  }
  updateMagCalibrationSample(rawX, rawY);

  const float calibratedX = magCalibrationValid ? (static_cast<float>(rawX) - magOffsetX) * magScaleX : static_cast<float>(rawX);
  const float calibratedY = magCalibrationValid ? (static_cast<float>(rawY) - magOffsetY) * magScaleY : static_cast<float>(rawY);
  if (fabsf(calibratedX) < 0.001f && fabsf(calibratedY) < 0.001f) {
    return true;
  }

  heading = normalizeAngle180(
    atan2f(calibratedY, calibratedX) * RADIANS_TO_DEGREES +
      MAG_HEADING_OFFSET_DEGREES
  );
  hasNewHeading = true;
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

  magnetometerAvailable = setupMagnetometer();
  if (!magnetometerAvailable) {
    imuAvailable = false;
    Serial.println("ICM20948 detected, but AK09916 magnetometer unavailable; heading disabled");
    return;
  }
  loadMagCalibration();

  imuAvailable = true;
  imuFailureCount = 0;
  gyroZBiasDps = 0.0f;
  lastImuUpdateMs = millis();
  lastMagHeadingReadMs = 0;
  headingFilterInitialized = false;
  Serial.print("ICM20948 ready addr=0x");
  Serial.print(imuAddress, HEX);
  Serial.println(" heading_source=AK09916_MAG");
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

bool parseNmeaTime(const char* value, uint8_t& hour, uint8_t& minute, uint8_t& second) {
  if (value == nullptr || strlen(value) < 6) {
    return false;
  }
  for (uint8_t i = 0; i < 6; ++i) {
    if (value[i] < '0' || value[i] > '9') {
      return false;
    }
  }

  hour = static_cast<uint8_t>((value[0] - '0') * 10 + (value[1] - '0'));
  minute = static_cast<uint8_t>((value[2] - '0') * 10 + (value[3] - '0'));
  second = static_cast<uint8_t>((value[4] - '0') * 10 + (value[5] - '0'));
  return hour < 24 && minute < 60 && second < 60;
}

bool parseNmeaDate(const char* value, uint16_t& year, uint8_t& month, uint8_t& day) {
  if (value == nullptr || strlen(value) < 6) {
    return false;
  }
  for (uint8_t i = 0; i < 6; ++i) {
    if (value[i] < '0' || value[i] > '9') {
      return false;
    }
  }

  day = static_cast<uint8_t>((value[0] - '0') * 10 + (value[1] - '0'));
  month = static_cast<uint8_t>((value[2] - '0') * 10 + (value[3] - '0'));
  const uint8_t year2 = static_cast<uint8_t>((value[4] - '0') * 10 + (value[5] - '0'));
  year = static_cast<uint16_t>(year2 >= 80 ? 1900 + year2 : 2000 + year2);
  return year >= 2020 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
}

int32_t daysFromCivil(int32_t year, uint8_t month, uint8_t day) {
  year -= month <= 2;
  const int32_t era = (year >= 0 ? year : year - 399) / 400;
  const uint32_t yearOfEra = static_cast<uint32_t>(year - era * 400);
  const uint32_t monthPrime = month + (month > 2 ? -3 : 9);
  const uint32_t dayOfYear = (153 * monthPrime + 2) / 5 + day - 1;
  const uint32_t dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
  return era * 146097 + static_cast<int32_t>(dayOfEra) - 719468;
}

bool unixSecondsFromUtc(
  uint16_t year,
  uint8_t month,
  uint8_t day,
  uint8_t hour,
  uint8_t minute,
  uint8_t second,
  uint32_t& outSeconds
) {
  const int32_t days = daysFromCivil(year, month, day);
  if (days < 0) {
    return false;
  }
  const uint64_t seconds = static_cast<uint64_t>(days) * 86400ULL +
    static_cast<uint64_t>(hour) * 3600ULL +
    static_cast<uint64_t>(minute) * 60ULL +
    second;
  if (seconds > 0xFFFFFFFFULL) {
    return false;
  }
  outSeconds = static_cast<uint32_t>(seconds);
  return outSeconds >= TRACKLOG_MIN_VALID_UTC;
}

int32_t degreesToE7(float degrees) {
  return static_cast<int32_t>(lroundf(degrees * 10000000.0f));
}

bool isValidLatLonE7(int32_t latE7, int32_t lonE7) {
  if (latE7 == 0 && lonE7 == 0) {
    return false;
  }
  return latE7 >= -900000000 &&
    latE7 <= 900000000 &&
    lonE7 >= -1800000000 &&
    lonE7 <= 1800000000;
}

float distanceMetersE7(int32_t latAE7, int32_t lonAE7, int32_t latBE7, int32_t lonBE7) {
  constexpr float earthRadiusMeters = 6371000.0f;
  constexpr float e7ToRadians = DEG_TO_RAD / 10000000.0f;
  const float latA = static_cast<float>(latAE7) * e7ToRadians;
  const float latB = static_cast<float>(latBE7) * e7ToRadians;
  const float dLat = static_cast<float>(latBE7 - latAE7) * e7ToRadians;
  const float dLon = static_cast<float>(lonBE7 - lonAE7) * e7ToRadians;
  const float sinDLat = sinf(dLat * 0.5f);
  const float sinDLon = sinf(dLon * 0.5f);
  const float a = sinDLat * sinDLat + cosf(latA) * cosf(latB) * sinDLon * sinDLon;
  const float c = 2.0f * atan2f(sqrtf(a), sqrtf(max(0.0f, 1.0f - a)));
  return earthRadiusMeters * c;
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
  gpsFixValid = gpsRmcStatusValid && gpsFixQuality > 0;

  if (gpsFixValid && fields[2][0] != '\0' && fields[4][0] != '\0') {
    gpsLatitudeDegrees = nmeaCoordinateToDegrees(fields[2], fields[3][0], 2);
    gpsLongitudeDegrees = nmeaCoordinateToDegrees(fields[4], fields[5][0], 3);
  }
}

void parseGpsRmc(char* line) {
  const uint8_t maxFields = 10;
  char* fields[maxFields] = {};
  const uint8_t fieldCount = splitNmeaFields(line, fields, maxFields);

  if (fieldCount >= 3 && fields[2][0] != '\0') {
    gpsRmcStatusValid = fields[2][0] == 'A';
    gpsFixValid = gpsRmcStatusValid && gpsFixQuality > 0;
    if (!gpsFixValid) {
      gpsSpeedKmh = 0.0f;
    }
  }
  if (gpsFixValid && fieldCount >= 7 && fields[3][0] != '\0' && fields[5][0] != '\0') {
    gpsLatitudeDegrees = nmeaCoordinateToDegrees(fields[3], fields[4][0], 2);
    gpsLongitudeDegrees = nmeaCoordinateToDegrees(fields[5], fields[6][0], 3);
  }
  if (gpsFixValid && fieldCount >= 8 && fields[7][0] != '\0') {
    const float speedKnots = static_cast<float>(atof(fields[7]));
    gpsSpeedKmh = speedKnots > 0.0f ? speedKnots * 1.852f : 0.0f;
  }
  if (fieldCount >= 10) {
    uint8_t hour = 0;
    uint8_t minute = 0;
    uint8_t second = 0;
    uint16_t year = 0;
    uint8_t month = 0;
    uint8_t day = 0;
    uint32_t nextUtcSeconds = 0;
    if (
      parseNmeaTime(fields[1], hour, minute, second) &&
      parseNmeaDate(fields[9], year, month, day) &&
      unixSecondsFromUtc(year, month, day, hour, minute, second, nextUtcSeconds)
    ) {
      gpsUtcSeconds = nextUtcSeconds;
      gpsUtcValid = true;
    }
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

uint32_t unpackTrackSeq24(const TrackLogRecord& record) {
  return static_cast<uint32_t>(record.seq24[0]) |
    (static_cast<uint32_t>(record.seq24[1]) << 8) |
    (static_cast<uint32_t>(record.seq24[2]) << 16);
}

void packTrackSeq24(TrackLogRecord& record, uint32_t seq) {
  record.seq24[0] = static_cast<uint8_t>(seq & 0xFF);
  record.seq24[1] = static_cast<uint8_t>((seq >> 8) & 0xFF);
  record.seq24[2] = static_cast<uint8_t>((seq >> 16) & 0xFF);
}

bool isValidTrackRecord(const TrackLogRecord& record) {
  const uint32_t seq = unpackTrackSeq24(record);
  return seq != 0 &&
    seq != TRACKLOG_INVALID_SEQ24 &&
    record.utcSeconds >= TRACKLOG_MIN_VALID_UTC &&
    isValidLatLonE7(record.latE7, record.lonE7);
}

size_t trackLogOffsetForIndex(size_t index) {
  return index * TRACKLOG_RECORD_SIZE;
}

bool readTrackLogRecord(size_t index, TrackLogRecord& record) {
  if (!trackLogReady || index >= trackLogCapacityRecords) {
    return false;
  }
  return esp_partition_read(
    trackLogPartition,
    trackLogOffsetForIndex(index),
    &record,
    sizeof(record)
  ) == ESP_OK;
}

void resetTrackLogState() {
  trackLogCount = 0;
  trackLogOldestSeq = 0;
  trackLogNewestSeq = 0;
  trackLogWriteIndex = 0;
  trackLogOldestIndex = 0;
  trackLogNextSeq = 1;
  trackLogLastConsideredUtc = 0;
  trackLogLastRecordedUtc = 0;
  trackLogStablePoints = 0;
  trackLogNeedsRestabilize = true;
  trackLogHasLastRecorded = false;
  trackLogHasLastCandidate = false;
  trackLogLastRecorded = {};
  trackLogLastCandidate = {};
}

bool eraseTrackLogPartition(const char* reason) {
  if (!trackLogReady || trackLogPartition == nullptr) {
    return false;
  }

  Serial.print("GPS tracklog erase all reason=");
  Serial.println(reason);
  for (size_t offset = 0; offset < trackLogPartition->size; offset += TRACKLOG_FLASH_SECTOR_SIZE) {
    const esp_err_t eraseResult = esp_partition_erase_range(
      trackLogPartition,
      offset,
      TRACKLOG_FLASH_SECTOR_SIZE
    );
    if (eraseResult != ESP_OK) {
      trackLogWriteErrors += 1;
      Serial.print("GPS tracklog full erase failed offset=");
      Serial.print(offset);
      Serial.print(" err=");
      Serial.println(static_cast<int>(eraseResult));
      return false;
    }
  }

  trackLogSanitizeCount += 1;
  trackLogSanitizedThisBoot = true;
  resetTrackLogState();
  return true;
}

bool trackLogScanLooksSuspicious(uint32_t minSeq, uint32_t maxSeq, uint32_t count) {
  if (count == 0) {
    return false;
  }
  if (maxSeq < minSeq) {
    return true;
  }

  const uint32_t span = maxSeq - minSeq + 1;
  const uint32_t recordsPerSector = TRACKLOG_FLASH_SECTOR_SIZE / TRACKLOG_RECORD_SIZE;
  return span > count + recordsPerSector;
}

void advanceTrackLogSessionId() {
  if (!nvsReady) {
    trackLogSessionId = 1;
    return;
  }

  uint8_t nextSessionId = preferences.getUChar(NVS_TRACK_SESSION_KEY, 0);
  nextSessionId = static_cast<uint8_t>(nextSessionId + 1);
  if (nextSessionId == 0xFF) {
    nextSessionId = 1;
  }
  preferences.putUChar(NVS_TRACK_SESSION_KEY, nextSessionId);
  trackLogSessionId = nextSessionId;
}

void scanTrackLogPartition() {
  resetTrackLogState();

  uint32_t minSeq = TRACKLOG_INVALID_SEQ24;
  uint32_t maxSeq = 0;
  size_t minSeqIndex = 0;
  size_t maxSeqIndex = 0;
  TrackLogRecord record = {};

  for (size_t index = 0; index < trackLogCapacityRecords; ++index) {
    if (!readTrackLogRecord(index, record) || !isValidTrackRecord(record)) {
      continue;
    }

    const uint32_t seq = unpackTrackSeq24(record);
    trackLogCount += 1;
    if (seq < minSeq) {
      minSeq = seq;
      minSeqIndex = index;
    }
    if (seq > maxSeq) {
      maxSeq = seq;
      maxSeqIndex = index;
      trackLogLastRecorded = record;
    }
  }

  if (trackLogCount == 0) {
    trackLogHasLastRecorded = false;
    return;
  }

  if (trackLogScanLooksSuspicious(minSeq, maxSeq, trackLogCount)) {
    Serial.print("GPS tracklog suspicious scan count=");
    Serial.print(trackLogCount);
    Serial.print(" min=");
    Serial.print(minSeq);
    Serial.print(" max=");
    Serial.println(maxSeq);
    eraseTrackLogPartition("scan_seq_gap");
    return;
  }

  trackLogOldestSeq = minSeq;
  trackLogNewestSeq = maxSeq;
  trackLogOldestIndex = minSeqIndex;
  trackLogWriteIndex = (maxSeqIndex + 1) % trackLogCapacityRecords;
  trackLogNextSeq = maxSeq >= TRACKLOG_MAX_SEQ24 ? 1 : maxSeq + 1;
  trackLogLastRecordedUtc = trackLogLastRecorded.utcSeconds;
  trackLogHasLastRecorded = true;
}

void setupTrackLog() {
  advanceTrackLogSessionId();
  trackLogPartition = esp_partition_find_first(
    ESP_PARTITION_TYPE_DATA,
    static_cast<esp_partition_subtype_t>(TRACKLOG_PARTITION_SUBTYPE),
    TRACKLOG_PARTITION_LABEL
  );
  if (trackLogPartition == nullptr) {
    trackLogReady = false;
    Serial.println("GPS tracklog partition not found");
    return;
  }

  trackLogCapacityRecords = trackLogPartition->size / TRACKLOG_RECORD_SIZE;
  if (
    trackLogCapacityRecords == 0 ||
    trackLogPartition->size % TRACKLOG_FLASH_SECTOR_SIZE != 0 ||
    TRACKLOG_FLASH_SECTOR_SIZE % TRACKLOG_RECORD_SIZE != 0
  ) {
    trackLogReady = false;
    Serial.println("GPS tracklog partition geometry invalid");
    return;
  }

  trackLogReady = true;
  scanTrackLogPartition();
  Serial.print("GPS tracklog ready cap=");
  Serial.print(trackLogCapacityRecords);
  Serial.print(" count=");
  Serial.print(trackLogCount);
  Serial.print(" oldest=");
  Serial.print(trackLogOldestSeq);
  Serial.print(" newest=");
  Serial.print(trackLogNewestSeq);
  Serial.print(" session=");
  Serial.println(trackLogSessionId);
}

bool buildCurrentTrackRecord(TrackLogRecord& record) {
  if (!gpsFixValid || !gpsUtcValid || gpsSatellites < TRACKLOG_MIN_SATELLITES) {
    return false;
  }

  const int32_t latE7 = degreesToE7(gpsLatitudeDegrees);
  const int32_t lonE7 = degreesToE7(gpsLongitudeDegrees);
  if (!isValidLatLonE7(latE7, lonE7)) {
    return false;
  }

  record.utcSeconds = gpsUtcSeconds;
  record.latE7 = latE7;
  record.lonE7 = lonE7;
  record.sessionId = trackLogSessionId;
  packTrackSeq24(record, trackLogNextSeq);
  return true;
}

bool isReasonableTrackStep(const TrackLogRecord& previous, const TrackLogRecord& next) {
  if (next.utcSeconds <= previous.utcSeconds) {
    return false;
  }

  const uint32_t dt = next.utcSeconds - previous.utcSeconds;
  if (dt == 0) {
    return false;
  }
  if (dt > TRACKLOG_NO_FIX_RESET_MS / 1000UL) {
    return true;
  }

  const float distanceMeters = distanceMetersE7(previous.latE7, previous.lonE7, next.latE7, next.lonE7);
  const float speedMps = distanceMeters / static_cast<float>(dt);
  return speedMps <= TRACKLOG_MAX_SPEED_MPS;
}

void noteTrackLogInvalid(uint32_t now) {
  trackLogDroppedInvalid += 1;
  if (
    trackLogLastCandidateMs != 0 &&
    now - trackLogLastCandidateMs > TRACKLOG_NO_FIX_RESET_MS
  ) {
    trackLogNeedsRestabilize = true;
    trackLogStablePoints = 0;
    trackLogHasLastCandidate = false;
  }
}

bool writeTrackLogRecord(const TrackLogRecord& record) {
  if (!trackLogReady || trackLogWriteIndex >= trackLogCapacityRecords) {
    return false;
  }

  const size_t offset = trackLogOffsetForIndex(trackLogWriteIndex);
  if (offset % TRACKLOG_FLASH_SECTOR_SIZE == 0) {
    if (trackLogCount == trackLogCapacityRecords) {
      const size_t recordsPerSector = TRACKLOG_FLASH_SECTOR_SIZE / TRACKLOG_RECORD_SIZE;
      trackLogCount -= recordsPerSector;
      trackLogOldestSeq += recordsPerSector;
      trackLogOldestIndex = (trackLogWriteIndex + recordsPerSector) % trackLogCapacityRecords;
    }
    const esp_err_t eraseResult = esp_partition_erase_range(
      trackLogPartition,
      offset,
      TRACKLOG_FLASH_SECTOR_SIZE
    );
    if (eraseResult != ESP_OK) {
      trackLogWriteErrors += 1;
      Serial.print("GPS tracklog erase failed err=");
      Serial.println(static_cast<int>(eraseResult));
      return false;
    }
  }

  const esp_err_t writeResult = esp_partition_write(trackLogPartition, offset, &record, sizeof(record));
  if (writeResult != ESP_OK) {
    trackLogWriteErrors += 1;
    Serial.print("GPS tracklog write failed err=");
    Serial.println(static_cast<int>(writeResult));
    return false;
  }

  const uint32_t seq = unpackTrackSeq24(record);
  if (trackLogCount < trackLogCapacityRecords) {
    trackLogCount += 1;
  }
  if (trackLogCount == 1 || trackLogOldestSeq == 0) {
    trackLogOldestSeq = seq;
    trackLogOldestIndex = trackLogWriteIndex;
  }
  trackLogNewestSeq = seq;
  trackLogLastRecorded = record;
  trackLogLastRecordedUtc = record.utcSeconds;
  trackLogHasLastRecorded = true;
  trackLogWriteIndex = (trackLogWriteIndex + 1) % trackLogCapacityRecords;
  trackLogNextSeq = seq >= TRACKLOG_MAX_SEQ24 ? 1 : seq + 1;
  return true;
}

void updateTrackLog(uint32_t now) {
  if (!trackLogReady || now - trackLogLastAttemptMs < TRACKLOG_INTERVAL_MS / 2) {
    return;
  }
  trackLogLastAttemptMs = now;

  TrackLogRecord candidate = {};
  if (!buildCurrentTrackRecord(candidate)) {
    noteTrackLogInvalid(now);
    return;
  }

  if (candidate.utcSeconds == trackLogLastConsideredUtc) {
    return;
  }
  trackLogLastConsideredUtc = candidate.utcSeconds;

  if (
    trackLogHasLastRecorded &&
    trackLogLastRecordedUtc > candidate.utcSeconds &&
    trackLogLastRecordedUtc - candidate.utcSeconds > TRACKLOG_STALE_FUTURE_SECONDS
  ) {
    eraseTrackLogPartition("stale_future_record");
  }

  if (trackLogHasLastRecorded && candidate.utcSeconds <= trackLogLastRecordedUtc) {
    noteTrackLogInvalid(now);
    return;
  }

  if (
    trackLogHasLastRecorded &&
    !trackLogNeedsRestabilize &&
    !isReasonableTrackStep(trackLogLastRecorded, candidate)
  ) {
    trackLogDroppedDrift += 1;
    trackLogNeedsRestabilize = true;
    trackLogStablePoints = 0;
    trackLogHasLastCandidate = false;
    return;
  }

  if (trackLogNeedsRestabilize) {
    if (
      trackLogHasLastCandidate &&
      !isReasonableTrackStep(trackLogLastCandidate, candidate)
    ) {
      trackLogDroppedDrift += 1;
      trackLogStablePoints = 1;
      trackLogLastCandidate = candidate;
      trackLogLastCandidateMs = now;
      return;
    }

    trackLogStablePoints = min<uint8_t>(
      static_cast<uint8_t>(trackLogStablePoints + 1),
      TRACKLOG_STABLE_POINTS_REQUIRED
    );
    trackLogLastCandidate = candidate;
    trackLogHasLastCandidate = true;
    trackLogLastCandidateMs = now;
    if (trackLogStablePoints < TRACKLOG_STABLE_POINTS_REQUIRED) {
      return;
    }
    trackLogNeedsRestabilize = false;
  }

  if (writeTrackLogRecord(candidate)) {
    trackLogLastCandidate = candidate;
    trackLogHasLastCandidate = true;
    trackLogLastCandidateMs = now;
  }
}

void updateImu(uint32_t now) {
  if (phoneHeadingEnabled) {
    if (!hasFreshPhoneHeading(now) && (turnControlActive || headingLockActive)) {
      forceNeutralAndDisarm();
      Serial.println("Phone heading timeout; autonomous control disabled");
      SerialBT.println("STATUS;ARMED=0;FAULT=PHONE_HEADING_TIMEOUT");
    }
    return;
  }
  if (!imuAvailable) {
    return;
  }
  if (!magnetometerAvailable || now - lastMagHeadingReadMs < MAG_HEADING_INTERVAL_MS) {
    return;
  }
  lastMagHeadingReadMs = now;

  float nextHeadingDegrees = headingDegrees;
  bool hasNewHeading = false;
  if (!readMagHeadingDegrees(nextHeadingDegrees, hasNewHeading)) {
    imuFailureCount += 1;
    if (imuFailureCount >= IMU_FAILURE_LIMIT) {
      imuAvailable = false;
      magnetometerAvailable = false;
      turnControlActive = false;
      headingLockActive = false;
      requestedLeftPercent = 0;
      requestedRightPercent = 0;
      Serial.println("IMU magnetometer read failed; angle turn and heading lock disabled");
      SerialBT.println("STATUS;FAULT=IMU_READ_FAILED");
    }
    return;
  }

  imuFailureCount = 0;
  if (hasNewHeading) {
    if (!headingFilterInitialized) {
      headingDegrees = nextHeadingDegrees;
      headingFilterInitialized = true;
    } else {
      const float deltaDegrees = shortestAngleError(nextHeadingDegrees, headingDegrees);
      headingDegrees = normalizeAngle180(headingDegrees + deltaDegrees * MAG_HEADING_FILTER_ALPHA);
    }
  }
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
  lastCommandSource = CommandSource::App;
  lastCommandMode = CommandMode::Throttle;
  turnControlActive = false;
  turnControlCompleted = false;
  headingLockActive = false;
  lastHeadingLockCorrectionPercent = 0;
  leftPulseUs = ESC_NEUTRAL_US;
  rightPulseUs = ESC_NEUTRAL_US;
  writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);
}

bool applyMagCalibrationLine(char* line, Print& response) {
  if (strncmp(line, "MAG_CAL", 7) != 0) {
    return false;
  }

  bool sawAction = false;
  bool startRequested = false;
  bool saveRequested = false;
  bool clearRequested = false;
  bool statusRequested = false;
  bool badToken = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "MAG_CAL") == 0) {
      // Header marker.
    } else if (strcmp(token, "ACTION=START") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      startRequested = true;
    } else if (strcmp(token, "ACTION=SAVE") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      saveRequested = true;
    } else if (strcmp(token, "ACTION=CLEAR") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      clearRequested = true;
    } else if (strcmp(token, "ACTION=STATUS") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      statusRequested = true;
    } else if (strncmp(token, "ACTION=", 7) == 0) {
      badToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawAction || badToken) {
    response.println("ERR;BAD_MAG_CAL_COMMAND");
    return true;
  }

  if (statusRequested) {
    printMagCalibrationStatus(response);
    return true;
  }

  forceNeutralAndDisarm();
  lastValidBtCommandMs = 0;

  if (startRequested) {
    if (!magnetometerAvailable) {
      response.println("ERR;MAG_UNAVAILABLE");
      return true;
    }
    resetMagCalibrationSession();
    magCalibrationActive = true;
    headingFilterInitialized = false;
    response.println("STATUS;ARMED=0;L=0;R=0;HLOCK=OFF");
    printMagCalibrationStatus(response);
    Serial.println("Magnetometer calibration started; state=DISARMED");
    return true;
  }

  if (saveRequested) {
    if (!magCalibrationActive) {
      response.println("ERR;MAG_CAL_NOT_ACTIVE");
      printMagCalibrationStatus(response);
      return true;
    }
    const bool saved = saveMagCalibrationFromSession();
    magCalibrationActive = false;
    headingFilterInitialized = false;
    if (!saved) {
      response.println("ERR;MAG_CAL_INSUFFICIENT");
    }
    printMagCalibrationStatus(response);
    Serial.println(saved ? "Magnetometer calibration saved; state=DISARMED" : "Magnetometer calibration rejected; insufficient sample coverage");
    return true;
  }

  if (clearRequested) {
    magCalibrationActive = false;
    resetMagCalibrationSession();
    clearMagCalibrationParameters();
    headingFilterInitialized = false;
    response.println("STATUS;ARMED=0;L=0;R=0;HLOCK=OFF");
    printMagCalibrationStatus(response);
    Serial.println("Magnetometer calibration cleared; state=DISARMED");
    return true;
  }

  return true;
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

bool parseHeadingSourceToken(const char* token, bool& outUsePhoneHeading) {
  if (strcmp(token, "H_SRC=PHONE") == 0) {
    outUsePhoneHeading = true;
    return true;
  }
  if (strcmp(token, "H_SRC=IMU") == 0) {
    outUsePhoneHeading = false;
    return true;
  }
  return false;
}

bool parseHeadingDegreesToken(const char* token, const char* prefix, float& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const float parsed = strtof(token + prefixLen, &end);
  if (end == token + prefixLen || *end != '\0' || !isfinite(parsed) || parsed < -180.0f || parsed > 360.0f) {
    return false;
  }

  outValue = normalizeCompass360(parsed);
  return true;
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

bool parseSignedToken(const char* token, const char* prefix, int16_t minValue, int16_t maxValue, int16_t& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const long parsed = strtol(token + prefixLen, &end, 10);
  if (end == token + prefixLen || *end != '\0' || parsed < minValue || parsed > maxValue) {
    return false;
  }

  outValue = static_cast<int16_t>(parsed);
  return true;
}

bool parseUint32Token(const char* token, const char* prefix, uint32_t minValue, uint32_t maxValue, uint32_t& outValue) {
  const size_t prefixLen = strlen(prefix);
  if (strncmp(token, prefix, prefixLen) != 0) {
    return false;
  }

  char* end = nullptr;
  const unsigned long parsed = strtoul(token + prefixLen, &end, 10);
  if (end == token + prefixLen || *end != '\0' || parsed < minValue || parsed > maxValue) {
    return false;
  }

  outValue = static_cast<uint32_t>(parsed);
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
    const int availableBytes = SerialBT.available();
    if (availableBytes <= 0) {
      break;
    }
    const size_t remaining = otaExpectedBytes - otaWrittenBytes;
    const size_t toRead = min(
      remaining,
      min(sizeof(buffer), static_cast<size_t>(availableBytes))
    );
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
  uint8_t voicePowerLimitPercent,
  bool leftEscReversed,
  bool rightEscReversed
) {
  const uint32_t now = millis();
  if (!hasUsableHeading(now)) {
    SerialBT.println("ERR;HEADING_UNAVAILABLE");
    Serial.println("Turn angle rejected: heading unavailable");
    return;
  }

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
  activeVoicePowerLimitPercent = constrain(
    static_cast<int>(voicePowerLimitPercent),
    static_cast<int>(MIN_VOICE_POWER_LIMIT_PERCENT),
    static_cast<int>(MAX_VOICE_POWER_LIMIT_PERCENT)
  );
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

  if (!hasUsableHeading(now)) {
    forceNeutralAndDisarm();
    SerialBT.println("STATUS;ARMED=0;FAULT=HEADING_UNAVAILABLE");
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
  uint16_t toleranceDegrees,
  uint16_t fullCorrectionDegrees,
  uint8_t neutralReversePercent,
  uint8_t voicePowerLimitPercent,
  uint16_t requestId,
  bool hasTargetOffset,
  int16_t targetOffsetDegrees,
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

  if (!hasUsableHeading(now)) {
    SerialBT.println("ERR;HEADING_UNAVAILABLE");
    Serial.println("Heading lock rejected: heading unavailable");
    return;
  }

  lastCommandMode = CommandMode::HeadingLock;
  turnControlActive = false;
  headingLockSource = source;
  activeVoicePowerLimitPercent = constrain(
    static_cast<int>(voicePowerLimitPercent),
    static_cast<int>(MIN_VOICE_POWER_LIMIT_PERCENT),
    static_cast<int>(MAX_VOICE_POWER_LIMIT_PERCENT)
  );
  headingLockBasePercent = clampHeadingLockBasePercent(basePercent, source);
  headingLockToleranceDegrees = toleranceDegrees;
  headingLockFullCorrectionDegrees = fullCorrectionDegrees;
  headingLockNeutralReversePercent = neutralReversePercent;
  headingLockLeftEscReversed = leftEscReversed;
  headingLockRightEscReversed = rightEscReversed;

  if (headingLockActive && requestId == activeHeadingLockRequestId) {
    return;
  }

  const bool duplicateConsumedOffset =
    hasTargetOffset &&
    headingLockOffsetRequestConsumed &&
    requestId == consumedHeadingLockOffsetRequestId;
  const bool shouldApplyTargetOffset = hasTargetOffset && !duplicateConsumedOffset;

  activeHeadingLockRequestId = requestId;
  headingLockTargetDegrees = shouldApplyTargetOffset
    ? normalizeAngle180(headingDegrees + static_cast<float>(targetOffsetDegrees))
    : headingDegrees;
  if (shouldApplyTargetOffset) {
    headingLockOffsetRequestConsumed = true;
    consumedHeadingLockOffsetRequestId = requestId;
  }
  lastHeadingLockErrorDegrees = 0.0f;
  lastHeadingLockCorrectionPercent = 0;
  headingLockActive = true;

  Serial.print("Heading lock start hid=");
  Serial.print(requestId);
  Serial.print(" base=");
  Serial.print(headingLockBasePercent);
  Serial.print(" target=");
  Serial.print(headingLockTargetDegrees, 1);
  if (shouldApplyTargetOffset) {
    Serial.print(" offset=");
    Serial.print(targetOffsetDegrees);
  } else if (duplicateConsumedOffset) {
    Serial.print(" stale_offset_ignored=");
    Serial.print(targetOffsetDegrees);
  }
  Serial.print(" tolerance=");
  Serial.print(headingLockToleranceDegrees);
  Serial.print(" full=");
  Serial.print(headingLockFullCorrectionDegrees);
  Serial.print(" neutral_reverse=");
  Serial.println(headingLockNeutralReversePercent);
  SerialBT.print("STATUS;HLOCK=START;HID=");
  SerialBT.print(requestId);
  SerialBT.print(";TARGET=");
  SerialBT.print(headingLockTargetDegrees, 1);
  if (shouldApplyTargetOffset) {
    SerialBT.print(";HOFF=");
    SerialBT.print(targetOffsetDegrees);
  } else if (duplicateConsumedOffset) {
    SerialBT.print(";HOFF_IGNORED=");
    SerialBT.print(targetOffsetDegrees);
  }
  SerialBT.print(";HTOL=");
  SerialBT.print(headingLockToleranceDegrees);
  SerialBT.print(";HFULL=");
  SerialBT.print(headingLockFullCorrectionDegrees);
  SerialBT.print(";HREV=");
  SerialBT.println(headingLockNeutralReversePercent);
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

  if (!hasUsableHeading(now)) {
    forceNeutralAndDisarm();
    SerialBT.println("STATUS;ARMED=0;FAULT=HEADING_UNAVAILABLE");
    return;
  }

  const float errorDegrees = shortestAngleError(headingLockTargetDegrees, headingDegrees);
  lastHeadingLockErrorDegrees = errorDegrees;
  const float absError = fabs(errorDegrees);

  int8_t correction = 0;
  const float toleranceDegrees = static_cast<float>(headingLockToleranceDegrees);
  const float fullCorrectionDegrees = max(
    static_cast<float>(headingLockFullCorrectionDegrees),
    toleranceDegrees + 1.0f
  );
  if (absError > toleranceDegrees) {
    const float activeError = min(absError, fullCorrectionDegrees) - toleranceDegrees;
    const float activeRange = fullCorrectionDegrees - toleranceDegrees;
    const float ratio = activeRange > 0.0f ? activeError / activeRange : 1.0f;
    const int correctionPercent = HEADING_LOCK_MIN_CORRECTION_PERCENT +
      static_cast<int>(roundf((HEADING_LOCK_MAX_CORRECTION_PERCENT - HEADING_LOCK_MIN_CORRECTION_PERCENT) * ratio));
    correction = static_cast<int8_t>(
      constrain(correctionPercent, static_cast<int>(HEADING_LOCK_MIN_CORRECTION_PERCENT), static_cast<int>(HEADING_LOCK_MAX_CORRECTION_PERCENT))
    );
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
  } else {
    const int neutralReverseLimit = static_cast<int>(headingLockNeutralReversePercent);
    rawLeft = max(-neutralReverseLimit, rawLeft);
    rawRight = max(-neutralReverseLimit, rawRight);
  }

  int8_t nextLeft = static_cast<int8_t>(constrain(rawLeft, -100, 100));
  int8_t nextRight = static_cast<int8_t>(constrain(rawRight, -100, 100));
  nextLeft = applyTurnEscDirection(nextLeft, headingLockLeftEscReversed);
  nextRight = applyTurnEscDirection(nextRight, headingLockRightEscReversed);
  applyHeadingLockLimits(nextLeft, nextRight);

  requestedLeftPercent = nextLeft;
  requestedRightPercent = nextRight;
}

void printTrackLogInfo(Print& output) {
  if (!trackLogReady) {
    output.println("LOG_INFO;ERR=NO_TRACKLOG");
    return;
  }

  output.print("LOG_INFO;REC=");
  output.print(TRACKLOG_RECORD_SIZE);
  output.print(";CAP=");
  output.print(trackLogCapacityRecords);
  output.print(";COUNT=");
  output.print(trackLogCount);
  output.print(";OLDEST=");
  output.print(trackLogOldestSeq);
  output.print(";NEWEST=");
  output.print(trackLogNewestSeq);
  output.print(";SESSION=");
  output.print(trackLogSessionId);
  output.print(";RATE=1;DROP_INVALID=");
  output.print(trackLogDroppedInvalid);
  output.print(";DROP_DRIFT=");
  output.print(trackLogDroppedDrift);
  output.print(";WRITE_ERR=");
  output.print(trackLogWriteErrors);
  output.print(";SANITIZED=");
  output.print(trackLogSanitizedThisBoot ? 1 : 0);
  output.print(";SAN_COUNT=");
  output.println(trackLogSanitizeCount);
}

uint16_t countTrackLogReadRecords(uint32_t fromSeq, uint16_t limit) {
  if (!trackLogReady || trackLogCount == 0) {
    return 0;
  }

  uint16_t count = 0;
  TrackLogRecord record = {};
  for (size_t offset = 0; offset < trackLogCapacityRecords && count < limit; ++offset) {
    const size_t index = (trackLogOldestIndex + offset) % trackLogCapacityRecords;
    if (!readTrackLogRecord(index, record) || !isValidTrackRecord(record)) {
      continue;
    }
    if (unpackTrackSeq24(record) >= fromSeq) {
      count += 1;
    }
  }
  return count;
}

void printTrackLogRead(Print& output, uint32_t fromSeq, uint16_t limit) {
  if (!trackLogReady) {
    output.println("LOG_BEGIN;ERR=NO_TRACKLOG");
    output.println("LOG_END;NEXT=0");
    return;
  }

  const uint16_t count = countTrackLogReadRecords(fromSeq, limit);
  output.print("LOG_BEGIN;FROM=");
  output.print(fromSeq);
  output.print(";COUNT=");
  output.println(count);

  uint16_t emitted = 0;
  uint32_t nextSeq = fromSeq;
  TrackLogRecord record = {};
  for (size_t offset = 0; offset < trackLogCapacityRecords && emitted < count; ++offset) {
    const size_t index = (trackLogOldestIndex + offset) % trackLogCapacityRecords;
    if (!readTrackLogRecord(index, record) || !isValidTrackRecord(record)) {
      continue;
    }

    const uint32_t seq = unpackTrackSeq24(record);
    if (seq < fromSeq) {
      continue;
    }

    output.print("LOG_POINT;SEQ=");
    output.print(seq);
    output.print(";SID=");
    output.print(record.sessionId);
    output.print(";T=");
    output.print(record.utcSeconds);
    output.print(";LAT=");
    output.print(record.latE7);
    output.print(";LON=");
    output.println(record.lonE7);
    emitted += 1;
    nextSeq = seq >= TRACKLOG_MAX_SEQ24 ? 1 : seq + 1;
  }

  if (emitted == 0) {
    nextSeq = trackLogNewestSeq >= TRACKLOG_MAX_SEQ24 ? 1 : trackLogNewestSeq + 1;
  }
  output.print("LOG_END;NEXT=");
  output.println(nextSeq);
}

bool applyTrackLogLine(char* line, Print& response) {
  if (strcmp(line, "LOG_INFO") == 0) {
    printTrackLogInfo(response);
    return true;
  }

  if (strncmp(line, "LOG_READ", 8) != 0) {
    return false;
  }

  uint32_t fromSeq = 1;
  uint16_t limit = 32;
  bool sawFrom = false;
  bool badToken = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "LOG_READ") == 0) {
      // Header marker.
    } else if (parseUint32Token(token, "FROM=", 1, TRACKLOG_MAX_SEQ24, fromSeq)) {
      sawFrom = true;
    } else if (parseUnsignedToken(token, "LIMIT=", 1, 64, limit)) {
      // Optional read size.
    } else {
      badToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawFrom || badToken) {
    response.println("LOG_BEGIN;ERR=BAD_READ");
    response.println("LOG_END;NEXT=0");
    return true;
  }

  printTrackLogRead(response, fromSeq, limit);
  return true;
}

void applyBluetoothLine(char* line) {
  if (strncmp(line, "OTA_BEGIN", 9) == 0) {
    beginOta(line);
    return;
  }
  if (applyIdentityLine(line, SerialBT)) {
    return;
  }
  if (applyMagCalibrationLine(line, SerialBT)) {
    return;
  }
  if (applyTrackLogLine(line, SerialBT)) {
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
  bool sawHeadingLockTolerance = false;
  bool sawHeadingLockFullCorrection = false;
  bool sawHeadingLockNeutralReverse = false;
  bool sawHeadingLockTargetOffset = false;
  bool sawVoicePowerLimit = false;
  bool sawHeadingSource = false;
  bool sawPhoneHeading = false;
  bool badSource = false;
  bool badMode = false;
  bool badTurnToken = false;
  bool badHeadingLockToken = false;
  bool badVoiceLimitToken = false;
  bool badHeadingSourceToken = false;
  bool nextArmed = false;
  int8_t nextLeftPercent = 0;
  int8_t nextRightPercent = 0;
  int8_t nextHeadingLockBasePercent = 0;
  uint16_t nextVoicePowerLimitPercent = DEFAULT_VOICE_POWER_LIMIT_PERCENT;
  CommandSource nextSource = CommandSource::App;
  CommandMode nextMode = CommandMode::Throttle;
  TurnDirection nextDirection = TurnDirection::Left;
  uint16_t nextAngleDegrees = 0;
  uint16_t nextTurnRequestId = 0;
  uint16_t nextHeadingLockRequestId = 0;
  uint16_t nextHeadingLockToleranceDegrees = DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES;
  uint16_t nextHeadingLockFullCorrectionDegrees = DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES;
  uint16_t nextHeadingLockNeutralReversePercent = DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT;
  int16_t nextHeadingLockTargetOffsetDegrees = 0;
  float nextPhoneHeadingDegrees = 0.0f;
  bool nextHeadingLockEnabled = false;
  bool nextUsePhoneHeading = false;
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
    } else if (
      parseUnsignedToken(
        token,
        "HTOL=",
        MIN_HEADING_LOCK_TOLERANCE_DEGREES,
        MAX_HEADING_LOCK_TOLERANCE_DEGREES,
        nextHeadingLockToleranceDegrees
      )
    ) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockTolerance;
      sawHeadingLockTolerance = true;
    } else if (
      parseUnsignedToken(
        token,
        "HFULL=",
        MIN_HEADING_LOCK_FULL_CORRECTION_DEGREES,
        MAX_HEADING_LOCK_FULL_CORRECTION_DEGREES,
        nextHeadingLockFullCorrectionDegrees
      )
    ) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockFullCorrection;
      sawHeadingLockFullCorrection = true;
    } else if (
      parseUnsignedToken(
        token,
        "HREV=",
        MIN_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT,
        MAX_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT,
        nextHeadingLockNeutralReversePercent
      )
    ) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockNeutralReverse;
      sawHeadingLockNeutralReverse = true;
    } else if (
      parseSignedToken(
        token,
        "HOFF=",
        MIN_HEADING_LOCK_TARGET_OFFSET_DEGREES,
        MAX_HEADING_LOCK_TARGET_OFFSET_DEGREES,
        nextHeadingLockTargetOffsetDegrees
      )
    ) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockTargetOffset;
      sawHeadingLockTargetOffset = true;
    } else if (
      parseUnsignedToken(
        token,
        "VMAX=",
        MIN_VOICE_POWER_LIMIT_PERCENT,
        MAX_VOICE_POWER_LIMIT_PERCENT,
        nextVoicePowerLimitPercent
      )
    ) {
      badVoiceLimitToken = badVoiceLimitToken || sawVoicePowerLimit;
      sawVoicePowerLimit = true;
    } else if (parseHeadingSourceToken(token, nextUsePhoneHeading)) {
      badHeadingSourceToken = badHeadingSourceToken || sawHeadingSource;
      sawHeadingSource = true;
    } else if (parseHeadingDegreesToken(token, "PHDG=", nextPhoneHeadingDegrees)) {
      badHeadingSourceToken = badHeadingSourceToken || sawPhoneHeading;
      sawPhoneHeading = true;
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
      strncmp(token, "HID=", 4) == 0 ||
      strncmp(token, "HTOL=", 5) == 0 ||
      strncmp(token, "HFULL=", 6) == 0 ||
      strncmp(token, "HREV=", 5) == 0 ||
      strncmp(token, "HOFF=", 5) == 0
    ) {
      badHeadingLockToken = true;
    } else if (strncmp(token, "VMAX=", 5) == 0) {
      badVoiceLimitToken = true;
    } else if (strncmp(token, "H_SRC=", 6) == 0 || strncmp(token, "PHDG=", 5) == 0) {
      badHeadingSourceToken = true;
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

  if (badVoiceLimitToken) {
    SerialBT.println("ERR;BAD_VOICE_LIMIT");
    Serial.println("Bluetooth command rejected: bad voice power limit");
    return;
  }

  if (badHeadingSourceToken) {
    SerialBT.println("ERR;BAD_HEADING_SOURCE");
    Serial.println("Bluetooth command rejected: bad heading source");
    return;
  }

  if (nextMode != CommandMode::Throttle) {
    forceNeutralAndDisarm();
    SerialBT.println("ERR;APP_HEADING_CONTROL_ONLY");
    Serial.println("Autonomous heading command rejected: heading control runs in Android App");
    return;
  }

  const uint32_t commandNow = millis();
  if (sawHeadingSource) {
    phoneHeadingEnabled = nextUsePhoneHeading;
    if (!phoneHeadingEnabled) {
      lastPhoneHeadingMs = 0;
    }
  }
  if (phoneHeadingEnabled && sawPhoneHeading) {
    phoneHeadingDegrees = nextPhoneHeadingDegrees;
    headingDegrees = normalizeAngle180(nextPhoneHeadingDegrees);
    headingFilterInitialized = true;
    lastPhoneHeadingMs = commandNow;
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
      static_cast<uint8_t>(nextVoicePowerLimitPercent),
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
    if (nextHeadingLockFullCorrectionDegrees <= nextHeadingLockToleranceDegrees) {
      SerialBT.println("ERR;BAD_HEADING_LOCK_COMMAND");
      Serial.println("Heading lock rejected: full correction angle must exceed tolerance");
      return;
    }
    applyHeadingLockCommand(
      nextHeadingLockEnabled,
      nextHeadingLockBasePercent,
      nextHeadingLockToleranceDegrees,
      nextHeadingLockFullCorrectionDegrees,
      static_cast<uint8_t>(nextHeadingLockNeutralReversePercent),
      static_cast<uint8_t>(nextVoicePowerLimitPercent),
      nextHeadingLockRequestId,
      sawHeadingLockTargetOffset,
      nextHeadingLockTargetOffsetDegrees,
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
    activeVoicePowerLimitPercent = static_cast<uint8_t>(nextVoicePowerLimitPercent);
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
  if (applyMagCalibrationLine(line, Serial)) {
    return;
  }
  if (applyTrackLogLine(line, Serial)) {
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
  SerialBT.print(";HSRC=");
  SerialBT.print(activeHeadingSourceName(now));
  if (phoneHeadingEnabled) {
    SerialBT.print(";PHDG=");
    SerialBT.print(phoneHeadingDegrees, 1);
    SerialBT.print(";PHDG_AGE=");
    SerialBT.print(lastPhoneHeadingMs == 0 ? 9999 : now - lastPhoneHeadingMs);
  }
  SerialBT.print(";MCAL=");
  SerialBT.print(magCalibrationStateName());
  SerialBT.print(";MCNT=");
  SerialBT.print(magCalibrationSamples);
  SerialBT.print(";MRX=");
  SerialBT.print(magCalibrationSamples > 0 ? magCalibrationMaxX - magCalibrationMinX : 0);
  SerialBT.print(";MRY=");
  SerialBT.print(magCalibrationSamples > 0 ? magCalibrationMaxY - magCalibrationMinY : 0);
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
  if (gpsUtcValid) {
    SerialBT.print(";GPS_TIME=");
    SerialBT.print(gpsUtcSeconds);
  }
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
    SerialBT.print(";GPS_SPD_KMH=");
    SerialBT.print(gpsSpeedKmh, 1);
  }
  SerialBT.print(";TRK=");
  SerialBT.print(trackLogReady ? 1 : 0);
  SerialBT.print(";TRK_COUNT=");
  SerialBT.print(trackLogCount);
  SerialBT.print(";TRK_NEWEST=");
  SerialBT.print(trackLogNewestSeq);
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
    SerialBT.print(";HTOL=");
    SerialBT.print(headingLockToleranceDegrees);
    SerialBT.print(";HFULL=");
    SerialBT.print(headingLockFullCorrectionDegrees);
    SerialBT.print(";HREV=");
    SerialBT.print(headingLockNeutralReversePercent);
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
  setupTrackLog();

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
  updateTrackLog(now);
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
