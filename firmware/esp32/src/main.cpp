#include <Arduino.h>
#include "BluetoothSerial.h"
#include "esp_partition.h"
#include <Adafruit_AHRS.h>
#include <Preferences.h>
#include <Update.h>
#include <Wire.h>
#include <string.h>
#if __has_include("factory_unit_id.h")
#include "factory_unit_id.h"
#endif

#ifndef SMART_SUP_VERSION
#define SMART_SUP_VERSION "dev"
#endif

namespace {
BluetoothSerial SerialBT;
Preferences preferences;
Adafruit_Mahony imuMahonyFilter;

constexpr uint8_t LEFT_ESC_PIN = 26;
constexpr uint8_t RIGHT_ESC_PIN = 25;
constexpr uint8_t ARM_BUTTON_PIN = 17;
constexpr uint8_t STATUS_LED_PIN = 2;
constexpr uint8_t IMU_SDA_PIN = 14;
constexpr uint8_t IMU_SCL_PIN = 22;
constexpr int8_t GPS_RX_PIN = 16;
constexpr int8_t GPS_TX_PIN = -1;
constexpr uint8_t GPS_PPS_PIN = 13;
constexpr char BT_DEVICE_NAME_PREFIX[] = "SmartSUP-";
constexpr char FIRMWARE_VERSION[] = SMART_SUP_VERSION;
constexpr char NVS_NAMESPACE[] = "smart_sup";
constexpr char NVS_UNIT_ID_KEY[] = "unit_id";
constexpr char NVS_TRACK_SESSION_KEY[] = "track_sid";
constexpr char NVS_MAG_CAL_VALID_KEY[] = "mag_cal";
constexpr char NVS_MAG_OFFSET_X_KEY[] = "mag_off_x";
constexpr char NVS_MAG_OFFSET_Y_KEY[] = "mag_off_y";
constexpr char NVS_MAG_SCALE_X_KEY[] = "mag_scl_x";
constexpr char NVS_MAG_SCALE_Y_KEY[] = "mag_scl_y";
constexpr char NVS_ESC_LEFT_REVERSED_KEY[] = "esc_l_rev";
constexpr char NVS_ESC_RIGHT_REVERSED_KEY[] = "esc_r_rev";
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
constexpr int8_t ESC_MIN_EFFECTIVE_THROTTLE_PERCENT = 9;
constexpr uint16_t THROTTLE_RAMP_US_PER_TICK = 5;
constexpr uint32_t CONTROL_TICK_MS = 20;
constexpr uint32_t ESC_BOOT_NEUTRAL_HOLD_MS = 3000;
constexpr uint32_t ARM_HOLD_MS = 1500;
constexpr uint32_t BT_COMMAND_TIMEOUT_MS = 1000;
constexpr uint32_t BT_STATUS_INTERVAL_MS = 1000;
constexpr uint32_t BT_YB_IMU_STATUS_INTERVAL_MS = 100;
constexpr uint16_t I2C_TIMEOUT_MS = 50;
constexpr uint32_t OTA_TIMEOUT_MS = 30000;
constexpr uint32_t OTA_READ_COALESCE_MS = 20;
constexpr uint32_t TURN_CONTROL_TIMEOUT_MS = 8000;
constexpr uint8_t MIN_VOICE_POWER_LIMIT_PERCENT = 5;
constexpr uint8_t MAX_VOICE_POWER_LIMIT_PERCENT = 100;
constexpr uint8_t DEFAULT_VOICE_POWER_LIMIT_PERCENT = 70;
constexpr int8_t VOICE_MAX_TURN_DELTA_PERCENT = 20;
constexpr float TURN_DONE_DEGREES = 3.0f;
constexpr int8_t HEADING_LOCK_MIN_CORRECTION_PERCENT = 3;
constexpr int8_t HEADING_LOCK_MAX_CORRECTION_PERCENT = 70;
constexpr int8_t HEADING_LOCK_FORWARD_MAX_OUTPUT_PERCENT = 70;
constexpr int8_t HEADING_LOCK_REVERSE_MAX_OUTPUT_PERCENT = 60;
constexpr uint16_t DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES = 2;
constexpr uint16_t DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES = 45;
constexpr uint8_t DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT =
  HEADING_LOCK_REVERSE_MAX_OUTPUT_PERCENT;
constexpr uint16_t MIN_HEADING_LOCK_TOLERANCE_DEGREES = 1;
constexpr uint16_t MAX_HEADING_LOCK_TOLERANCE_DEGREES = 20;
constexpr uint16_t MIN_HEADING_LOCK_FULL_CORRECTION_DEGREES = 5;
constexpr uint16_t MAX_HEADING_LOCK_FULL_CORRECTION_DEGREES = 180;
constexpr uint8_t MIN_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = 0;
constexpr uint8_t MAX_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = 100;
constexpr float HEADING_LOCK_MAX_TARGET_RATE_DEG_S = 45.0f;
constexpr float HEADING_LOCK_RATE_KP_PERCENT_PER_DEGREE_S = 0.8f;
constexpr float HEADING_LOCK_RATE_KI_PERCENT_PER_DEGREE_S_SECOND = 2.5f;
constexpr float HEADING_LOCK_ACCEL_DAMP_PERCENT_PER_DEGREE_S2 = 0.015f;
constexpr float HEADING_LOCK_RATE_ERROR_INTEGRAL_DEADBAND_DEG_S = 0.8f;
constexpr float HEADING_LOCK_ADAPTIVE_BOOST_MAX_PERCENT = 65.0f;
constexpr float HEADING_LOCK_ADAPTIVE_BOOST_RATE_LIMIT_PERCENT_PER_SECOND = 25.0f;
constexpr float HEADING_LOCK_ADAPTIVE_BOOST_MIN_RATE_PERCENT_PER_SECOND = 12.0f;
constexpr float HEADING_LOCK_ADAPTIVE_BOOST_DECAY = 0.90f;
constexpr float HEADING_LOCK_ADAPTIVE_BOOST_HOLD_DECAY = 0.995f;
constexpr int8_t HEADING_LOCK_MIN_NEUTRAL_EFFECTIVE_CORRECTION_PERCENT =
  ESC_MIN_EFFECTIVE_THROTTLE_PERCENT + 1;
constexpr float HEADING_LOCK_QUIET_HOLD_MARGIN_DEGREES = 1.0f;
constexpr float HEADING_LOCK_OBSERVER_B0_DEG_S2_PER_PERCENT = 4.0f;
constexpr float HEADING_LOCK_OBSERVER_ALPHA = 0.10f;
constexpr float HEADING_LOCK_OBSERVER_DECAY = 0.95f;
constexpr float HEADING_LOCK_OBSERVER_MAX_PERCENT = 25.0f;
constexpr float HEADING_LOCK_MAX_RATE_DOT_DEG_S2 = 360.0f;
constexpr float HEADING_LOCK_LOOKAHEAD_SECONDS = 0.6f;
constexpr float HEADING_LOCK_BRAKE_WINDOW_DEGREES = 3.0f;
constexpr float HEADING_LOCK_MIN_BRAKE_RATE_DEG_S = 8.0f;
constexpr float HEADING_LOCK_FULL_BRAKE_RATE_DEG_S = 35.0f;
constexpr int8_t HEADING_LOCK_MIN_BRAKE_PERCENT = 20;
constexpr int8_t HEADING_LOCK_MAX_BRAKE_PERCENT = 35;
constexpr uint16_t HEADING_LOCK_MIN_BRAKE_HOLD_MS = 250;
constexpr uint16_t HEADING_LOCK_MAX_BRAKE_HOLD_MS = 800;
constexpr float HEADING_LOCK_SETTLE_RATE_DEG_S = 3.0f;
constexpr uint32_t HEADING_LOCK_DIVERGENCE_WINDOW_MS = 1500;
constexpr float HEADING_LOCK_DIVERGENCE_DEGREES = 5.0f;
constexpr float HEADING_LOCK_DIVERGENCE_MIN_AWAY_RATE_DEG_S = 3.0f;
constexpr int8_t HEADING_LOCK_STEER_SIGN = 1;
constexpr uint32_t YB_IMU_HEADING_TIMEOUT_MS = 500;
constexpr uint16_t MAX_VOICE_TURN_ANGLE_DEGREES = 180;
constexpr float ICM20948_GYRO_250DPS_LSB_PER_DPS = 131.0f;
constexpr float IMU_YAW_SIGN = 1.0f;
constexpr float RADIANS_TO_DEGREES = 57.2957795f;
constexpr float MAG_HEADING_OFFSET_DEGREES = 0.0f;
constexpr float MAG_HEADING_FILTER_ALPHA = 0.22f;
constexpr uint32_t MAG_HEADING_INTERVAL_MS = 20;
constexpr float IMU_FUSION_SAMPLE_RATE_HZ = 50.0f;
constexpr float IMU_FUSION_MAG_ALPHA_MOVING = 0.008f;
constexpr float IMU_FUSION_MAG_ALPHA_STILL = 0.050f;
constexpr float IMU_FUSION_MAG_ALPHA_RECOVER = 0.120f;
constexpr float IMU_FUSION_STILL_GYRO_DPS = 1.5f;
constexpr float IMU_FUSION_RECOVER_ERROR_DEGREES = 8.0f;
constexpr float IMU_FUSION_MAX_MAG_CORRECTION_MOVING_DPS = 30.0f;
constexpr float IMU_FUSION_MAX_MAG_CORRECTION_STILL_DPS = 100.0f;
constexpr float IMU_TURNING_GYRO_DPS = 8.0f;
constexpr float IMU_AHRS_YAW_OFFSET_ALPHA_STILL = 0.014f;
constexpr float IMU_AHRS_YAW_OFFSET_ALPHA_MOVING = 0.002f;
constexpr float IMU_AHRS_YAW_OFFSET_ALPHA_RECOVER = 0.035f;
constexpr float IMU_HEADING_OUTPUT_ALPHA_STILL = 0.10f;
constexpr float IMU_HEADING_OUTPUT_ALPHA_MOVING = 0.28f;
constexpr float IMU_HEADING_OUTPUT_ALPHA_RECOVER = 0.20f;
constexpr float IMU_HEADING_OUTPUT_MAX_DPS_STILL = 8.0f;
constexpr float IMU_HEADING_OUTPUT_MAX_DPS_MOVING = 35.0f;
constexpr float IMU_HEADING_OUTPUT_MAX_DPS_RECOVER = 22.0f;
constexpr uint32_t IMU_TURN_RECOVERY_MS = 1200;
constexpr uint32_t IMU_FUSION_SETTLING_MS = 2000;
constexpr float IMU_ACCEL_NORM_MIN_G = 0.65f;
constexpr float IMU_ACCEL_NORM_MAX_G = 1.35f;
constexpr float IMU_MAG_DISTURBANCE_RATIO = 0.30f;
constexpr uint16_t IMU_GYRO_BIAS_SAMPLES = 200;
constexpr float IMU_GYRO_BIAS_STABLE_RANGE_DPS = 3.0f;
constexpr float IMU_GYRO_SATURATION_DPS = 230.0f;
constexpr uint16_t MAG_CAL_MIN_SAMPLES = 80;
constexpr int32_t MAG_CAL_MIN_RANGE = 100;
constexpr float MAG_CAL_MIN_SCALE = 0.2f;
constexpr float MAG_CAL_MAX_SCALE = 5.0f;
constexpr uint8_t IMU_FAILURE_LIMIT = 10;
constexpr uint8_t YB_CALIBRATION_STATE_ACTIVE = 250;
constexpr uint8_t YB_CALIBRATION_STATE_TIMEOUT = 254;
constexpr uint32_t YB_CALIBRATION_STATUS_POLL_MS = 500;
constexpr uint32_t YB_IMU_CALIBRATION_MAX_MS = 10000;
constexpr uint32_t YB_MAG_CALIBRATION_MAX_MS = 60000;
constexpr uint32_t YB_IMU_REDETECT_INTERVAL_MS = 1000;
constexpr size_t BT_RX_BUFFER_SIZE = 192;
constexpr size_t SERIAL_RX_BUFFER_SIZE = 96;
constexpr size_t GPS_RX_BUFFER_SIZE = 128;
constexpr size_t OTA_BUFFER_SIZE = 512;
constexpr size_t OTA_MAX_CHUNK_SIZE = 1024;
constexpr size_t OTA_PROGRESS_INTERVAL_BYTES = 16 * 1024;
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
constexpr uint8_t ICM20948_ACCEL_XOUT_H = 0x2D;
constexpr uint8_t ICM20948_GYRO_ZOUT_H = 0x37;
constexpr uint8_t ICM20948_GYRO_SMPLRT_DIV = 0x00;
constexpr uint8_t ICM20948_GYRO_CONFIG_1 = 0x01;
constexpr uint8_t ICM20948_GYRO_CONFIG_2 = 0x02;
constexpr float ICM20948_ACCEL_2G_LSB_PER_G = 16384.0f;
constexpr uint8_t AK09916_ADDR = 0x0C;
constexpr uint8_t AK09916_WIA2 = 0x01;
constexpr uint8_t AK09916_WIA2_VALUE = 0x09;
constexpr uint8_t AK09916_ST1 = 0x10;
constexpr uint8_t AK09916_CNTL2 = 0x31;
constexpr uint8_t AK09916_CNTL3 = 0x32;
constexpr uint8_t AK09916_MODE_CONTINUOUS_50HZ = 0x06;
constexpr uint32_t IMU_I2C_CLOCK_HZ = 100000;
constexpr uint8_t YB_IMU_ADDR = 0x23;
constexpr uint8_t YB_IMU_RAW_ACCEL = 0x04;
constexpr uint8_t YB_IMU_RAW_GYRO = 0x0A;
constexpr uint8_t YB_IMU_RAW_MAG = 0x10;
constexpr uint8_t YB_IMU_QUAT = 0x16;
constexpr uint8_t YB_IMU_EULER = 0x26;
constexpr uint8_t YB_IMU_CALIB_IMU = 0x70;
constexpr uint8_t YB_IMU_CALIB_MAG = 0x71;
constexpr float YB_IMU_ACCEL_SCALE_G = 16.0f / 32767.0f;
constexpr float YB_IMU_GYRO_SCALE_RAD_S = (2000.0f / 32767.0f) * (3.1415926f / 180.0f);
constexpr float YB_IMU_MAG_SCALE_UT = 800.0f / 32767.0f;
constexpr float YB_IMU_HEADING_FORWARD_OFFSET_DEGREES = 180.0f;

enum class CommandSource : uint8_t {
  App,
  Voice,
};

enum class CommandMode : uint8_t {
  Throttle,
  TurnAngle,
  HeadingLock,
  KeepAlive,
};

enum class TurnDirection : uint8_t {
  Left,
  Right,
};

enum class HeadingLockPhase : uint8_t {
  Correct,
  Brake,
  Settle,
  Guard,
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
int8_t reportedLeftPercent = 0;
int8_t reportedRightPercent = 0;
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
bool otaChunkedProtocol = false;
bool otaChunkReceivingData = false;
size_t otaExpectedBytes = 0;
size_t otaWrittenBytes = 0;
size_t otaChunkSize = OTA_MAX_CHUNK_SIZE;
size_t otaChunkOffset = 0;
size_t otaChunkLength = 0;
size_t otaChunkReceived = 0;
uint32_t otaChunkExpectedCrc = 0;
uint32_t otaChunkRunningCrc = 0;
uint32_t otaLastDataMs = 0;
uint32_t otaLastProgressMs = 0;
size_t otaLastProgressBytes = 0;
char otaLineBuffer[BT_RX_BUFFER_SIZE] = {};
size_t otaLineLen = 0;
uint8_t otaChunkBuffer[OTA_MAX_CHUNK_SIZE] = {};
bool nvsReady = false;
uint16_t unitId = DEFAULT_UNIT_ID;
char btDeviceName[BT_DEVICE_NAME_SIZE] = {};
bool unitIdProvisioned = false;
const char* unitIdSource = "FALLBACK";
bool escLeftReversed = false;
bool escRightReversed = false;
bool imuAvailable = false;
bool magnetometerAvailable = false;
bool ybImuAvailable = false;
bool headingFilterInitialized = false;
bool imuHeadingFilterInitialized = false;
bool magCalibrationValid = false;
bool magCalibrationActive = false;
uint8_t imuAddress = ICM20948_ADDR_LOW;
uint8_t imuFailureCount = 0;
uint8_t ybImuFailureCount = 0;
bool ybCalibrationActive = false;
uint8_t ybCalibrationTargetRegister = 0;
uint8_t ybCalibrationLastState = 0;
uint16_t ybCalibrationReadFailures = 0;
uint32_t ybCalibrationStartedMs = 0;
uint32_t ybCalibrationLastPollMs = 0;
uint32_t ybCalibrationMaxMs = 0;
float headingDegrees = 0.0f;
float imuHeadingDegrees = 0.0f;
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
bool gyroZBiasCalibrated = false;
float imuAccelXG = 0.0f;
float imuAccelYG = 0.0f;
float imuAccelZG = 0.0f;
float imuAccelNormG = 0.0f;
float imuGyroXDps = 0.0f;
float imuGyroYDps = 0.0f;
float imuGyroZDps = 0.0f;
int16_t imuMagRawX = 0;
int16_t imuMagRawY = 0;
int16_t imuMagRawZ = 0;
float imuMagNormRaw = 0.0f;
float imuMagHeadingDegrees = 0.0f;
float imuMagCalX = 0.0f;
float imuMagCalY = 0.0f;
float imuMagCalZ = 0.0f;
float imuAharsRawYawDegrees = 0.0f;
float imuAharsYawOffsetDegrees = 0.0f;
bool imuAharsYawOffsetInitialized = false;
float imuRollDegrees = 0.0f;
float imuPitchDegrees = 0.0f;
float imuMagNormReferenceRaw = 0.0f;
bool imuMagNormReferenceInitialized = false;
uint32_t imuFusionInitializedMs = 0;
uint32_t imuLastMagCorrectionMs = 0;
uint32_t imuLastTurningMs = 0;
const char* imuFusionQuality = "STALE";
uint32_t lastYbImuReadMs = 0;
uint32_t lastYbImuDetectAttemptMs = 0;
uint32_t lastYbImuSampleMs = 0;
float ybAccelXG = 0.0f;
float ybAccelYG = 0.0f;
float ybAccelZG = 0.0f;
float ybGyroXRadS = 0.0f;
float ybGyroYRadS = 0.0f;
float ybGyroZRadS = 0.0f;
float ybMagXUt = 0.0f;
float ybMagYUt = 0.0f;
float ybMagZUt = 0.0f;
float ybQuatW = 1.0f;
float ybQuatX = 0.0f;
float ybQuatY = 0.0f;
float ybQuatZ = 0.0f;
float ybRollDegrees = 0.0f;
float ybPitchDegrees = 0.0f;
float ybYawDegrees = 0.0f;
bool ybHeadingInitialized = false;
uint8_t ybImuCalibrationState = 255;
uint8_t ybMagCalibrationState = 255;
float lastTurnErrorDegrees = 0.0f;
bool turnControlActive = false;
bool turnControlCompleted = false;
uint16_t activeTurnRequestId = 0;
uint16_t completedTurnRequestId = 0;
float turnTargetHeadingDegrees = 0.0f;
uint32_t turnStartedMs = 0;
bool headingLockActive = false;
uint16_t activeHeadingLockRequestId = 0;
float headingLockTargetDegrees = 0.0f;
float lastHeadingLockErrorDegrees = 0.0f;
float lastHeadingLockRateDegS = 0.0f;
float lastHeadingLockTargetRateDegS = 0.0f;
float lastHeadingLockRateErrorDegS = 0.0f;
float lastHeadingLockRateDotDegS2 = 0.0f;
float lastHeadingLockPredictedErrorDegrees = 0.0f;
float lastHeadingLockPdPercent = 0.0f;
float lastHeadingLockInnerPercent = 0.0f;
float headingLockDisturbancePercent = 0.0f;
float headingLockAdaptiveBoostFloatPercent = 0.0f;
float lastHeadingLockBrakePercent = 0.0f;
int8_t headingLockAdaptiveBoostPercent = 0;
int8_t headingLockBasePercent = 0;
int8_t lastHeadingLockCorrectionPercent = 0;
bool headingLockDivergenceWarningActive = false;
bool headingLockHeadingUnavailableWarningActive = false;
uint16_t headingLockToleranceDegrees = DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES;
uint16_t headingLockFullCorrectionDegrees = DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES;
uint8_t headingLockNeutralReversePercent = DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT;
CommandSource headingLockSource = CommandSource::App;
HeadingLockPhase headingLockPhase = HeadingLockPhase::Correct;
uint32_t headingLockBrakeStartedMs = 0;
uint16_t headingLockBrakeHoldTargetMs = 0;
float headingLockBrakeStartRateSign = 0.0f;
float previousHeadingLockHeadingDegrees = 0.0f;
uint32_t previousHeadingLockRateMs = 0;
bool previousHeadingLockRateValid = false;
float previousHeadingLockRateDegS = 0.0f;
bool previousHeadingLockRateDotValid = false;
uint32_t headingLockDivergenceStartedMs = 0;
float headingLockDivergenceStartAbsError = 0.0f;
float headingLockDivergenceErrorSign = 0.0f;
uint32_t lastHeadingLockControlMs = 0;
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

void loadEscDirectionConfig() {
  if (!nvsReady) {
    escLeftReversed = false;
    escRightReversed = false;
    return;
  }
  escLeftReversed = preferences.getBool(NVS_ESC_LEFT_REVERSED_KEY, false);
  escRightReversed = preferences.getBool(NVS_ESC_RIGHT_REVERSED_KEY, false);
}

bool saveEscDirectionConfig(bool leftReversed, bool rightReversed) {
  escLeftReversed = leftReversed;
  escRightReversed = rightReversed;
  if (!nvsReady) {
    return false;
  }
  preferences.putBool(NVS_ESC_LEFT_REVERSED_KEY, escLeftReversed);
  preferences.putBool(NVS_ESC_RIGHT_REVERSED_KEY, escRightReversed);
  return true;
}

uint32_t pulseUsToDuty(uint16_t pulseUs) {
  const uint32_t maxDuty = (1UL << ESC_PWM_RESOLUTION_BITS) - 1;
  return (static_cast<uint32_t>(pulseUs) * maxDuty) / 20000UL;
}

void writeEsc(uint8_t channel, uint16_t pulseUs) {
  ledcWrite(channel, pulseUsToDuty(pulseUs));
}

void holdEscNeutralDuringBoot() {
  const uint32_t startedAt = millis();
  leftPulseUs = ESC_NEUTRAL_US;
  rightPulseUs = ESC_NEUTRAL_US;
  Serial.print("Holding ESC neutral for boot arming window ms=");
  Serial.println(ESC_BOOT_NEUTRAL_HOLD_MS);
  while (millis() - startedAt < ESC_BOOT_NEUTRAL_HOLD_MS) {
    writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
    writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);
    delay(CONTROL_TICK_MS);
  }
}

uint16_t signedPercentToPulseUs(int8_t percent) {
  const int constrainedPercent = constrain(static_cast<int>(percent), -100, 100);
  if (abs(constrainedPercent) < ESC_MIN_EFFECTIVE_THROTTLE_PERCENT) {
    return ESC_NEUTRAL_US;
  }
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
    case CommandMode::KeepAlive:
      return "KEEPALIVE";
    case CommandMode::Throttle:
    default:
      return "THROTTLE";
  }
}

const char* headingLockPhaseName(HeadingLockPhase phase) {
  switch (phase) {
    case HeadingLockPhase::Brake:
      return "BRAKE";
    case HeadingLockPhase::Settle:
      return "SETTLE";
    case HeadingLockPhase::Guard:
      return "GUARD";
    case HeadingLockPhase::Correct:
    default:
      return "CORRECT";
  }
}

float signOrZero(float value) {
  if (value > 0.0f) {
    return 1.0f;
  }
  if (value < 0.0f) {
    return -1.0f;
  }
  return 0.0f;
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
  const int voiceLimit = headingLockSource == CommandSource::Voice
    ? static_cast<int>(activeVoicePowerLimitPercent)
    : 100;
  const int forwardLimit = min(static_cast<int>(HEADING_LOCK_FORWARD_MAX_OUTPUT_PERCENT), voiceLimit);
  const int reverseLimit = min(static_cast<int>(HEADING_LOCK_REVERSE_MAX_OUTPUT_PERCENT), voiceLimit);
  leftPercent = static_cast<int8_t>(constrain(static_cast<int>(leftPercent), -reverseLimit, forwardLimit));
  rightPercent = static_cast<int8_t>(constrain(static_cast<int>(rightPercent), -reverseLimit, forwardLimit));
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

float currentYbHeadingDegrees() {
  return normalizeCompass360(ybYawDegrees + YB_IMU_HEADING_FORWARD_OFFSET_DEGREES);
}

float currentYbHeadingRateDegS() {
  return ybGyroZRadS * RADIANS_TO_DEGREES;
}

uint32_t ybHeadingAgeMs(uint32_t now) {
  if (lastYbImuSampleMs == 0) {
    return 999999UL;
  }
  if (lastYbImuSampleMs > now) {
    return 0;
  }
  return now - lastYbImuSampleMs;
}

bool hasFreshYbHeading(uint32_t now) {
  return ybImuAvailable &&
    ybHeadingInitialized &&
    lastYbImuSampleMs != 0 &&
    ybHeadingAgeMs(now) <= YB_IMU_HEADING_TIMEOUT_MS;
}

bool hasUsableFirmwareHeading(uint32_t now) {
  return hasFreshYbHeading(now);
}

const char* activeHeadingSourceName(uint32_t now) {
  if (hasUsableFirmwareHeading(now)) {
    return "YBIMU";
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

bool readImuMotionSample() {
  uint8_t buffer[12] = {};
  if (!imuSelectBank(ICM20948_BANK_0) || !imuRead(ICM20948_ACCEL_XOUT_H, buffer, sizeof(buffer))) {
    return false;
  }

  const int16_t rawAccelX = static_cast<int16_t>((static_cast<uint16_t>(buffer[0]) << 8) | buffer[1]);
  const int16_t rawAccelY = static_cast<int16_t>((static_cast<uint16_t>(buffer[2]) << 8) | buffer[3]);
  const int16_t rawAccelZ = static_cast<int16_t>((static_cast<uint16_t>(buffer[4]) << 8) | buffer[5]);
  const int16_t rawGyroX = static_cast<int16_t>((static_cast<uint16_t>(buffer[6]) << 8) | buffer[7]);
  const int16_t rawGyroY = static_cast<int16_t>((static_cast<uint16_t>(buffer[8]) << 8) | buffer[9]);
  const int16_t rawGyroZ = static_cast<int16_t>((static_cast<uint16_t>(buffer[10]) << 8) | buffer[11]);

  imuAccelXG = static_cast<float>(rawAccelX) / ICM20948_ACCEL_2G_LSB_PER_G;
  imuAccelYG = static_cast<float>(rawAccelY) / ICM20948_ACCEL_2G_LSB_PER_G;
  imuAccelZG = static_cast<float>(rawAccelZ) / ICM20948_ACCEL_2G_LSB_PER_G;
  imuAccelNormG = sqrtf(
    imuAccelXG * imuAccelXG +
      imuAccelYG * imuAccelYG +
      imuAccelZG * imuAccelZG
  );
  imuGyroXDps = static_cast<float>(rawGyroX) / ICM20948_GYRO_250DPS_LSB_PER_DPS;
  imuGyroYDps = static_cast<float>(rawGyroY) / ICM20948_GYRO_250DPS_LSB_PER_DPS;
  imuGyroZDps = ((static_cast<float>(rawGyroZ) / ICM20948_GYRO_250DPS_LSB_PER_DPS) * IMU_YAW_SIGN) - gyroZBiasDps;
  return true;
}

bool ybImuRead(uint8_t reg, uint8_t* buffer, size_t length) {
  Wire.beginTransmission(YB_IMU_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  const size_t readBytes = Wire.requestFrom(static_cast<int>(YB_IMU_ADDR), static_cast<int>(length));
  if (readBytes != length) {
    while (Wire.available() > 0) {
      Wire.read();
    }
    return false;
  }

  for (size_t i = 0; i < length; ++i) {
    buffer[i] = Wire.read();
  }
  return true;
}

bool ybImuWriteByte(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(YB_IMU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

int16_t ybInt16Le(const uint8_t* data) {
  return static_cast<int16_t>((static_cast<uint16_t>(data[1]) << 8) | data[0]);
}

float ybFloatLe(const uint8_t* data) {
  float value = 0.0f;
  uint8_t bytes[4] = {data[0], data[1], data[2], data[3]};
  memcpy(&value, bytes, sizeof(value));
  return value;
}

bool readYbImuSample() {
  uint8_t motion[18] = {};
  uint8_t quat[16] = {};
  uint8_t euler[12] = {};

  if (
    !ybImuRead(YB_IMU_RAW_ACCEL, motion, sizeof(motion)) ||
    !ybImuRead(YB_IMU_QUAT, quat, sizeof(quat)) ||
    !ybImuRead(YB_IMU_EULER, euler, sizeof(euler))
  ) {
    return false;
  }

  ybAccelXG = static_cast<float>(ybInt16Le(&motion[0])) * YB_IMU_ACCEL_SCALE_G;
  ybAccelYG = static_cast<float>(ybInt16Le(&motion[2])) * YB_IMU_ACCEL_SCALE_G;
  ybAccelZG = static_cast<float>(ybInt16Le(&motion[4])) * YB_IMU_ACCEL_SCALE_G;
  ybGyroXRadS = static_cast<float>(ybInt16Le(&motion[6])) * YB_IMU_GYRO_SCALE_RAD_S;
  ybGyroYRadS = static_cast<float>(ybInt16Le(&motion[8])) * YB_IMU_GYRO_SCALE_RAD_S;
  ybGyroZRadS = static_cast<float>(ybInt16Le(&motion[10])) * YB_IMU_GYRO_SCALE_RAD_S;
  ybMagXUt = static_cast<float>(ybInt16Le(&motion[12])) * YB_IMU_MAG_SCALE_UT;
  ybMagYUt = static_cast<float>(ybInt16Le(&motion[14])) * YB_IMU_MAG_SCALE_UT;
  ybMagZUt = static_cast<float>(ybInt16Le(&motion[16])) * YB_IMU_MAG_SCALE_UT;

  ybQuatW = ybFloatLe(&quat[0]);
  ybQuatX = ybFloatLe(&quat[4]);
  ybQuatY = ybFloatLe(&quat[8]);
  ybQuatZ = ybFloatLe(&quat[12]);
  ybRollDegrees = ybFloatLe(&euler[0]) * RADIANS_TO_DEGREES;
  ybPitchDegrees = ybFloatLe(&euler[4]) * RADIANS_TO_DEGREES;
  ybYawDegrees = ybFloatLe(&euler[8]) * RADIANS_TO_DEGREES;

  const float accelNorm = sqrtf(ybAccelXG * ybAccelXG + ybAccelYG * ybAccelYG + ybAccelZG * ybAccelZG);
  const float quatNorm = sqrtf(ybQuatW * ybQuatW + ybQuatX * ybQuatX + ybQuatY * ybQuatY + ybQuatZ * ybQuatZ);
  const bool valid =
    isfinite(accelNorm) &&
    accelNorm >= IMU_ACCEL_NORM_MIN_G &&
    accelNorm <= IMU_ACCEL_NORM_MAX_G &&
    isfinite(quatNorm) &&
    quatNorm >= 0.70f &&
    quatNorm <= 1.30f &&
    isfinite(ybRollDegrees) &&
    isfinite(ybPitchDegrees) &&
    isfinite(ybYawDegrees);

  if (valid) {
    headingDegrees = currentYbHeadingDegrees();
    headingFilterInitialized = true;
    ybHeadingInitialized = true;
    lastYbImuSampleMs = millis();
  }
  return valid;
}

void beginYbCalibration(uint8_t targetRegister) {
  ybCalibrationActive = true;
  ybCalibrationTargetRegister = targetRegister;
  ybCalibrationLastState = 0;
  ybCalibrationReadFailures = 0;
  ybCalibrationStartedMs = millis();
  ybCalibrationLastPollMs = 0;
  ybCalibrationMaxMs = targetRegister == YB_IMU_CALIB_MAG
    ? YB_MAG_CALIBRATION_MAX_MS
    : YB_IMU_CALIBRATION_MAX_MS;
  ybImuFailureCount = 0;

  if (targetRegister == YB_IMU_CALIB_IMU) {
    ybImuCalibrationState = YB_CALIBRATION_STATE_ACTIVE;
  } else if (targetRegister == YB_IMU_CALIB_MAG) {
    ybMagCalibrationState = YB_CALIBRATION_STATE_ACTIVE;
  }
}

void finishYbCalibration(uint8_t finalState) {
  if (ybCalibrationTargetRegister == YB_IMU_CALIB_IMU) {
    ybImuCalibrationState = finalState;
  } else if (ybCalibrationTargetRegister == YB_IMU_CALIB_MAG) {
    ybMagCalibrationState = finalState;
  }
  ybCalibrationActive = false;
  ybCalibrationTargetRegister = 0;
  ybCalibrationLastState = finalState;
  ybCalibrationStartedMs = 0;
  ybCalibrationLastPollMs = 0;
  ybCalibrationMaxMs = 0;
  ybImuFailureCount = 0;
}

void updateYbCalibration(uint32_t now) {
  if (!ybCalibrationActive) {
    return;
  }

  if (now - ybCalibrationStartedMs >= ybCalibrationMaxMs) {
    finishYbCalibration(YB_CALIBRATION_STATE_TIMEOUT);
    Serial.println("Yahboom calibration timeout; telemetry restored");
    SerialBT.println("YB_CAL;ERR=TIMEOUT");
    return;
  }

  if (now - ybCalibrationLastPollMs < YB_CALIBRATION_STATUS_POLL_MS) {
    return;
  }
  ybCalibrationLastPollMs = now;

  uint8_t state = 0;
  if (!ybImuRead(ybCalibrationTargetRegister, &state, 1)) {
    if (ybCalibrationReadFailures < UINT16_MAX) {
      ybCalibrationReadFailures += 1;
    }
    return;
  }
  ybCalibrationLastState = state;

  if (state != 0) {
    finishYbCalibration(state);
    Serial.println("Yahboom calibration completed");
    SerialBT.print("YB_CAL;DONE=");
    SerialBT.println(state);
  }
}

bool detectYbImu() {
  Wire.beginTransmission(YB_IMU_ADDR);
  if (Wire.endTransmission() != 0) {
    return false;
  }
  return readYbImuSample();
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
  const int16_t rawZ = static_cast<int16_t>((static_cast<uint16_t>(buffer[6]) << 8) | buffer[5]);
  imuMagRawX = rawX;
  imuMagRawY = rawY;
  imuMagRawZ = rawZ;
  imuMagNormRaw = sqrtf(
    static_cast<float>(rawX) * static_cast<float>(rawX) +
      static_cast<float>(rawY) * static_cast<float>(rawY) +
      static_cast<float>(rawZ) * static_cast<float>(rawZ)
  );
  if (rawX == 0 && rawY == 0 && rawZ == 0) {
    return true;
  }
  updateMagCalibrationSample(rawX, rawY);

  const float calibratedX = magCalibrationValid ? (static_cast<float>(rawX) - magOffsetX) * magScaleX : static_cast<float>(rawX);
  const float calibratedY = magCalibrationValid ? (static_cast<float>(rawY) - magOffsetY) * magScaleY : static_cast<float>(rawY);
  imuMagCalX = calibratedX;
  imuMagCalY = calibratedY;
  imuMagCalZ = static_cast<float>(rawZ);
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

float clampFloat(float value, float minValue, float maxValue) {
  if (value < minValue) {
    return minValue;
  }
  if (value > maxValue) {
    return maxValue;
  }
  return value;
}

bool accelNormSane() {
  return isfinite(imuAccelNormG) &&
    imuAccelNormG >= IMU_ACCEL_NORM_MIN_G &&
    imuAccelNormG <= IMU_ACCEL_NORM_MAX_G;
}

bool gyroYawSane() {
  return isfinite(imuGyroZDps) && fabsf(imuGyroZDps) < IMU_GYRO_SATURATION_DPS;
}

bool magNormSane() {
  if (!isfinite(imuMagNormRaw) || imuMagNormRaw < 1.0f) {
    return false;
  }

  if (!imuMagNormReferenceInitialized) {
    imuMagNormReferenceRaw = imuMagNormRaw;
    imuMagNormReferenceInitialized = true;
    return true;
  }

  const float allowedDelta = imuMagNormReferenceRaw * IMU_MAG_DISTURBANCE_RATIO;
  if (fabsf(imuMagNormRaw - imuMagNormReferenceRaw) > allowedDelta) {
    return false;
  }

  imuMagNormReferenceRaw = imuMagNormReferenceRaw * 0.995f + imuMagNormRaw * 0.005f;
  return true;
}

float ahrsAccelXG() {
  return imuAccelXG;
}

float ahrsAccelYG() {
  return -imuAccelYG;
}

float ahrsAccelZG() {
  return -imuAccelZG;
}

float ahrsGyroXDps() {
  return imuGyroXDps;
}

float ahrsGyroYDps() {
  return -imuGyroYDps;
}

float ahrsGyroZDps() {
  return imuGyroZDps;
}

float ahrsMagX() {
  return imuMagCalX;
}

float ahrsMagY() {
  return -imuMagCalY;
}

float ahrsMagZ() {
  return -imuMagCalZ;
}

float normalizeBoatTiltDegrees(float angleDegrees) {
  float normalized = normalizeAngle180(angleDegrees);
  if (normalized > 90.0f) {
    normalized -= 180.0f;
  } else if (normalized < -90.0f) {
    normalized += 180.0f;
  }
  return normalized;
}

bool imuIsTurning() {
  return isfinite(imuGyroZDps) && fabsf(imuGyroZDps) >= IMU_TURNING_GYRO_DPS;
}

bool imuIsRecoveringFromTurn(uint32_t now) {
  return imuLastTurningMs != 0 && now - imuLastTurningMs <= IMU_TURN_RECOVERY_MS;
}

float imuYawOffsetCorrectionAlpha(uint32_t now) {
  if (imuIsTurning()) {
    return IMU_AHRS_YAW_OFFSET_ALPHA_MOVING;
  }
  return imuIsRecoveringFromTurn(now)
    ? IMU_AHRS_YAW_OFFSET_ALPHA_RECOVER
    : IMU_AHRS_YAW_OFFSET_ALPHA_STILL;
}

float imuHeadingOutputAlpha(uint32_t now) {
  if (imuIsTurning()) {
    return IMU_HEADING_OUTPUT_ALPHA_MOVING;
  }
  return imuIsRecoveringFromTurn(now)
    ? IMU_HEADING_OUTPUT_ALPHA_RECOVER
    : IMU_HEADING_OUTPUT_ALPHA_STILL;
}

float imuHeadingOutputMaxStepDegrees(uint32_t now, float dtSeconds) {
  const float maxDps = imuIsTurning()
    ? IMU_HEADING_OUTPUT_MAX_DPS_MOVING
    : (imuIsRecoveringFromTurn(now)
      ? IMU_HEADING_OUTPUT_MAX_DPS_RECOVER
      : IMU_HEADING_OUTPUT_MAX_DPS_STILL);
  return maxDps * clampFloat(dtSeconds, 0.001f, 0.12f);
}

void correctAhrsYawOffsetTowardMag(float magHeadingDegrees, uint32_t now) {
  const float targetOffsetDegrees = shortestAngleError(magHeadingDegrees, imuAharsRawYawDegrees);
  const float offsetErrorDegrees = shortestAngleError(targetOffsetDegrees, imuAharsYawOffsetDegrees);
  imuAharsYawOffsetDegrees = normalizeAngle180(
    imuAharsYawOffsetDegrees + offsetErrorDegrees * imuYawOffsetCorrectionAlpha(now)
  );
}

void updateImuHeadingOutput(float targetHeadingDegrees, uint32_t now, float dtSeconds) {
  const float alpha = imuHeadingOutputAlpha(now);
  const float outputErrorDegrees = shortestAngleError(targetHeadingDegrees, imuHeadingDegrees);
  const float maxStepDegrees = imuHeadingOutputMaxStepDegrees(now, dtSeconds);
  const float outputStepDegrees = clampFloat(
    outputErrorDegrees * alpha,
    -maxStepDegrees,
    maxStepDegrees
  );
  imuHeadingDegrees = normalizeAngle180(imuHeadingDegrees + outputStepDegrees);
}

void updateImuFusionQuality(uint32_t now, bool hasFreshMagCorrection) {
  if (!imuHeadingFilterInitialized) {
    imuFusionQuality = "STALE";
  } else if (!gyroZBiasCalibrated) {
    imuFusionQuality = "GYRO_SETTLING";
  } else if (!accelNormSane()) {
    imuFusionQuality = "ACCEL_UNSTABLE";
  } else if (!gyroYawSane()) {
    imuFusionQuality = "GYRO_SATURATED";
  } else if (!hasFreshMagCorrection && imuIsTurning()) {
    imuFusionQuality = "GYRO_TURNING";
  } else if (hasFreshMagCorrection && imuIsRecoveringFromTurn(now)) {
    imuFusionQuality = "GYRO_RECOVER";
  } else if (!hasFreshMagCorrection) {
    imuFusionQuality = "MAG_DISTURBED";
  } else if (now - imuFusionInitializedMs < IMU_FUSION_SETTLING_MS) {
    imuFusionQuality = "GYRO_SETTLING";
  } else if (!magCalibrationValid) {
    imuFusionQuality = "CAL_REQUIRED";
  } else {
    imuFusionQuality = "OK";
  }
}

void updateImuFusion(float magHeadingDegrees, bool hasNewMagHeading, float dtSeconds, uint32_t now) {
  const float safeDt = clampFloat(dtSeconds, 0.001f, 0.12f);
  bool hasFreshMagCorrection = false;
  if (imuIsTurning()) {
    imuLastTurningMs = now;
  }

  if (!imuHeadingFilterInitialized) {
    if (hasNewMagHeading && magNormSane()) {
      imuMagHeadingDegrees = magHeadingDegrees;
      imuMahonyFilter.begin(IMU_FUSION_SAMPLE_RATE_HZ);
      imuMahonyFilter.setQuaternion(1.0f, 0.0f, 0.0f, 0.0f);
      imuMahonyFilter.update(
        ahrsGyroXDps(),
        ahrsGyroYDps(),
        ahrsGyroZDps(),
        ahrsAccelXG(),
        ahrsAccelYG(),
        ahrsAccelZG(),
        ahrsMagX(),
        ahrsMagY(),
        ahrsMagZ(),
        safeDt
      );
      imuAharsRawYawDegrees = normalizeAngle180(imuMahonyFilter.getYaw());
      imuAharsYawOffsetDegrees = shortestAngleError(magHeadingDegrees, imuAharsRawYawDegrees);
      imuAharsYawOffsetInitialized = true;
      imuHeadingDegrees = normalizeAngle180(imuAharsRawYawDegrees + imuAharsYawOffsetDegrees);
      imuRollDegrees = normalizeBoatTiltDegrees(imuMahonyFilter.getRoll());
      imuPitchDegrees = normalizeBoatTiltDegrees(imuMahonyFilter.getPitch());
      imuHeadingFilterInitialized = true;
      imuFusionInitializedMs = now;
      imuLastMagCorrectionMs = now;
      hasFreshMagCorrection = true;
    }
    updateImuFusionQuality(now, hasFreshMagCorrection);
    return;
  }

  if (hasNewMagHeading) {
    imuMagHeadingDegrees = magHeadingDegrees;
    const bool canUseMagCorrection = magNormSane() && !imuIsTurning();
    if (canUseMagCorrection) {
      imuMahonyFilter.update(
        ahrsGyroXDps(),
        ahrsGyroYDps(),
        ahrsGyroZDps(),
        ahrsAccelXG(),
        ahrsAccelYG(),
        ahrsAccelZG(),
        ahrsMagX(),
        ahrsMagY(),
        ahrsMagZ(),
        safeDt
      );
      imuLastMagCorrectionMs = now;
      hasFreshMagCorrection = true;
    } else {
      imuMahonyFilter.updateIMU(
        ahrsGyroXDps(),
        ahrsGyroYDps(),
        ahrsGyroZDps(),
        ahrsAccelXG(),
        ahrsAccelYG(),
        ahrsAccelZG(),
        safeDt
      );
    }
  } else {
    imuMahonyFilter.updateIMU(
      ahrsGyroXDps(),
      ahrsGyroYDps(),
      ahrsGyroZDps(),
      ahrsAccelXG(),
      ahrsAccelYG(),
      ahrsAccelZG(),
      safeDt
    );
  }

  imuAharsRawYawDegrees = normalizeAngle180(imuMahonyFilter.getYaw());
  if (!imuAharsYawOffsetInitialized && hasNewMagHeading) {
    imuAharsYawOffsetDegrees = shortestAngleError(magHeadingDegrees, imuAharsRawYawDegrees);
    imuAharsYawOffsetInitialized = true;
  } else if (hasFreshMagCorrection) {
    correctAhrsYawOffsetTowardMag(imuMagHeadingDegrees, now);
  }
  const float nextHeadingDegrees = normalizeAngle180(imuAharsRawYawDegrees + imuAharsYawOffsetDegrees);
  updateImuHeadingOutput(nextHeadingDegrees, now, safeDt);
  imuRollDegrees = normalizeBoatTiltDegrees(imuMahonyFilter.getRoll());
  imuPitchDegrees = normalizeBoatTiltDegrees(imuMahonyFilter.getPitch());

  const bool hasRecentMagCorrection = !imuIsTurning() && (now - imuLastMagCorrectionMs < 500);
  updateImuFusionQuality(now, hasFreshMagCorrection || hasRecentMagCorrection);
}

bool calibrateGyroBias() {
  float sum = 0.0f;
  float minValue = 1000000.0f;
  float maxValue = -1000000.0f;
  uint16_t samples = 0;
  for (uint16_t i = 0; i < IMU_GYRO_BIAS_SAMPLES; ++i) {
    float gyroZDps = 0.0f;
    if (readGyroZDps(gyroZDps)) {
      sum += gyroZDps;
      minValue = min(minValue, gyroZDps);
      maxValue = max(maxValue, gyroZDps);
      samples += 1;
    }
    delay(5);
  }
  if (samples < IMU_GYRO_BIAS_SAMPLES * 4 / 5) {
    gyroZBiasDps = 0.0f;
    gyroZBiasCalibrated = false;
    return false;
  }

  const float range = maxValue - minValue;
  if (!isfinite(range) || range > IMU_GYRO_BIAS_STABLE_RANGE_DPS) {
    gyroZBiasDps = 0.0f;
    gyroZBiasCalibrated = false;
    return false;
  }

  gyroZBiasDps = sum / static_cast<float>(samples);
  gyroZBiasCalibrated = true;
  return true;
}

void setupImu() {
  Wire.begin(IMU_SDA_PIN, IMU_SCL_PIN);
  Wire.setTimeOut(I2C_TIMEOUT_MS);
  Wire.setClock(IMU_I2C_CLOCK_HZ);

  if (detectYbImu()) {
    ybImuAvailable = true;
    ybImuFailureCount = 0;
    lastYbImuReadMs = millis();
    imuAvailable = false;
    magnetometerAvailable = false;
    imuFusionQuality = "YB_TELEMETRY_ONLY";
    Serial.print("Yahboom IMU ready addr=0x");
    Serial.print(YB_IMU_ADDR, HEX);
    Serial.println(" mode=TELEMETRY_ONLY");
    return;
  }

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
    Serial.println("ICM20948 detected, but AK09916 magnetometer unavailable; fusion heading disabled");
  } else {
    loadMagCalibration();
  }

  imuAvailable = true;
  imuFailureCount = 0;
  if (!calibrateGyroBias()) {
    Serial.println("Gyro Z bias calibration skipped; keep board still on next boot for better fusion");
  }
  lastImuUpdateMs = millis();
  lastMagHeadingReadMs = 0;
  headingFilterInitialized = false;
  imuHeadingFilterInitialized = false;
  imuAharsYawOffsetInitialized = false;
  imuAharsRawYawDegrees = 0.0f;
  imuAharsYawOffsetDegrees = 0.0f;
  imuRollDegrees = 0.0f;
  imuPitchDegrees = 0.0f;
  imuMagNormReferenceInitialized = false;
  imuFusionInitializedMs = 0;
  imuLastMagCorrectionMs = 0;
  imuMahonyFilter.begin(IMU_FUSION_SAMPLE_RATE_HZ);
  imuFusionQuality = magnetometerAvailable ? "GYRO_SETTLING" : "MAG_UNAVAILABLE";
  Serial.print("ICM20948 ready addr=0x");
  Serial.print(imuAddress, HEX);
  Serial.print(" heading_source=");
  Serial.println(magnetometerAvailable ? "GYRO_MAG_FUSION" : "GYRO_ONLY");
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
  if (!ybImuAvailable && now - lastYbImuDetectAttemptMs >= YB_IMU_REDETECT_INTERVAL_MS) {
    lastYbImuDetectAttemptMs = now;
    if (detectYbImu()) {
      ybImuAvailable = true;
      ybImuFailureCount = 0;
      lastYbImuReadMs = now;
      lastYbImuSampleMs = 0;
      ybHeadingInitialized = false;
      imuAvailable = false;
      magnetometerAvailable = false;
      imuFusionQuality = "YB_TELEMETRY_ONLY";
      Serial.println("Yahboom IMU recovered; telemetry restored");
      SerialBT.println("STATUS;YBIMU=1");
    }
  }
  if (ybImuAvailable && now - lastYbImuReadMs >= MAG_HEADING_INTERVAL_MS) {
    lastYbImuReadMs = now;
    if (ybCalibrationActive) {
      updateYbCalibration(now);
    } else if (readYbImuSample()) {
      ybImuFailureCount = 0;
    } else {
      ybImuFailureCount += 1;
      if (ybImuFailureCount >= IMU_FAILURE_LIMIT) {
        ybImuAvailable = false;
        ybHeadingInitialized = false;
        lastYbImuSampleMs = 0;
        Serial.println("Yahboom IMU read failed; telemetry disabled");
        SerialBT.println("STATUS;FAULT=YB_IMU_READ_FAILED");
      }
    }
  }
  if (!imuAvailable) {
    return;
  }
  if (now - lastMagHeadingReadMs < MAG_HEADING_INTERVAL_MS) {
    return;
  }
  const float dtSeconds = lastImuUpdateMs == 0
    ? static_cast<float>(MAG_HEADING_INTERVAL_MS) / 1000.0f
    : static_cast<float>(now - lastImuUpdateMs) / 1000.0f;
  lastMagHeadingReadMs = now;
  lastImuUpdateMs = now;

  if (!readImuMotionSample()) {
    imuFailureCount += 1;
    if (imuFailureCount >= IMU_FAILURE_LIMIT) {
      imuAvailable = false;
      magnetometerAvailable = false;
      Serial.println("ICM20948 motion read failed; IMU telemetry disabled");
      SerialBT.println("STATUS;WARN=IMU_MOTION_READ_FAILED");
    }
    return;
  }

  if (!magnetometerAvailable) {
    imuFailureCount = 0;
    imuFusionQuality = "MAG_UNAVAILABLE";
    if (imuHeadingFilterInitialized) {
      updateImuFusion(imuMagHeadingDegrees, false, dtSeconds, now);
      headingDegrees = imuHeadingDegrees;
      headingFilterInitialized = true;
    }
    return;
  }

  float nextHeadingDegrees = imuHeadingFilterInitialized ? imuMagHeadingDegrees : headingDegrees;
  bool hasNewHeading = false;
  if (!readMagHeadingDegrees(nextHeadingDegrees, hasNewHeading)) {
    imuFailureCount += 1;
    if (imuFailureCount >= IMU_FAILURE_LIMIT) {
      magnetometerAvailable = false;
      Serial.println("IMU magnetometer read failed; ESP32 magnetic heading disabled");
      SerialBT.println("STATUS;WARN=IMU_MAG_READ_FAILED");
    }
    return;
  }

  imuFailureCount = 0;
  updateImuFusion(nextHeadingDegrees, hasNewHeading, dtSeconds, now);
  if (imuHeadingFilterInitialized) {
    headingDegrees = imuHeadingDegrees;
    headingFilterInitialized = true;
  }
}

// Installation-only ESC direction correction. Keep this out of control loops.
int8_t applyEscDirectionAtOutputLayer(int8_t percent, bool reversed) {
  return reversed ? static_cast<int8_t>(-percent) : percent;
}

int8_t leftSemanticPercentToEscPercent(int8_t percent) {
  return applyEscDirectionAtOutputLayer(percent, escLeftReversed);
}

int8_t rightSemanticPercentToEscPercent(int8_t percent) {
  return applyEscDirectionAtOutputLayer(percent, escRightReversed);
}

void cancelTurnControl() {
  turnControlActive = false;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  reportedLeftPercent = 0;
  reportedRightPercent = 0;
  lastCommandMode = CommandMode::Throttle;
}

void resetHeadingLockRuntimeState() {
  lastHeadingLockErrorDegrees = 0.0f;
  lastHeadingLockRateDegS = 0.0f;
  lastHeadingLockTargetRateDegS = 0.0f;
  lastHeadingLockRateErrorDegS = 0.0f;
  lastHeadingLockRateDotDegS2 = 0.0f;
  lastHeadingLockPredictedErrorDegrees = 0.0f;
  lastHeadingLockPdPercent = 0.0f;
  lastHeadingLockInnerPercent = 0.0f;
  headingLockDisturbancePercent = 0.0f;
  headingLockAdaptiveBoostFloatPercent = 0.0f;
  lastHeadingLockBrakePercent = 0.0f;
  headingLockAdaptiveBoostPercent = 0;
  lastHeadingLockCorrectionPercent = 0;
  headingLockDivergenceWarningActive = false;
  headingLockHeadingUnavailableWarningActive = false;
  headingLockPhase = HeadingLockPhase::Correct;
  headingLockBrakeStartedMs = 0;
  headingLockBrakeHoldTargetMs = 0;
  headingLockBrakeStartRateSign = 0.0f;
  previousHeadingLockHeadingDegrees = ybHeadingInitialized ? currentYbHeadingDegrees() : headingDegrees;
  previousHeadingLockRateMs = millis();
  previousHeadingLockRateValid = false;
  previousHeadingLockRateDegS = 0.0f;
  previousHeadingLockRateDotValid = false;
  headingLockDivergenceStartedMs = 0;
  headingLockDivergenceStartAbsError = 0.0f;
  headingLockDivergenceErrorSign = 0.0f;
  lastHeadingLockControlMs = 0;
}

void cancelHeadingLockControl() {
  headingLockActive = false;
  resetHeadingLockRuntimeState();
  lastCommandMode = CommandMode::Throttle;
}

void cancelAutonomousControl() {
  cancelTurnControl();
  cancelHeadingLockControl();
}

void cancelHeadingLockFaultKeepArmed(const char* faultCode) {
  cancelHeadingLockControl();
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  reportedLeftPercent = 0;
  reportedRightPercent = 0;
  lastCommandMode = CommandMode::Throttle;
  SerialBT.print("STATUS;ARMED=");
  SerialBT.print(armed ? 1 : 0);
  SerialBT.print(";FAULT=");
  SerialBT.print(faultCode);
  SerialBT.println(";HLOCK=OFF");
}

void forceNeutralAndDisarm() {
  armed = false;
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  reportedLeftPercent = 0;
  reportedRightPercent = 0;
  lastCommandSource = CommandSource::App;
  lastCommandMode = CommandMode::Throttle;
  turnControlActive = false;
  turnControlCompleted = false;
  headingLockActive = false;
  resetHeadingLockRuntimeState();
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

void printYbCalibrationStatus(Print& response) {
  response.print("YB_CAL;YBCIMU=");
  response.print(ybImuCalibrationState);
  response.print(";YBCMAG=");
  response.println(ybMagCalibrationState);
}

bool applyYbCalibrationLine(char* line, Print& response) {
  if (strncmp(line, "YB_CAL", 6) != 0) {
    return false;
  }

  bool sawAction = false;
  bool startRequested = false;
  bool clearRequested = false;
  bool statusRequested = false;
  bool sawTarget = false;
  uint8_t targetRegister = 0;
  bool badToken = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "YB_CAL") == 0) {
      // Header marker.
    } else if (strcmp(token, "ACTION=START") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      startRequested = true;
    } else if (strcmp(token, "ACTION=CLEAR") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      clearRequested = true;
    } else if (strcmp(token, "ACTION=STATUS") == 0) {
      badToken = badToken || sawAction;
      sawAction = true;
      statusRequested = true;
    } else if (strcmp(token, "TARGET=IMU") == 0) {
      badToken = badToken || sawTarget;
      sawTarget = true;
      targetRegister = YB_IMU_CALIB_IMU;
    } else if (strcmp(token, "TARGET=MAG") == 0) {
      badToken = badToken || sawTarget;
      sawTarget = true;
      targetRegister = YB_IMU_CALIB_MAG;
    } else if (strncmp(token, "ACTION=", 7) == 0 || strncmp(token, "TARGET=", 7) == 0) {
      badToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawAction || badToken || (!statusRequested && !sawTarget)) {
    response.println("ERR;BAD_YB_CAL_COMMAND");
    return true;
  }

  if (statusRequested) {
    printYbCalibrationStatus(response);
    return true;
  }

  forceNeutralAndDisarm();
  lastValidBtCommandMs = 0;

  if (!ybImuAvailable && !detectYbImu()) {
    response.println("ERR;YB_IMU_UNAVAILABLE");
    return true;
  }
  ybImuAvailable = true;

  const uint8_t value = startRequested ? 1 : 0;
  if (!ybImuWriteByte(targetRegister, value)) {
    response.println("ERR;YB_CAL_WRITE_FAILED");
    return true;
  }

  if (startRequested) {
    beginYbCalibration(targetRegister);
  } else if (clearRequested) {
    if (targetRegister == YB_IMU_CALIB_IMU) {
      ybImuCalibrationState = 0;
    } else if (targetRegister == YB_IMU_CALIB_MAG) {
      ybMagCalibrationState = 0;
    }
    if (ybCalibrationTargetRegister == targetRegister) {
      finishYbCalibration(0);
    }
  }

  response.println("STATUS;ARMED=0;L=0;R=0;HLOCK=OFF");
  printYbCalibrationStatus(response);
  Serial.print("Yahboom calibration command target=0x");
  Serial.print(targetRegister, HEX);
  Serial.print(" value=");
  Serial.println(value);
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
  if (strcmp(token, "MODE=KEEPALIVE") == 0) {
    outMode = CommandMode::KeepAlive;
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

bool parseHeadingSourceToken(const char* token) {
  if (strcmp(token, "H_SRC=IMU") == 0) {
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

bool applyEscConfigLine(char* line, Print& response) {
  if (strncmp(line, "ESC_CFG", 7) != 0) {
    return false;
  }

  bool nextLeftReversed = escLeftReversed;
  bool nextRightReversed = escRightReversed;
  bool sawLeft = false;
  bool sawRight = false;
  bool badToken = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "ESC_CFG") == 0) {
      // command prefix
    } else if (parseBoolToken(token, "LREV=", nextLeftReversed)) {
      sawLeft = true;
    } else if (parseBoolToken(token, "RREV=", nextRightReversed)) {
      sawRight = true;
    } else {
      badToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (badToken || (!sawLeft && !sawRight)) {
    response.println("ESC_CFG;ERR=BAD_COMMAND");
    return true;
  }

  const bool saved = saveEscDirectionConfig(nextLeftReversed, nextRightReversed);
  response.print("ESC_CFG;OK;LREV=");
  response.print(escLeftReversed ? 1 : 0);
  response.print(";RREV=");
  response.print(escRightReversed ? 1 : 0);
  response.print(";NVS=");
  response.println(saved ? 1 : 0);
  return true;
}

uint32_t updateCrc32(uint32_t crc, const uint8_t* data, size_t length) {
  for (size_t i = 0; i < length; ++i) {
    crc ^= data[i];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      const uint32_t mask = static_cast<uint32_t>(-(static_cast<int32_t>(crc & 1)));
      crc = (crc >> 1) ^ (0xEDB88320UL & mask);
    }
  }
  return crc;
}

uint32_t finishCrc32(uint32_t crc) {
  return crc ^ 0xFFFFFFFFUL;
}

void resetOtaTransferState() {
  otaInProgress = false;
  otaChunkedProtocol = false;
  otaChunkReceivingData = false;
  otaExpectedBytes = 0;
  otaWrittenBytes = 0;
  otaChunkSize = OTA_MAX_CHUNK_SIZE;
  otaChunkOffset = 0;
  otaChunkLength = 0;
  otaChunkReceived = 0;
  otaChunkExpectedCrc = 0;
  otaChunkRunningCrc = 0;
  otaLineLen = 0;
  otaLastDataMs = 0;
  otaLastProgressMs = 0;
  otaLastProgressBytes = 0;
}

void abortOtaWithError(const char* errorCode) {
  Update.abort();
  resetOtaTransferState();
  forceNeutralAndDisarm();
  SerialBT.print("OTA;ERR=");
  SerialBT.println(errorCode);
  Serial.print("OTA aborted: ");
  Serial.println(errorCode);
}

void printOtaNack(size_t offset, const char* errorCode) {
  SerialBT.print("OTA;NACK;OFFSET=");
  SerialBT.print(offset);
  SerialBT.print(";ERR=");
  SerialBT.println(errorCode);
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

void printControllerInfo(Print& output) {
  output.print("INFO;FW=");
  output.print(FIRMWARE_VERSION);
  output.print(";ID=");
  printPaddedUnitId(output, unitId);
  output.print(";BT=");
  output.print(btDeviceName);
  output.print(";ID_SRC=");
  output.print(unitIdSource);
  output.print(";PROVISIONED=");
  output.print(unitIdProvisioned ? 1 : 0);
  output.println(";OTA=1");
}

bool applyIdentityLine(char* line, Print& response) {
  if (strcmp(line, "ID?") == 0 || strcmp(line, "ID") == 0) {
    printIdentity(response);
    return true;
  }

  if (strcmp(line, "INFO?") == 0 || strcmp(line, "INFO") == 0) {
    printControllerInfo(response);
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
  bool requestChunkedProtocol = false;
  size_t nextSize = 0;
  uint16_t nextChunkSize = OTA_MAX_CHUNK_SIZE;
  char nextMd5[33] = {};

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "OTA_BEGIN") == 0) {
      // Header marker.
    } else if (parseSizeToken(token, "SIZE=", nextSize)) {
      sawSize = true;
    } else if (parseMd5Token(token, "MD5=", nextMd5, sizeof(nextMd5))) {
      sawMd5 = true;
    } else if (strcmp(token, "PROTO=2") == 0) {
      requestChunkedProtocol = true;
    } else if (parseUnsignedToken(token, "CHUNK=", 128, OTA_MAX_CHUNK_SIZE, nextChunkSize)) {
      // Optional requested chunk size for protocol 2.
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
  otaChunkedProtocol = requestChunkedProtocol;
  otaChunkReceivingData = false;
  otaExpectedBytes = nextSize;
  otaWrittenBytes = 0;
  otaChunkSize = nextChunkSize;
  otaChunkOffset = 0;
  otaChunkLength = 0;
  otaChunkReceived = 0;
  otaChunkExpectedCrc = 0;
  otaChunkRunningCrc = 0;
  otaLastDataMs = millis();
  otaLastProgressMs = otaLastDataMs;
  otaLastProgressBytes = 0;
  otaLineLen = 0;
  btRxLen = 0;

  Serial.print("OTA begin size=");
  Serial.println(otaExpectedBytes);
  SerialBT.print("OTA;READY;SIZE=");
  SerialBT.print(otaExpectedBytes);
  if (otaChunkedProtocol) {
    SerialBT.print(";PROTO=2;CHUNK=");
    SerialBT.print(otaChunkSize);
  }
  SerialBT.println();
}

void finishOta() {
  if (!Update.end(true)) {
    SerialBT.print("OTA;ERR=END_FAILED;CODE=");
    SerialBT.println(Update.getError());
    Serial.print("OTA end failed code=");
    Serial.println(Update.getError());
    resetOtaTransferState();
    return;
  }

  const size_t completedBytes = otaWrittenBytes;
  resetOtaTransferState();
  for (uint8_t i = 0; i < 3; ++i) {
    SerialBT.print("OTA;OK;BYTES=");
    SerialBT.println(completedBytes);
    delay(150);
  }
  Serial.println("OTA complete; rebooting");
  delay(700);
  ESP.restart();
}

void processOtaRawBytes() {
  uint8_t buffer[OTA_BUFFER_SIZE];
  while (otaInProgress && otaWrittenBytes < otaExpectedBytes) {
    const int availableBytes = SerialBT.available();
    if (availableBytes <= 0) {
      break;
    }
    const size_t remaining = otaExpectedBytes - otaWrittenBytes;
    const size_t targetBytes = min(remaining, sizeof(buffer));
    size_t readBytes = 0;
    const uint32_t readStartMs = millis();
    while (readBytes < targetBytes) {
      const int nextByte = SerialBT.read();
      if (nextByte >= 0) {
        buffer[readBytes++] = static_cast<uint8_t>(nextByte);
        continue;
      }
      if (millis() - readStartMs >= OTA_READ_COALESCE_MS) {
        break;
      }
      delay(1);
    }
    if (readBytes == 0) {
      break;
    }

    const size_t written = Update.write(buffer, readBytes);
    otaWrittenBytes += written;
    otaLastDataMs = millis();

    if (written != readBytes) {
      const uint8_t error = Update.getError();
      resetOtaTransferState();
      forceNeutralAndDisarm();
      SerialBT.print("OTA;ERR=WRITE_FAILED;CODE=");
      SerialBT.println(error);
      Serial.println("OTA write failed");
      return;
    }

    const uint32_t now = millis();
    if (now - otaLastProgressMs >= 1000 ||
        otaWrittenBytes - otaLastProgressBytes >= OTA_PROGRESS_INTERVAL_BYTES ||
        otaWrittenBytes == otaExpectedBytes) {
      otaLastProgressMs = now;
      otaLastProgressBytes = otaWrittenBytes;
      SerialBT.print("OTA;PROGRESS=");
      SerialBT.print(otaWrittenBytes);
      SerialBT.print("/");
      SerialBT.println(otaExpectedBytes);
    }
  }

  if (otaInProgress && otaWrittenBytes == otaExpectedBytes) {
    finishOta();
  }
}

void beginOtaChunk(char* line) {
  bool sawOffset = false;
  bool sawLength = false;
  bool sawCrc = false;
  uint32_t nextOffset = 0;
  uint32_t nextLength = 0;
  uint32_t nextCrc = 0;
  bool badToken = false;

  char* token = strtok(line, ";");
  while (token != nullptr) {
    if (strcmp(token, "OTA_CHUNK") == 0) {
      // Header marker.
    } else if (parseUint32Token(token, "OFFSET=", 0, 0xFFFFFFFFUL, nextOffset)) {
      sawOffset = true;
    } else if (parseUint32Token(token, "LEN=", 1, OTA_MAX_CHUNK_SIZE, nextLength)) {
      sawLength = true;
    } else if (parseUint32Token(token, "CRC=", 0, 0xFFFFFFFFUL, nextCrc)) {
      sawCrc = true;
    } else {
      badToken = true;
    }
    token = strtok(nullptr, ";");
  }

  if (!sawOffset || !sawLength || !sawCrc || badToken) {
    printOtaNack(otaWrittenBytes, "BAD_CHUNK_HEADER");
    return;
  }
  if (nextOffset != otaWrittenBytes) {
    printOtaNack(otaWrittenBytes, "BAD_CHUNK_OFFSET");
    return;
  }
  if (nextLength > otaChunkSize || nextLength > otaExpectedBytes - otaWrittenBytes) {
    printOtaNack(otaWrittenBytes, "BAD_CHUNK_LENGTH");
    return;
  }

  otaChunkOffset = nextOffset;
  otaChunkLength = nextLength;
  otaChunkReceived = 0;
  otaChunkExpectedCrc = nextCrc;
  otaChunkRunningCrc = 0xFFFFFFFFUL;
  otaChunkReceivingData = true;
  otaLastDataMs = millis();
}

void commitOtaChunk() {
  const uint32_t actualCrc = finishCrc32(otaChunkRunningCrc);
  if (actualCrc != otaChunkExpectedCrc) {
    otaChunkReceivingData = false;
    otaChunkReceived = 0;
    printOtaNack(otaWrittenBytes, "BAD_CHUNK_CRC");
    return;
  }

  const size_t written = Update.write(otaChunkBuffer, otaChunkLength);
  if (written != otaChunkLength) {
    const uint8_t error = Update.getError();
    resetOtaTransferState();
    forceNeutralAndDisarm();
    SerialBT.print("OTA;ERR=WRITE_FAILED;CODE=");
    SerialBT.println(error);
    Serial.println("OTA chunk write failed");
    return;
  }

  otaWrittenBytes += written;
  otaLastDataMs = millis();
  otaChunkReceivingData = false;
  otaChunkOffset = 0;
  otaChunkLength = 0;
  otaChunkReceived = 0;

  SerialBT.print("OTA;ACK;OFFSET=");
  SerialBT.print(otaWrittenBytes);
  SerialBT.print(";CRC=");
  SerialBT.println(actualCrc);

  const uint32_t now = millis();
  if (now - otaLastProgressMs >= 1000 ||
      otaWrittenBytes - otaLastProgressBytes >= OTA_PROGRESS_INTERVAL_BYTES ||
      otaWrittenBytes == otaExpectedBytes) {
    otaLastProgressMs = now;
    otaLastProgressBytes = otaWrittenBytes;
    SerialBT.print("OTA;PROGRESS=");
    SerialBT.print(otaWrittenBytes);
    SerialBT.print("/");
    SerialBT.println(otaExpectedBytes);
  }

  if (otaWrittenBytes == otaExpectedBytes) {
    finishOta();
  }
}

void processOtaChunkedBytes() {
  while (otaInProgress && otaChunkedProtocol && SerialBT.available() > 0) {
    if (!otaChunkReceivingData) {
      const int nextByte = SerialBT.read();
      if (nextByte < 0) {
        break;
      }
      const char next = static_cast<char>(nextByte);
      if (next == '\r') {
        continue;
      }
      if (next == '\n') {
        otaLineBuffer[otaLineLen] = '\0';
        if (otaLineLen > 0) {
          beginOtaChunk(otaLineBuffer);
          if (!otaInProgress) {
            return;
          }
        }
        otaLineLen = 0;
        continue;
      }
      if (otaLineLen < sizeof(otaLineBuffer) - 1) {
        otaLineBuffer[otaLineLen++] = next;
      } else {
        abortOtaWithError("CHUNK_HEADER_TOO_LONG");
        return;
      }
      continue;
    }

    const int availableBytes = SerialBT.available();
    if (availableBytes <= 0) {
      break;
    }
    const size_t remaining = otaChunkLength - otaChunkReceived;
    const size_t readTarget = min(remaining, static_cast<size_t>(availableBytes));
    const size_t crcStart = otaChunkReceived;
    size_t actualRead = 0;
    for (size_t i = 0; i < readTarget; ++i) {
      const int nextByte = SerialBT.read();
      if (nextByte < 0) {
        break;
      }
      otaChunkBuffer[otaChunkReceived++] = static_cast<uint8_t>(nextByte);
      actualRead += 1;
    }
    if (actualRead == 0) {
      break;
    }
    otaChunkRunningCrc = updateCrc32(
      otaChunkRunningCrc,
      otaChunkBuffer + crcStart,
      actualRead
    );
    otaLastDataMs = millis();

    if (otaChunkReceived == otaChunkLength) {
      commitOtaChunk();
    }
  }
}

void processOtaBytes() {
  if (otaChunkedProtocol) {
    processOtaChunkedBytes();
  } else {
    processOtaRawBytes();
  }
}

void handleOtaTimeout(uint32_t now) {
  if (!otaInProgress || now - otaLastDataMs <= OTA_TIMEOUT_MS) {
    return;
  }

  abortOtaWithError("TIMEOUT");
}

void applyTurnAngleCommand(
  TurnDirection direction,
  uint16_t angleDegrees,
  uint16_t requestId,
  uint8_t voicePowerLimitPercent
) {
  const uint32_t now = millis();
  if (!hasUsableFirmwareHeading(now)) {
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
    return;
  }

  const float signedAngle = direction == TurnDirection::Left
    ? -static_cast<float>(angleDegrees)
    : static_cast<float>(angleDegrees);

  if (headingLockActive && !turnControlActive) {
    turnTargetHeadingDegrees = normalizeCompass360(headingLockTargetDegrees + signedAngle);
    headingLockTargetDegrees = turnTargetHeadingDegrees;
    activeTurnRequestId = requestId;
    completedTurnRequestId = requestId;
    turnControlCompleted = true;
    activeVoicePowerLimitPercent = constrain(
      static_cast<int>(voicePowerLimitPercent),
      static_cast<int>(MIN_VOICE_POWER_LIMIT_PERCENT),
      static_cast<int>(MAX_VOICE_POWER_LIMIT_PERCENT)
    );
    resetHeadingLockRuntimeState();
    Serial.print("Turn angle applied to heading lock tid=");
    Serial.print(requestId);
    Serial.print(" target=");
    Serial.println(headingLockTargetDegrees, 1);
    SerialBT.print("STATUS;TURN=APPLIED;TID=");
    SerialBT.print(requestId);
    SerialBT.print(";HLOCK=ACTIVE;HID=");
    SerialBT.print(activeHeadingLockRequestId);
    SerialBT.print(";TARGET=");
    SerialBT.println(headingLockTargetDegrees, 1);
    return;
  }

  turnTargetHeadingDegrees = normalizeCompass360(currentYbHeadingDegrees() + signedAngle);
  turnStartedMs = now;
  activeTurnRequestId = requestId;
  turnControlActive = true;
  turnControlCompleted = false;
  headingLockActive = true;
  activeHeadingLockRequestId = requestId;
  headingLockTargetDegrees = turnTargetHeadingDegrees;
  headingLockBasePercent = 0;
  headingLockToleranceDegrees = DEFAULT_HEADING_LOCK_TOLERANCE_DEGREES;
  headingLockFullCorrectionDegrees = DEFAULT_HEADING_LOCK_FULL_CORRECTION_DEGREES;
  headingLockNeutralReversePercent = DEFAULT_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT;
  headingLockSource = CommandSource::Voice;
  activeVoicePowerLimitPercent = constrain(
    static_cast<int>(voicePowerLimitPercent),
    static_cast<int>(MIN_VOICE_POWER_LIMIT_PERCENT),
    static_cast<int>(MAX_VOICE_POWER_LIMIT_PERCENT)
  );
  requestedLeftPercent = 0;
  requestedRightPercent = 0;
  reportedLeftPercent = 0;
  reportedRightPercent = 0;
  resetHeadingLockRuntimeState();

  Serial.print("Turn angle start tid=");
  Serial.print(requestId);
  Serial.print(" dir=");
  Serial.print(direction == TurnDirection::Left ? "LEFT" : "RIGHT");
  Serial.print(" angle=");
  Serial.print(angleDegrees);
  Serial.print(" current=");
  Serial.print(currentYbHeadingDegrees(), 1);
  Serial.print(" target=");
  Serial.println(turnTargetHeadingDegrees, 1);
  SerialBT.print("STATUS;TURN=START;TID=");
  SerialBT.print(requestId);
  SerialBT.print(";TARGET=");
  SerialBT.println(turnTargetHeadingDegrees, 1);
}

void applyHeadingLockCommand(
  bool enabled,
  int8_t basePercent,
  uint16_t toleranceDegrees,
  uint16_t fullCorrectionDegrees,
  uint8_t neutralReversePercent,
  uint8_t voicePowerLimitPercent,
  uint16_t requestId,
  bool hasTargetHeading,
  float targetHeadingDegrees,
  CommandSource source
) {
  const uint32_t now = millis();
  lastValidBtCommandMs = now;
  lastCommandSource = source;

  if (!enabled) {
    cancelHeadingLockControl();
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    reportedLeftPercent = 0;
    reportedRightPercent = 0;
    Serial.println("Heading lock cancelled by command");
    SerialBT.println("STATUS;HLOCK=OFF");
    return;
  }

  if (!hasUsableFirmwareHeading(now)) {
    SerialBT.print("ERR;YB_HEADING_UNAVAILABLE;YBIMU=");
    SerialBT.print(ybImuAvailable ? 1 : 0);
    SerialBT.print(";YBINIT=");
    SerialBT.print(ybHeadingInitialized ? 1 : 0);
    SerialBT.print(";YBAGE=");
    SerialBT.print(ybHeadingAgeMs(now));
    SerialBT.print(";HSRC=");
    SerialBT.print(activeHeadingSourceName(now));
    if (ybHeadingInitialized) {
      SerialBT.print(";YBHDG=");
      SerialBT.print(currentYbHeadingDegrees(), 1);
    }
    SerialBT.println();
    Serial.println("Heading lock rejected: Yahboom heading unavailable");
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
  headingLockNeutralReversePercent = HEADING_LOCK_REVERSE_MAX_OUTPUT_PERCENT;

  const bool sameActiveHeadingLockRequest = headingLockActive && requestId == activeHeadingLockRequestId;
  if (sameActiveHeadingLockRequest && !hasTargetHeading) {
    return;
  }

  activeHeadingLockRequestId = requestId;
  if (hasTargetHeading) {
    headingLockTargetDegrees = normalizeCompass360(targetHeadingDegrees);
  } else if (!headingLockActive) {
    headingLockTargetDegrees = currentYbHeadingDegrees();
  }
  if (!sameActiveHeadingLockRequest) {
    resetHeadingLockRuntimeState();
  }
  headingLockActive = true;

  Serial.print("Heading lock start hid=");
  Serial.print(requestId);
  Serial.print(" base=");
  Serial.print(headingLockBasePercent);
  Serial.print(" target=");
  Serial.print(headingLockTargetDegrees, 1);
  if (hasTargetHeading) {
    Serial.print(" explicit_target=1");
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
  if (hasTargetHeading) {
    SerialBT.print(";TARGET_SRC=CMD");
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

  const int8_t previousCorrectionPercent = lastHeadingLockCorrectionPercent;
  const bool freshCommand = lastValidBtCommandMs != 0 && now - lastValidBtCommandMs <= BT_COMMAND_TIMEOUT_MS;
  if (!armed || !freshCommand) {
    cancelHeadingLockControl();
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    reportedLeftPercent = 0;
    reportedRightPercent = 0;
    return;
  }

  if (lastHeadingLockControlMs != 0 && now - lastHeadingLockControlMs < CONTROL_TICK_MS) {
    return;
  }
  lastHeadingLockControlMs = now;

  if (!hasUsableFirmwareHeading(now)) {
    headingLockHeadingUnavailableWarningActive = true;
    requestedLeftPercent = 0;
    requestedRightPercent = 0;
    reportedLeftPercent = 0;
    reportedRightPercent = 0;
    lastHeadingLockCorrectionPercent = 0;
    lastHeadingLockPdPercent = 0.0f;
    lastHeadingLockTargetRateDegS = 0.0f;
    lastHeadingLockRateErrorDegS = 0.0f;
    lastHeadingLockRateDotDegS2 = 0.0f;
    lastHeadingLockInnerPercent = 0.0f;
    headingLockDisturbancePercent *= HEADING_LOCK_OBSERVER_DECAY;
    headingLockAdaptiveBoostFloatPercent *= HEADING_LOCK_ADAPTIVE_BOOST_DECAY;
    if (fabs(headingLockAdaptiveBoostFloatPercent) < 0.1f) {
      headingLockAdaptiveBoostFloatPercent = 0.0f;
    }
    lastHeadingLockBrakePercent = 0.0f;
    headingLockAdaptiveBoostPercent = static_cast<int8_t>(roundf(headingLockAdaptiveBoostFloatPercent));
    Serial.println("Heading lock waiting: Yahboom heading unavailable; keeping heading lock active");
    return;
  }
  headingLockHeadingUnavailableWarningActive = false;

  const float currentHeadingDegrees = currentYbHeadingDegrees();
  const float errorDegrees = shortestAngleError(headingLockTargetDegrees, currentHeadingDegrees);
  lastHeadingLockErrorDegrees = errorDegrees;
  const float absError = fabs(errorDegrees);
  const float errorSign = signOrZero(errorDegrees);
  const float toleranceDegrees = static_cast<float>(headingLockToleranceDegrees);
  const float quietHoldToleranceDegrees =
    toleranceDegrees + HEADING_LOCK_QUIET_HOLD_MARGIN_DEGREES;
  const bool inQuietHoldBand = absError <= quietHoldToleranceDegrees;

  if (turnControlActive) {
    lastTurnErrorDegrees = errorDegrees;
    if (now - turnStartedMs > TURN_CONTROL_TIMEOUT_MS) {
      const uint16_t timedOutRequestId = activeTurnRequestId;
      cancelHeadingLockControl();
      turnControlActive = false;
      turnControlCompleted = true;
      completedTurnRequestId = timedOutRequestId;
      requestedLeftPercent = 0;
      requestedRightPercent = 0;
      reportedLeftPercent = 0;
      reportedRightPercent = 0;
      SerialBT.print("STATUS;TURN=TIMEOUT;TID=");
      SerialBT.print(timedOutRequestId);
      SerialBT.print(";ERR=");
      SerialBT.println(errorDegrees, 1);
      return;
    }
    if (absError <= TURN_DONE_DEGREES) {
      const uint16_t doneRequestId = activeTurnRequestId;
      cancelHeadingLockControl();
      turnControlActive = false;
      turnControlCompleted = true;
      completedTurnRequestId = doneRequestId;
      requestedLeftPercent = 0;
      requestedRightPercent = 0;
      reportedLeftPercent = 0;
      reportedRightPercent = 0;
      SerialBT.print("STATUS;TURN=DONE;TID=");
      SerialBT.print(doneRequestId);
      SerialBT.print(";ERR=");
      SerialBT.println(errorDegrees, 1);
      return;
    }
  }

  float headingRateDegS = currentYbHeadingRateDegS();
  if (!isfinite(headingRateDegS)) {
    headingRateDegS = 0.0f;
  }
  float headingDtSeconds = 0.0f;
  if (previousHeadingLockRateValid && previousHeadingLockRateMs != now) {
    headingDtSeconds = static_cast<float>(now - previousHeadingLockRateMs) / 1000.0f;
    if (headingDtSeconds > 0.0f && headingDtSeconds <= 0.2f) {
      const float diffRateDegS = shortestAngleError(currentHeadingDegrees, previousHeadingLockHeadingDegrees) / headingDtSeconds;
      if (isfinite(diffRateDegS) && fabs(headingRateDegS) < 0.5f) {
        headingRateDegS = diffRateDegS;
      }
    }
  }
  float rateDotDegS2 = 0.0f;
  if (previousHeadingLockRateDotValid && headingDtSeconds > 0.0f && headingDtSeconds <= 0.2f) {
    const float observedRateDotDegS2 = (headingRateDegS - previousHeadingLockRateDegS) / headingDtSeconds;
    if (isfinite(observedRateDotDegS2) && fabs(observedRateDotDegS2) <= HEADING_LOCK_MAX_RATE_DOT_DEG_S2) {
      rateDotDegS2 = observedRateDotDegS2;
    }
  }
  previousHeadingLockHeadingDegrees = currentHeadingDegrees;
  previousHeadingLockRateMs = now;
  previousHeadingLockRateValid = true;
  previousHeadingLockRateDegS = headingRateDegS;
  previousHeadingLockRateDotValid = true;
  lastHeadingLockRateDegS = headingRateDegS;
  lastHeadingLockRateDotDegS2 = rateDotDegS2;
  const float absRate = fabs(headingRateDegS);
  const float rateSign = signOrZero(headingRateDegS);
  const bool movingAwayFromTarget =
    errorSign != 0.0f &&
    rateSign != 0.0f &&
    rateSign != errorSign &&
    absRate >= HEADING_LOCK_DIVERGENCE_MIN_AWAY_RATE_DEG_S;

  const float fullCorrectionDegrees = max(
    static_cast<float>(headingLockFullCorrectionDegrees),
    toleranceDegrees + 1.0f
  );
  const float activeRange = fullCorrectionDegrees - toleranceDegrees;
  const float targetRateGain = activeRange > 0.0f
    ? HEADING_LOCK_MAX_TARGET_RATE_DEG_S / activeRange
    : 0.0f;
  const float targetRateDegS = inQuietHoldBand
    ? 0.0f
    : constrain(errorDegrees * targetRateGain, -HEADING_LOCK_MAX_TARGET_RATE_DEG_S, HEADING_LOCK_MAX_TARGET_RATE_DEG_S);
  const float rateErrorDegS = targetRateDegS - headingRateDegS;
  const float innerPercent =
    HEADING_LOCK_RATE_KP_PERCENT_PER_DEGREE_S * rateErrorDegS -
    HEADING_LOCK_ACCEL_DAMP_PERCENT_PER_DEGREE_S2 * rateDotDegS2;
  lastHeadingLockTargetRateDegS = targetRateDegS;
  lastHeadingLockRateErrorDegS = rateErrorDegS;
  lastHeadingLockInnerPercent = innerPercent;
  lastHeadingLockPdPercent = innerPercent;

  if (inQuietHoldBand) {
    headingLockDivergenceWarningActive = false;
    headingLockDivergenceStartedMs = 0;
    headingLockDivergenceStartAbsError = 0.0f;
    headingLockDivergenceErrorSign = 0.0f;
  } else if (!movingAwayFromTarget) {
    if (headingLockDivergenceWarningActive) {
      Serial.println("Heading lock divergence cleared; resuming correction");
    }
    headingLockDivergenceWarningActive = false;
    headingLockDivergenceStartedMs = now;
    headingLockDivergenceStartAbsError = absError;
    headingLockDivergenceErrorSign = errorSign;
  } else if (
    headingLockDivergenceStartedMs == 0 ||
    headingLockDivergenceErrorSign != errorSign
  ) {
    headingLockDivergenceStartedMs = now;
    headingLockDivergenceStartAbsError = absError;
    headingLockDivergenceErrorSign = errorSign;
  } else if (
    now - headingLockDivergenceStartedMs >= HEADING_LOCK_DIVERGENCE_WINDOW_MS &&
    absError >= headingLockDivergenceStartAbsError + HEADING_LOCK_DIVERGENCE_DEGREES
  ) {
    headingLockDivergenceWarningActive = true;
    headingLockDivergenceStartedMs = now;
    headingLockDivergenceStartAbsError = absError;
    Serial.println("Heading lock diverged; freezing correction and continuing heading lock");
  }

  const float pdPercent = innerPercent;

  const float predictedErrorDegrees = errorDegrees - headingRateDegS * HEADING_LOCK_LOOKAHEAD_SECONDS;
  lastHeadingLockPredictedErrorDegrees = predictedErrorDegrees;
  const float predictedSign = signOrZero(predictedErrorDegrees);
  const bool movingTowardTarget = errorSign != 0.0f && errorSign == rateSign;
  const bool willOvershoot = movingTowardTarget && predictedSign != 0.0f && predictedSign != errorSign;
  const bool nearStopLine = fabs(predictedErrorDegrees) <= HEADING_LOCK_BRAKE_WINDOW_DEGREES;
  const bool needBrake = movingTowardTarget &&
    absRate >= HEADING_LOCK_MIN_BRAKE_RATE_DEG_S &&
    (willOvershoot || nearStopLine);

  const float brakeRateRange = HEADING_LOCK_FULL_BRAKE_RATE_DEG_S - HEADING_LOCK_MIN_BRAKE_RATE_DEG_S;
  const float rateRatio = constrain(
    brakeRateRange > 0.0f
      ? (absRate - HEADING_LOCK_MIN_BRAKE_RATE_DEG_S) / brakeRateRange
      : 1.0f,
    0.0f,
    1.0f
  );
  const float brakePercent = static_cast<float>(HEADING_LOCK_MIN_BRAKE_PERCENT) +
    static_cast<float>(HEADING_LOCK_MAX_BRAKE_PERCENT - HEADING_LOCK_MIN_BRAKE_PERCENT) * rateRatio;
  const uint16_t brakeHoldTargetMs = static_cast<uint16_t>(
    roundf(static_cast<float>(HEADING_LOCK_MIN_BRAKE_HOLD_MS) +
      static_cast<float>(HEADING_LOCK_MAX_BRAKE_HOLD_MS - HEADING_LOCK_MIN_BRAKE_HOLD_MS) * rateRatio)
  );

  if (needBrake && headingLockPhase != HeadingLockPhase::Brake) {
    headingLockPhase = HeadingLockPhase::Brake;
    headingLockBrakeStartedMs = now;
    headingLockBrakeStartRateSign = rateSign;
    headingLockBrakeHoldTargetMs = brakeHoldTargetMs;
  }

  bool brakeActive = headingLockPhase == HeadingLockPhase::Brake;
  if (brakeActive) {
    headingLockBrakeHoldTargetMs = brakeHoldTargetMs;
    const uint32_t brakeElapsedMs = now - headingLockBrakeStartedMs;
    const bool rateReversed = rateSign == 0.0f || rateSign != headingLockBrakeStartRateSign;
    const bool settled = absRate <= HEADING_LOCK_SETTLE_RATE_DEG_S;
    const bool expired = brakeElapsedMs >= headingLockBrakeHoldTargetMs ||
      brakeElapsedMs >= HEADING_LOCK_MAX_BRAKE_HOLD_MS;
    if (rateReversed || settled || expired) {
      headingLockPhase = (absError <= toleranceDegrees || settled)
        ? HeadingLockPhase::Settle
        : HeadingLockPhase::Correct;
      headingLockBrakeStartedMs = 0;
      headingLockBrakeStartRateSign = 0.0f;
      brakeActive = false;
    }
  }

  const bool observerCanUpdate =
    !headingLockDivergenceWarningActive &&
    !brakeActive &&
    headingDtSeconds > 0.0f &&
    fabs(rateDotDegS2) <= HEADING_LOCK_MAX_RATE_DOT_DEG_S2 &&
    abs(previousCorrectionPercent) >= ESC_MIN_EFFECTIVE_THROTTLE_PERCENT &&
    abs(previousCorrectionPercent) < HEADING_LOCK_MAX_CORRECTION_PERCENT;
  if (observerCanUpdate) {
    const float observedDisturbancePercent = constrain(
      rateDotDegS2 / HEADING_LOCK_OBSERVER_B0_DEG_S2_PER_PERCENT -
        static_cast<float>(previousCorrectionPercent),
      -HEADING_LOCK_OBSERVER_MAX_PERCENT,
      HEADING_LOCK_OBSERVER_MAX_PERCENT
    );
    headingLockDisturbancePercent +=
      (observedDisturbancePercent - headingLockDisturbancePercent) * HEADING_LOCK_OBSERVER_ALPHA;
  } else {
    headingLockDisturbancePercent *= HEADING_LOCK_OBSERVER_DECAY;
    if (fabs(headingLockDisturbancePercent) < 0.1f) {
      headingLockDisturbancePercent = 0.0f;
    }
  }

  const bool boostCanUpdate =
    !headingLockDivergenceWarningActive &&
    !brakeActive &&
    headingDtSeconds > 0.0f &&
    headingDtSeconds <= 0.2f &&
    absError > quietHoldToleranceDegrees &&
    fabs(rateErrorDegS) > HEADING_LOCK_RATE_ERROR_INTEGRAL_DEADBAND_DEG_S &&
    fabs(rateDotDegS2) <= HEADING_LOCK_MAX_RATE_DOT_DEG_S2;
  if (boostCanUpdate) {
    const float rateErrorSign = signOrZero(rateErrorDegS);
    const float integralInputDegS =
      rateErrorDegS - rateErrorSign * HEADING_LOCK_RATE_ERROR_INTEGRAL_DEADBAND_DEG_S;
    const float maxStep = HEADING_LOCK_ADAPTIVE_BOOST_RATE_LIMIT_PERCENT_PER_SECOND * headingDtSeconds;
    const float minStep = HEADING_LOCK_ADAPTIVE_BOOST_MIN_RATE_PERCENT_PER_SECOND * headingDtSeconds;
    float boostStep = HEADING_LOCK_RATE_KI_PERCENT_PER_DEGREE_S_SECOND * integralInputDegS * headingDtSeconds;
    if (fabs(boostStep) < minStep) {
      boostStep = rateErrorSign * minStep;
    }
    boostStep = constrain(boostStep, -maxStep, maxStep);
    headingLockAdaptiveBoostFloatPercent = constrain(
      headingLockAdaptiveBoostFloatPercent + boostStep,
      -HEADING_LOCK_ADAPTIVE_BOOST_MAX_PERCENT,
      HEADING_LOCK_ADAPTIVE_BOOST_MAX_PERCENT
    );
  } else {
    const bool shouldClearBoost =
      inQuietHoldBand && absRate <= HEADING_LOCK_SETTLE_RATE_DEG_S;
    headingLockAdaptiveBoostFloatPercent *= shouldClearBoost
      ? HEADING_LOCK_ADAPTIVE_BOOST_DECAY
      : HEADING_LOCK_ADAPTIVE_BOOST_HOLD_DECAY;
    if (fabs(headingLockAdaptiveBoostFloatPercent) < 0.1f) {
      headingLockAdaptiveBoostFloatPercent = 0.0f;
    }
  }
  if (inQuietHoldBand && absRate <= HEADING_LOCK_SETTLE_RATE_DEG_S) {
    headingLockAdaptiveBoostFloatPercent = 0.0f;
  }
  if (headingLockDivergenceWarningActive) {
    headingLockAdaptiveBoostFloatPercent = 0.0f;
  }
  headingLockAdaptiveBoostPercent = static_cast<int8_t>(roundf(constrain(
    headingLockAdaptiveBoostFloatPercent,
    -HEADING_LOCK_ADAPTIVE_BOOST_MAX_PERCENT,
    HEADING_LOCK_ADAPTIVE_BOOST_MAX_PERCENT
  )));

  float correctionFloat = 0.0f;
  lastHeadingLockBrakePercent = 0.0f;
  if (headingLockDivergenceWarningActive) {
    headingLockPhase = HeadingLockPhase::Guard;
    headingLockBrakeStartedMs = 0;
    headingLockBrakeStartRateSign = 0.0f;
    headingLockBrakeHoldTargetMs = 0;
    correctionFloat = 0.0f;
  } else if (brakeActive) {
    correctionFloat = -headingLockBrakeStartRateSign * brakePercent;
    lastHeadingLockBrakePercent = correctionFloat;
  } else if (inQuietHoldBand && absRate <= HEADING_LOCK_SETTLE_RATE_DEG_S) {
    headingLockPhase = HeadingLockPhase::Settle;
    correctionFloat = 0.0f;
  } else {
    headingLockPhase = HeadingLockPhase::Correct;
    correctionFloat = pdPercent + headingLockAdaptiveBoostFloatPercent;
  }

  int8_t correction = 0;
  if (isfinite(correctionFloat)) {
    int correctionPercent = static_cast<int>(roundf(constrain(
      correctionFloat,
      -static_cast<float>(HEADING_LOCK_MAX_CORRECTION_PERCENT),
      static_cast<float>(HEADING_LOCK_MAX_CORRECTION_PERCENT)
    )));
    const bool neutralEffectiveMinimumAllowed =
      headingLockBasePercent == 0 && absError > quietHoldToleranceDegrees;
    const bool minimumCorrectionAllowed =
      headingLockBasePercent != 0 || neutralEffectiveMinimumAllowed;
    if (
      headingLockBasePercent == 0 &&
      !neutralEffectiveMinimumAllowed &&
      abs(correctionPercent) < HEADING_LOCK_MIN_NEUTRAL_EFFECTIVE_CORRECTION_PERCENT
    ) {
      correctionPercent = 0;
    }
    if (
      minimumCorrectionAllowed &&
      headingLockPhase == HeadingLockPhase::Correct &&
      absError > toleranceDegrees &&
      fabs(correctionFloat) > 0.1f &&
      abs(correctionPercent) < (
        neutralEffectiveMinimumAllowed
          ? HEADING_LOCK_MIN_NEUTRAL_EFFECTIVE_CORRECTION_PERCENT
          : HEADING_LOCK_MIN_CORRECTION_PERCENT
      )
    ) {
      const int minCorrectionPercent = neutralEffectiveMinimumAllowed
        ? HEADING_LOCK_MIN_NEUTRAL_EFFECTIVE_CORRECTION_PERCENT
        : HEADING_LOCK_MIN_CORRECTION_PERCENT;
      correctionPercent = correctionFloat > 0.0f
        ? minCorrectionPercent
        : -minCorrectionPercent;
    }
    correction = static_cast<int8_t>(constrain(
      correctionPercent,
      -static_cast<int>(HEADING_LOCK_MAX_CORRECTION_PERCENT),
      static_cast<int>(HEADING_LOCK_MAX_CORRECTION_PERCENT)
    ));
  }
  lastHeadingLockCorrectionPercent = correction;

  const int signedCorrection = static_cast<int>(correction) * static_cast<int>(HEADING_LOCK_STEER_SIGN);
  int rawLeft = headingLockBasePercent + signedCorrection;
  int rawRight = headingLockBasePercent - signedCorrection;
  if (headingLockBasePercent >= 70) {
    rawLeft = max(0, rawLeft);
    rawRight = max(0, rawRight);
  } else if (headingLockBasePercent <= -70) {
    rawLeft = min(0, rawLeft);
    rawRight = min(0, rawRight);
  } else {
    if (headingLockBasePercent == 0) {
      const int neutralReverseLimit = static_cast<int>(headingLockNeutralReversePercent);
      rawLeft = max(-neutralReverseLimit, rawLeft);
      rawRight = max(-neutralReverseLimit, rawRight);
    }
  }

  int8_t nextLeft = static_cast<int8_t>(constrain(rawLeft, -100, 100));
  int8_t nextRight = static_cast<int8_t>(constrain(rawRight, -100, 100));
  applyHeadingLockLimits(nextLeft, nextRight);

  reportedLeftPercent = nextLeft;
  reportedRightPercent = nextRight;
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

void printTrackLogRead(Print& output, uint32_t fromSeq, uint16_t limit) {
  if (!trackLogReady) {
    output.println("LOG_BEGIN;ERR=NO_TRACKLOG");
    output.println("LOG_END;NEXT=0");
    return;
  }

  uint32_t startSeq = fromSeq;
  size_t startOffset = 0;
  uint16_t count = 0;
  if (trackLogCount > 0) {
    if (startSeq < trackLogOldestSeq) {
      startSeq = trackLogOldestSeq;
    }
    if (startSeq <= trackLogNewestSeq) {
      const uint32_t available = trackLogNewestSeq - startSeq + 1;
      count = static_cast<uint16_t>(available < limit ? available : limit);
      startOffset = static_cast<size_t>(startSeq - trackLogOldestSeq);
      if (startOffset >= trackLogCount) {
        count = 0;
      }
    }
  }

  output.print("LOG_BEGIN;FROM=");
  output.print(fromSeq);
  output.print(";COUNT=");
  output.println(count);

  uint16_t emitted = 0;
  uint32_t nextSeq = fromSeq;
  TrackLogRecord record = {};
  for (size_t offset = startOffset; offset < trackLogCount && emitted < count; ++offset) {
    const size_t index = (trackLogOldestIndex + offset) % trackLogCapacityRecords;
    if (!readTrackLogRecord(index, record) || !isValidTrackRecord(record)) {
      continue;
    }

    const uint32_t seq = unpackTrackSeq24(record);
    if (seq < startSeq) {
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
    } else if (parseUnsignedToken(token, "LIMIT=", 1, 256, limit)) {
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
  if (applyYbCalibrationLine(line, SerialBT)) {
    return;
  }
  if (applyTrackLogLine(line, SerialBT)) {
    return;
  }
  if (applyEscConfigLine(line, SerialBT)) {
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
  bool sawHeadingLockTarget = false;
  bool sawVoicePowerLimit = false;
  bool badSource = false;
  bool badMode = false;
  bool badTurnToken = false;
  bool badHeadingLockToken = false;
  bool badVoiceLimitToken = false;
  bool badHeadingSourceToken = false;
  bool badEscConfigToken = false;
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
  float nextHeadingLockTargetDegrees = 0.0f;
  bool nextHeadingLockEnabled = false;

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
      parseHeadingDegreesToken(token, "TARGET=", nextHeadingLockTargetDegrees)
    ) {
      badHeadingLockToken = badHeadingLockToken || sawHeadingLockTarget;
      sawHeadingLockTarget = true;
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
    } else if (parseHeadingSourceToken(token)) {
      // H_SRC=IMU is accepted for protocol compatibility; control always uses YB IMU.
    } else if (strncmp(token, "LREV=", 5) == 0 || strncmp(token, "RREV=", 5) == 0) {
      badEscConfigToken = true;
    } else if (strncmp(token, "SRC=", 4) == 0) {
      badSource = true;
    } else if (strncmp(token, "MODE=", 5) == 0) {
      badMode = true;
    } else if (
      strncmp(token, "DIR=", 4) == 0 ||
      strncmp(token, "ANGLE=", 6) == 0 ||
      strncmp(token, "TID=", 4) == 0
    ) {
      badTurnToken = true;
    } else if (
      strncmp(token, "HLOCK=", 6) == 0 ||
      strncmp(token, "BASE=", 5) == 0 ||
      strncmp(token, "HID=", 4) == 0 ||
      strncmp(token, "HTOL=", 5) == 0 ||
      strncmp(token, "HFULL=", 6) == 0 ||
      strncmp(token, "HREV=", 5) == 0 ||
      strncmp(token, "TARGET=", 7) == 0
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

  if (badEscConfigToken) {
    SerialBT.println("ERR;ESC_CONFIG_REQUIRES_ESC_CFG");
    Serial.println("Bluetooth command rejected: ESC config token outside ESC_CFG");
    return;
  }

  if (nextMode == CommandMode::KeepAlive) {
    if (!sawArm || !nextArmed) {
      SerialBT.println("ERR;KEEPALIVE_REQUIRES_ARM");
      Serial.println("Keepalive rejected: missing ARM=1");
      return;
    }
    if (!armed) {
      SerialBT.println("ERR;VOICE_CANNOT_ARM");
      Serial.println("Keepalive rejected: cannot arm from locked state");
      return;
    }
    lastValidBtCommandMs = millis();
    lastCommandSource = nextSource;
    if (!headingLockActive && !turnControlActive) {
      lastCommandMode = CommandMode::KeepAlive;
    }
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
      static_cast<uint8_t>(nextVoicePowerLimitPercent)
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
      sawHeadingLockTarget,
      nextHeadingLockTargetDegrees,
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
  reportedLeftPercent = nextArmed ? nextLeftPercent : 0;
  reportedRightPercent = nextArmed ? nextRightPercent : 0;
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
  Serial.print(reportedLeftPercent);
  Serial.print(" right=");
  Serial.print(reportedRightPercent);
  Serial.print(" requested_left=");
  Serial.print(requestedLeftPercent);
  Serial.print(" requested_right=");
  Serial.println(requestedRightPercent);
}

void applySerialLine(char* line) {
  if (applyIdentityLine(line, Serial)) {
    return;
  }
  if (applyMagCalibrationLine(line, Serial)) {
    return;
  }
  if (applyYbCalibrationLine(line, Serial)) {
    return;
  }
  if (applyTrackLogLine(line, Serial)) {
    return;
  }
  if (applyEscConfigLine(line, Serial)) {
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
    reportedLeftPercent = 0;
    reportedRightPercent = 0;
    cancelAutonomousControl();
    Serial.println("Bluetooth command timeout; state=DISARMED; throttle=NEUTRAL");
    SerialBT.println("STATUS;ARMED=0;FAULT=BT_TIMEOUT");
  }
}

void publishBluetoothStatus(uint32_t now) {
  if (otaInProgress) {
    return;
  }
  const uint32_t statusIntervalMs = ybImuAvailable ? BT_YB_IMU_STATUS_INTERVAL_MS : BT_STATUS_INTERVAL_MS;
  if (now - lastBtStatusMs < statusIntervalMs) {
    return;
  }
  lastBtStatusMs = now;

  SerialBT.print("STATUS;ARMED=");
  SerialBT.print(armed ? 1 : 0);
  SerialBT.print(";T=");
  SerialBT.print(now);
  SerialBT.print(";FW=");
  SerialBT.print(FIRMWARE_VERSION);
  SerialBT.print(";L=");
  SerialBT.print(reportedLeftPercent);
  SerialBT.print(";R=");
  SerialBT.print(reportedRightPercent);
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
  SerialBT.print(";MAG=");
  SerialBT.print(magnetometerAvailable ? 1 : 0);
  SerialBT.print(";HDG=");
  SerialBT.print(headingDegrees, 1);
  SerialBT.print(";IHDG=");
  SerialBT.print(imuHeadingDegrees, 1);
  SerialBT.print(";IMHDG=");
  SerialBT.print(imuMagHeadingDegrees, 1);
  SerialBT.print(";IAHRS=");
  SerialBT.print(imuAharsRawYawDegrees, 1);
  SerialBT.print(";IROLL=");
  SerialBT.print(imuRollDegrees, 1);
  SerialBT.print(";IPITCH=");
  SerialBT.print(imuPitchDegrees, 1);
  SerialBT.print(";IQUAL=");
  SerialBT.print(imuFusionQuality);
  SerialBT.print(";YBIMU=");
  SerialBT.print(ybImuAvailable ? 1 : 0);
  if (ybImuAvailable) {
    SerialBT.print(";YBAX=");
    SerialBT.print(ybAccelXG, 3);
    SerialBT.print(";YBAY=");
    SerialBT.print(ybAccelYG, 3);
    SerialBT.print(";YBAZ=");
    SerialBT.print(ybAccelZG, 3);
    SerialBT.print(";YBGZ=");
    SerialBT.print(ybGyroZRadS, 3);
    SerialBT.print(";YBMX=");
    SerialBT.print(ybMagXUt, 2);
    SerialBT.print(";YBMY=");
    SerialBT.print(ybMagYUt, 2);
    SerialBT.print(";YBMZ=");
    SerialBT.print(ybMagZUt, 2);
    SerialBT.print(";YBCIMU=");
    SerialBT.print(ybImuCalibrationState);
    SerialBT.print(";YBCMAG=");
    SerialBT.print(ybMagCalibrationState);
    SerialBT.print(";YBCACT=");
    if (ybCalibrationActive && ybCalibrationTargetRegister == YB_IMU_CALIB_IMU) {
      SerialBT.print("IMU");
    } else if (ybCalibrationActive && ybCalibrationTargetRegister == YB_IMU_CALIB_MAG) {
      SerialBT.print("MAG");
    } else {
      SerialBT.print("NONE");
    }
    SerialBT.print(";YBCRAW=");
    SerialBT.print(ybCalibrationLastState);
    SerialBT.print(";YBCFAIL=");
    SerialBT.print(ybCalibrationReadFailures);
    SerialBT.print(";YBQW=");
    SerialBT.print(ybQuatW, 4);
    SerialBT.print(";YBQX=");
    SerialBT.print(ybQuatX, 4);
    SerialBT.print(";YBQY=");
    SerialBT.print(ybQuatY, 4);
    SerialBT.print(";YBQZ=");
    SerialBT.print(ybQuatZ, 4);
    SerialBT.print(";YBR=");
    SerialBT.print(ybRollDegrees, 1);
    SerialBT.print(";YBP=");
    SerialBT.print(ybPitchDegrees, 1);
    SerialBT.print(";YBY=");
    SerialBT.print(ybYawDegrees, 1);
  }
  SerialBT.print(";IGZB=");
  SerialBT.print(gyroZBiasDps, 3);
  SerialBT.print(";YBINIT=");
  SerialBT.print(ybHeadingInitialized ? 1 : 0);
  SerialBT.print(";YBAGE=");
  SerialBT.print(ybHeadingAgeMs(now));
  if (ybHeadingInitialized) {
    SerialBT.print(";YBHDG=");
    SerialBT.print(currentYbHeadingDegrees(), 1);
  }
  SerialBT.print(";HSRC=");
  SerialBT.print(activeHeadingSourceName(now));
  SerialBT.print(";MCAL=");
  SerialBT.print(magCalibrationStateName());
  SerialBT.print(";MCNT=");
  SerialBT.print(magCalibrationSamples);
  SerialBT.print(";MRX=");
  SerialBT.print(magCalibrationSamples > 0 ? magCalibrationMaxX - magCalibrationMinX : 0);
  SerialBT.print(";MRY=");
  SerialBT.print(magCalibrationSamples > 0 ? magCalibrationMaxY - magCalibrationMinY : 0);
  if (imuAvailable) {
    SerialBT.print(";IAX=");
    SerialBT.print(imuAccelXG, 3);
    SerialBT.print(";IAY=");
    SerialBT.print(imuAccelYG, 3);
    SerialBT.print(";IAZ=");
    SerialBT.print(imuAccelZG, 3);
    SerialBT.print(";IAN=");
    SerialBT.print(imuAccelNormG, 3);
    SerialBT.print(";IGX=");
    SerialBT.print(imuGyroXDps, 2);
    SerialBT.print(";IGY=");
    SerialBT.print(imuGyroYDps, 2);
    SerialBT.print(";IGZ=");
    SerialBT.print(imuGyroZDps, 2);
    SerialBT.print(";IMX=");
    SerialBT.print(imuMagRawX);
    SerialBT.print(";IMY=");
    SerialBT.print(imuMagRawY);
    SerialBT.print(";IMZ=");
    SerialBT.print(imuMagRawZ);
    SerialBT.print(";IMAG=");
    SerialBT.print(imuMagNormRaw, 1);
  }
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
    SerialBT.print(";HPHASE=");
    SerialBT.print(headingLockPhaseName(headingLockPhase));
    SerialBT.print(";HRATE=");
    SerialBT.print(lastHeadingLockRateDegS, 1);
    SerialBT.print(";HREF=");
    SerialBT.print(lastHeadingLockTargetRateDegS, 1);
    SerialBT.print(";HRERR=");
    SerialBT.print(lastHeadingLockRateErrorDegS, 1);
    SerialBT.print(";HRDOT=");
    SerialBT.print(lastHeadingLockRateDotDegS2, 1);
    SerialBT.print(";HPRED=");
    SerialBT.print(lastHeadingLockPredictedErrorDegrees, 1);
    SerialBT.print(";HPD=");
    SerialBT.print(lastHeadingLockPdPercent, 1);
    SerialBT.print(";HINNER=");
    SerialBT.print(lastHeadingLockInnerPercent, 1);
    SerialBT.print(";HFHAT=");
    SerialBT.print(headingLockDisturbancePercent, 1);
    SerialBT.print(";HBRK=");
    SerialBT.print(lastHeadingLockBrakePercent, 1);
    SerialBT.print(";HBOOST=");
    SerialBT.print(headingLockAdaptiveBoostPercent);
    if (headingLockHeadingUnavailableWarningActive) {
      SerialBT.print(";HWARN=YB_HEADING_UNAVAILABLE");
    } else if (headingLockDivergenceWarningActive) {
      SerialBT.print(";HWARN=HEADING_LOCK_DIVERGED");
    }
    SerialBT.print(";BMS=");
    if (headingLockPhase == HeadingLockPhase::Brake && headingLockBrakeStartedMs != 0) {
      const uint32_t elapsedMs = millis() - headingLockBrakeStartedMs;
      SerialBT.print(elapsedMs >= headingLockBrakeHoldTargetMs ? 0 : headingLockBrakeHoldTargetMs - elapsedMs);
    } else {
      SerialBT.print(0);
    }
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
  loadEscDirectionConfig();
  pinMode(ARM_BUTTON_PIN, INPUT_PULLUP);
  pinMode(STATUS_LED_PIN, OUTPUT);

  ledcSetup(LEFT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcSetup(RIGHT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcAttachPin(LEFT_ESC_PIN, LEFT_ESC_CHANNEL);
  ledcAttachPin(RIGHT_ESC_PIN, RIGHT_ESC_CHANNEL);

  writeEsc(LEFT_ESC_CHANNEL, ESC_NEUTRAL_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_NEUTRAL_US);
  holdEscNeutralDuringBoot();
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
  updateHeadingLockControl(now);
  publishBluetoothStatus(now);

  if (now - lastTickMs < CONTROL_TICK_MS) {
    return;
  }
  lastTickMs = now;

  const bool canApplyBluetoothThrottle = armed && hasFreshBluetoothCommand(now);
  const int8_t leftEscOutputPercent = leftSemanticPercentToEscPercent(requestedLeftPercent);
  const int8_t rightEscOutputPercent = rightSemanticPercentToEscPercent(requestedRightPercent);
  const uint16_t leftTarget = canApplyBluetoothThrottle ? signedPercentToPulseUs(leftEscOutputPercent) : ESC_NEUTRAL_US;
  const uint16_t rightTarget = canApplyBluetoothThrottle ? signedPercentToPulseUs(rightEscOutputPercent) : ESC_NEUTRAL_US;

  leftPulseUs = rampToward(leftPulseUs, constrain(leftTarget, ESC_REVERSE_US, ESC_FORWARD_US));
  rightPulseUs = rampToward(rightPulseUs, constrain(rightTarget, ESC_REVERSE_US, ESC_FORWARD_US));

  writeEsc(LEFT_ESC_CHANNEL, leftPulseUs);
  writeEsc(RIGHT_ESC_CHANNEL, rightPulseUs);
  digitalWrite(STATUS_LED_PIN, armed ? HIGH : (now / 500) % 2);
}
