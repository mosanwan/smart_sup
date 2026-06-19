#include <Arduino.h>
#include <Wire.h>

namespace {

constexpr uint8_t IMU_SDA_PIN = 14;
constexpr uint8_t IMU_SCL_PIN = 22;
constexpr uint32_t SERIAL_BAUD = 115200;
constexpr uint32_t I2C_CLOCK_HZ = 100000;
constexpr uint32_t SCAN_INTERVAL_MS = 2000;

uint32_t lastScanMs = 0;

void scanI2cBus() {
  uint8_t found = 0;

  Serial.println();
  Serial.println("SmartSUP IMU I2C scan");
  Serial.print("SDA=GPIO");
  Serial.print(IMU_SDA_PIN);
  Serial.print(" SCL=GPIO");
  Serial.print(IMU_SCL_PIN);
  Serial.print(" clock=");
  Serial.print(I2C_CLOCK_HZ);
  Serial.println("Hz");

  for (uint8_t address = 1; address < 127; ++address) {
    Wire.beginTransmission(address);
    const uint8_t error = Wire.endTransmission();
    if (error == 0) {
      Serial.print("I2C device found at 0x");
      if (address < 16) {
        Serial.print('0');
      }
      Serial.println(address, HEX);
      ++found;
    } else if (error == 4) {
      Serial.print("Unknown I2C error at 0x");
      if (address < 16) {
        Serial.print('0');
      }
      Serial.println(address, HEX);
    }
    delay(2);
  }

  if (found == 0) {
    Serial.println("No I2C device found");
  } else {
    Serial.print("I2C device count: ");
    Serial.println(found);
  }
}

}  // namespace

void setup() {
  Serial.begin(SERIAL_BAUD);
  delay(1000);
  Wire.begin(IMU_SDA_PIN, IMU_SCL_PIN);
  Wire.setClock(I2C_CLOCK_HZ);
  scanI2cBus();
  lastScanMs = millis();
}

void loop() {
  const uint32_t now = millis();
  if (now - lastScanMs >= SCAN_INTERVAL_MS) {
    lastScanMs = now;
    scanI2cBus();
  }
}
