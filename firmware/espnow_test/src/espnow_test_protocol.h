#pragma once

#include <Arduino.h>

constexpr uint16_t ESPNOW_TEST_MAGIC = 0x5355;
constexpr uint8_t ESPNOW_TEST_VERSION = 1;
constexpr uint8_t ESPNOW_TEST_TYPE_CONTROL = 1;
constexpr uint8_t ESPNOW_TEST_TYPE_TELEMETRY = 3;

struct __attribute__((packed)) EspnowTestControlFrame {
  uint16_t magic;
  uint8_t version;
  uint8_t type;
  uint16_t remoteId;
  uint32_t seq;
  uint16_t flags;
  int8_t left;
  int8_t right;
  uint8_t vmax;
  uint16_t remoteMv;
  uint16_t crc16;
};

struct __attribute__((packed)) EspnowTestTelemetryFrame {
  uint16_t magic;
  uint8_t version;
  uint8_t type;
  uint16_t controllerId;
  uint32_t seq;
  uint8_t armed;
  uint16_t fault;
  int8_t leftOut;
  int8_t rightOut;
  uint16_t battMv;
  uint16_t totalAx10;
  int8_t rssiHint;
  uint16_t crc16;
};

inline uint16_t espnowTestCrc16(const uint8_t *data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; ++i) {
    crc ^= static_cast<uint16_t>(data[i]) << 8;
    for (uint8_t bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
    }
  }
  return crc;
}

template <typename T>
inline void espnowTestFinalizeFrame(T &frame) {
  frame.crc16 = 0;
  frame.crc16 = espnowTestCrc16(reinterpret_cast<const uint8_t *>(&frame), sizeof(frame));
}

template <typename T>
inline bool espnowTestValidateFrame(const T &frame) {
  T copy = frame;
  const uint16_t receivedCrc = copy.crc16;
  copy.crc16 = 0;
  return receivedCrc == espnowTestCrc16(reinterpret_cast<const uint8_t *>(&copy), sizeof(copy));
}

inline bool espnowTestParseMac(const char *text, uint8_t mac[6]) {
  unsigned int values[6] = {};
  if (sscanf(text, "%x:%x:%x:%x:%x:%x", &values[0], &values[1], &values[2],
             &values[3], &values[4], &values[5]) != 6) {
    return false;
  }
  for (uint8_t i = 0; i < 6; ++i) {
    if (values[i] > 0xFF) {
      return false;
    }
    mac[i] = static_cast<uint8_t>(values[i]);
  }
  return true;
}

inline void espnowTestPrintMac(Print &out, const uint8_t mac[6]) {
  for (uint8_t i = 0; i < 6; ++i) {
    if (i > 0) {
      out.print(':');
    }
    if (mac[i] < 0x10) {
      out.print('0');
    }
    out.print(mac[i], HEX);
  }
}
