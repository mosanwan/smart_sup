#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#include "espnow_test_protocol.h"

namespace {
constexpr uint8_t LEFT_STICK_PIN = 0;
constexpr uint8_t RIGHT_STICK_PIN = 1;
constexpr uint16_t ADC_DEADBAND = 130;
constexpr uint8_t CALIBRATION_SAMPLES = 80;
constexpr uint32_t SEND_INTERVAL_MS = 100;
constexpr uint16_t CENTER_MIN_VALID = 300;
constexpr uint16_t CENTER_MAX_VALID = 3795;

uint8_t receiverMac[6] = {};
uint32_t txSeq = 0;
uint32_t ackCount = 0;
uint32_t sendOkCount = 0;
uint32_t sendFailCount = 0;
uint32_t lastSendMs = 0;
uint32_t lastPrintMs = 0;
uint32_t lastAckMs = 0;
uint16_t leftCenter = 2048;
uint16_t rightCenter = 2048;
uint16_t leftRaw = 0;
uint16_t rightRaw = 0;
int8_t leftPercent = 0;
int8_t rightPercent = 0;
bool stickFault = false;
bool calibrationFault = false;

uint16_t readAveragedAdc(uint8_t pin, uint8_t samples) {
  uint32_t total = 0;
  for (uint8_t i = 0; i < samples; ++i) {
    total += analogRead(pin);
    delay(2);
  }
  return static_cast<uint16_t>(total / samples);
}

int8_t mapStickPercent(uint16_t raw, uint16_t center) {
  const int32_t delta = static_cast<int32_t>(raw) - static_cast<int32_t>(center);
  if (abs(delta) <= ADC_DEADBAND) {
    return 0;
  }

  int32_t percent = 0;
  if (delta > 0) {
    const int32_t span = max<int32_t>(1, 4095 - center - ADC_DEADBAND);
    percent = ((delta - ADC_DEADBAND) * 100) / span;
  } else {
    const int32_t span = max<int32_t>(1, center - ADC_DEADBAND);
    percent = ((delta + ADC_DEADBAND) * 100) / span;
  }
  return static_cast<int8_t>(constrain(percent, -100, 100));
}

void updateSticks() {
  leftRaw = readAveragedAdc(LEFT_STICK_PIN, 4);
  rightRaw = readAveragedAdc(RIGHT_STICK_PIN, 4);
  stickFault = calibrationFault;
  leftPercent = stickFault ? 0 : mapStickPercent(leftRaw, leftCenter);
  rightPercent = stickFault ? 0 : mapStickPercent(rightRaw, rightCenter);
}

void calibrateSticks() {
  leftCenter = readAveragedAdc(LEFT_STICK_PIN, CALIBRATION_SAMPLES);
  rightCenter = readAveragedAdc(RIGHT_STICK_PIN, CALIBRATION_SAMPLES);
  calibrationFault = leftCenter < CENTER_MIN_VALID || leftCenter > CENTER_MAX_VALID ||
                     rightCenter < CENTER_MIN_VALID || rightCenter > CENTER_MAX_VALID;
  updateSticks();
  Serial.print("stick calibration left_center=");
  Serial.print(leftCenter);
  Serial.print(" right_center=");
  Serial.print(rightCenter);
  Serial.print(" fault=");
  Serial.println(stickFault ? 1 : 0);
}

void addReceiverPeer() {
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, receiverMac, 6);
  peer.channel = ESPNOW_TEST_CHANNEL;
  peer.encrypt = false;
  esp_err_t err = esp_now_add_peer(&peer);
  Serial.print("remote peer=");
  espnowTestPrintMac(Serial, receiverMac);
  Serial.print(" add_err=");
  Serial.println(static_cast<int>(err));
}

void sendControlFrame() {
  updateSticks();
  EspnowTestControlFrame frame = {};
  frame.magic = ESPNOW_TEST_MAGIC;
  frame.version = ESPNOW_TEST_VERSION;
  frame.type = ESPNOW_TEST_TYPE_CONTROL;
  frame.remoteId = 1;
  frame.seq = ++txSeq;
  frame.flags = stickFault ? 0x0002 : 0x0000;  // Keep ARM=0 during bench test.
  frame.left = leftPercent;
  frame.right = rightPercent;
  frame.vmax = 30;
  frame.remoteMv = 0;
  espnowTestFinalizeFrame(frame);
  esp_err_t err = esp_now_send(receiverMac, reinterpret_cast<const uint8_t *>(&frame), sizeof(frame));
  if (err != ESP_OK) {
    ++sendFailCount;
    Serial.print("send immediate err=");
    Serial.println(static_cast<int>(err));
  }
}

#if ESP_ARDUINO_VERSION_MAJOR >= 3
void onReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  const uint8_t *mac = info->src_addr;
#else
void onReceive(const uint8_t *mac, const uint8_t *data, int len) {
#endif
  if (len != static_cast<int>(sizeof(EspnowTestTelemetryFrame))) {
    return;
  }
  EspnowTestTelemetryFrame frame = {};
  memcpy(&frame, data, sizeof(frame));
  if (frame.magic != ESPNOW_TEST_MAGIC || frame.version != ESPNOW_TEST_VERSION ||
      frame.type != ESPNOW_TEST_TYPE_TELEMETRY || !espnowTestValidateFrame(frame)) {
    return;
  }
  ++ackCount;
  lastAckMs = millis();
  Serial.print("ACK seq=");
  Serial.print(frame.seq);
  Serial.print(" from=");
  espnowTestPrintMac(Serial, mac);
  Serial.print(" armed=");
  Serial.print(frame.armed);
  Serial.print(" fault=");
  Serial.println(frame.fault);
}

void onSend(const uint8_t *mac, esp_now_send_status_t status) {
  if (status == ESP_NOW_SEND_SUCCESS) {
    ++sendOkCount;
  } else {
    ++sendFailCount;
  }
}

void setupEspNow() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(ESPNOW_TEST_CHANNEL, WIFI_SECOND_CHAN_NONE);
  Serial.print("remote mac=");
  Serial.print(WiFi.macAddress());
  Serial.print(" channel=");
  Serial.println(ESPNOW_TEST_CHANNEL);

  esp_err_t err = esp_now_init();
  if (err != ESP_OK) {
    Serial.print("esp_now_init failed err=");
    Serial.println(static_cast<int>(err));
    return;
  }
  esp_now_register_recv_cb(onReceive);
  esp_now_register_send_cb(onSend);
  addReceiverPeer();
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(2000);
  analogReadResolution(12);
  pinMode(LEFT_STICK_PIN, INPUT);
  pinMode(RIGHT_STICK_PIN, INPUT);
  calibrateSticks();
  if (!espnowTestParseMac(ESPNOW_RECEIVER_MAC, receiverMac)) {
    Serial.println("invalid ESPNOW_RECEIVER_MAC build flag");
    return;
  }
  setupEspNow();
  Serial.println("remote ready; sending dual-stick bench frames with ARM=0");
}

void loop() {
  const uint32_t now = millis();
  if (now - lastSendMs >= SEND_INTERVAL_MS) {
    lastSendMs = now;
    sendControlFrame();
  }
  if (now - lastPrintMs >= 1000) {
    lastPrintMs = now;
    Serial.print("STATUS tx=");
    Serial.print(txSeq);
    Serial.print(" send_ok=");
    Serial.print(sendOkCount);
    Serial.print(" send_fail=");
    Serial.print(sendFailCount);
    Serial.print(" ack=");
    Serial.print(ackCount);
    Serial.print(" link=");
    Serial.print(lastAckMs != 0 && (now - lastAckMs < 1000) ? "UP" : "DOWN");
    Serial.print(" left_center=");
    Serial.print(leftCenter);
    Serial.print(" right_center=");
    Serial.print(rightCenter);
    Serial.print(" left_raw=");
    Serial.print(leftRaw);
    Serial.print(" right_raw=");
    Serial.print(rightRaw);
    Serial.print(" left=");
    Serial.print(leftPercent);
    Serial.print(" right=");
    Serial.print(rightPercent);
    Serial.print(" fault=");
    Serial.print(stickFault ? 1 : 0);
    if (lastAckMs != 0) {
      Serial.print(" ack_age_ms=");
      Serial.print(now - lastAckMs);
    }
    Serial.println();
  }
}
