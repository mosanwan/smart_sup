#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <string.h>

namespace {

constexpr uint8_t IMU_SDA_PIN = 14;
constexpr uint8_t IMU_SCL_PIN = 22;
constexpr uint8_t IMU_ADDR = 0x23;
constexpr uint32_t SERIAL_BAUD = 115200;
constexpr uint32_t I2C_CLOCK_HZ = 100000;
constexpr uint32_t PROBE_INTERVAL_MS = 1000;

struct ProbeTarget {
  uint8_t reg;
  uint8_t length;
  const char* name;
};

const ProbeTarget PROBE_TARGETS[] = {
  {0x00, 16, "LOW_MEMORY_00"},
  {0x04, 6, "YB_RAW_ACCEL_CANDIDATE_i16"},
  {0x0A, 6, "YB_RAW_GYRO_CANDIDATE_i16"},
  {0x10, 6, "YB_RAW_MAG_CANDIDATE_i16"},
  {0x16, 16, "YB_QUAT_CANDIDATE_f32"},
  {0x26, 12, "YB_EULER_CANDIDATE_f32_RAD"},
  {0x32, 16, "YB_BARO_OR_EXTRA_CANDIDATE_f32"},
  {0x34, 6, "OLD_WIT_AX_AY_AZ_i16"},
  {0x37, 6, "OLD_WIT_GX_GY_GZ_i16"},
  {0x3D, 6, "OLD_WIT_ROLL_PITCH_YAW_i16"},
};

uint32_t lastProbeMs = 0;

bool readRegister(uint8_t reg, uint8_t* buffer, size_t length) {
  Wire.beginTransmission(IMU_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  const size_t readBytes = Wire.requestFrom(static_cast<int>(IMU_ADDR), static_cast<int>(length));
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

int16_t int16Le(const uint8_t* data) {
  return static_cast<int16_t>((static_cast<uint16_t>(data[1]) << 8) | data[0]);
}

int16_t int16Be(const uint8_t* data) {
  return static_cast<int16_t>((static_cast<uint16_t>(data[0]) << 8) | data[1]);
}

float floatLe(const uint8_t* data) {
  float value = 0.0f;
  uint8_t bytes[4] = {data[0], data[1], data[2], data[3]};
  memcpy(&value, bytes, sizeof(value));
  return value;
}

float floatBe(const uint8_t* data) {
  float value = 0.0f;
  uint8_t bytes[4] = {data[3], data[2], data[1], data[0]};
  memcpy(&value, bytes, sizeof(value));
  return value;
}

void printHexByte(uint8_t value) {
  if (value < 16) {
    Serial.print('0');
  }
  Serial.print(value, HEX);
}

void printHexBuffer(const uint8_t* data, size_t length) {
  for (size_t i = 0; i < length; ++i) {
    if (i > 0) {
      Serial.print(' ');
    }
    printHexByte(data[i]);
  }
}

void printScaledWitTriple(uint8_t reg, const uint8_t* data, size_t length) {
  if (length < 6) {
    return;
  }

  const int16_t x = int16Le(&data[0]);
  const int16_t y = int16Le(&data[2]);
  const int16_t z = int16Le(&data[4]);

  if (reg == 0x04 || reg == 0x34) {
    Serial.print(" accel_g=");
    Serial.print(x * 16.0f / 32768.0f, 3);
    Serial.print(',');
    Serial.print(y * 16.0f / 32768.0f, 3);
    Serial.print(',');
    Serial.print(z * 16.0f / 32768.0f, 3);
  } else if (reg == 0x0A || reg == 0x37) {
    Serial.print(" gyro_dps=");
    Serial.print(x * 2000.0f / 32768.0f, 2);
    Serial.print(',');
    Serial.print(y * 2000.0f / 32768.0f, 2);
    Serial.print(',');
    Serial.print(z * 2000.0f / 32768.0f, 2);
  } else if (reg == 0x3D) {
    Serial.print(" euler_deg=");
    Serial.print(x * 180.0f / 32768.0f, 2);
    Serial.print(',');
    Serial.print(y * 180.0f / 32768.0f, 2);
    Serial.print(',');
    Serial.print(z * 180.0f / 32768.0f, 2);
  }
}

void printProbeTarget(const ProbeTarget& target) {
  uint8_t data[16] = {};
  if (!readRegister(target.reg, data, target.length)) {
    Serial.print("reg=0x");
    printHexByte(target.reg);
    Serial.print(' ');
    Serial.print(target.name);
    Serial.println(" READ_FAIL");
    return;
  }

  Serial.print("reg=0x");
  printHexByte(target.reg);
  Serial.print(' ');
  Serial.print(target.name);
  Serial.print(" raw=");
  printHexBuffer(data, target.length);

  if (target.length >= 6) {
    Serial.print(" i16le=");
    Serial.print(int16Le(&data[0]));
    Serial.print(',');
    Serial.print(int16Le(&data[2]));
    Serial.print(',');
    Serial.print(int16Le(&data[4]));
    Serial.print(" i16be=");
    Serial.print(int16Be(&data[0]));
    Serial.print(',');
    Serial.print(int16Be(&data[2]));
    Serial.print(',');
    Serial.print(int16Be(&data[4]));
    printScaledWitTriple(target.reg, data, target.length);
  }

  if (target.length >= 12) {
    const float f0 = floatLe(&data[0]);
    const float f1 = floatLe(&data[4]);
    const float f2 = floatLe(&data[8]);
    Serial.print(" f32le=");
    Serial.print(f0, 4);
    Serial.print(',');
    Serial.print(f1, 4);
    Serial.print(',');
    Serial.print(f2, 4);
    Serial.print(" f32be=");
    Serial.print(floatBe(&data[0]), 4);
    Serial.print(',');
    Serial.print(floatBe(&data[4]), 4);
    Serial.print(',');
    Serial.print(floatBe(&data[8]), 4);
  }

  if (target.length >= 16) {
    Serial.print(" f32le4=");
    Serial.print(floatLe(&data[0]), 4);
    Serial.print(',');
    Serial.print(floatLe(&data[4]), 4);
    Serial.print(',');
    Serial.print(floatLe(&data[8]), 4);
    Serial.print(',');
    Serial.print(floatLe(&data[12]), 4);
  }

  Serial.println();
}

void probeImu() {
  Serial.println();
  Serial.println("SmartSUP Yahboom IMU read-only probe");
  Serial.print("addr=0x");
  printHexByte(IMU_ADDR);
  Serial.print(" SDA=GPIO");
  Serial.print(IMU_SDA_PIN);
  Serial.print(" SCL=GPIO");
  Serial.print(IMU_SCL_PIN);
  Serial.print(" clock=");
  Serial.print(I2C_CLOCK_HZ);
  Serial.println("Hz");

  for (const ProbeTarget& target : PROBE_TARGETS) {
    printProbeTarget(target);
    delay(5);
  }
}

}  // namespace

void setup() {
  Serial.begin(SERIAL_BAUD);
  delay(1000);
  Wire.begin(IMU_SDA_PIN, IMU_SCL_PIN);
  Wire.setClock(I2C_CLOCK_HZ);
  probeImu();
  lastProbeMs = millis();
}

void loop() {
  const uint32_t now = millis();
  if (now - lastProbeMs >= PROBE_INTERVAL_MS) {
    lastProbeMs = now;
    probeImu();
  }
}
