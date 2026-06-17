#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#include "espnow_test_protocol.h"

namespace {
constexpr uint8_t LEFT_ESC_PIN = 25;
constexpr uint8_t RIGHT_ESC_PIN = 26;
constexpr uint8_t LEFT_ESC_CHANNEL = 0;
constexpr uint8_t RIGHT_ESC_CHANNEL = 1;
constexpr uint32_t ESC_PWM_FREQ_HZ = 50;
constexpr uint8_t ESC_PWM_RESOLUTION_BITS = 16;
constexpr uint16_t ESC_NEUTRAL_US = 1500;
constexpr uint32_t LINK_TIMEOUT_MS = 500;
constexpr uint32_t STATUS_INTERVAL_MS = 1000;

uint8_t lastRemoteMac[6] = {};
bool haveRemote = false;
uint32_t lastRxMs = 0;
uint32_t lastStatusMs = 0;
uint32_t rxCount = 0;
uint32_t badCount = 0;
uint32_t telemetrySeq = 0;

uint32_t escDutyFromMicros(uint16_t pulseUs) {
  const uint32_t maxDuty = (1UL << ESC_PWM_RESOLUTION_BITS) - 1UL;
  return (static_cast<uint32_t>(pulseUs) * maxDuty * ESC_PWM_FREQ_HZ) / 1000000UL;
}

void writeNeutralEsc() {
  ledcWrite(LEFT_ESC_CHANNEL, escDutyFromMicros(ESC_NEUTRAL_US));
  ledcWrite(RIGHT_ESC_CHANNEL, escDutyFromMicros(ESC_NEUTRAL_US));
}

void ensurePeer(const uint8_t mac[6]) {
  if (esp_now_is_peer_exist(mac)) {
    return;
  }
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, mac, 6);
  peer.channel = ESPNOW_TEST_CHANNEL;
  peer.encrypt = false;
  esp_err_t err = esp_now_add_peer(&peer);
  Serial.print("receiver peer add ");
  espnowTestPrintMac(Serial, mac);
  Serial.print(" err=");
  Serial.println(static_cast<int>(err));
}

void sendTelemetry(const uint8_t mac[6]) {
  EspnowTestTelemetryFrame frame = {};
  frame.magic = ESPNOW_TEST_MAGIC;
  frame.version = ESPNOW_TEST_VERSION;
  frame.type = ESPNOW_TEST_TYPE_TELEMETRY;
  frame.controllerId = 1;
  frame.seq = ++telemetrySeq;
  frame.armed = 0;
  frame.fault = 0;
  frame.leftOut = 0;
  frame.rightOut = 0;
  frame.battMv = 0;
  frame.totalAx10 = 0;
  frame.rssiHint = 0;
  espnowTestFinalizeFrame(frame);
  esp_now_send(mac, reinterpret_cast<const uint8_t *>(&frame), sizeof(frame));
}

#if ESP_ARDUINO_VERSION_MAJOR >= 3
void onReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  const uint8_t *mac = info->src_addr;
#else
void onReceive(const uint8_t *mac, const uint8_t *data, int len) {
#endif
  writeNeutralEsc();
  if (len != static_cast<int>(sizeof(EspnowTestControlFrame))) {
    ++badCount;
    return;
  }
  EspnowTestControlFrame frame = {};
  memcpy(&frame, data, sizeof(frame));
  if (frame.magic != ESPNOW_TEST_MAGIC || frame.version != ESPNOW_TEST_VERSION ||
      frame.type != ESPNOW_TEST_TYPE_CONTROL || !espnowTestValidateFrame(frame)) {
    ++badCount;
    return;
  }

  memcpy(lastRemoteMac, mac, 6);
  haveRemote = true;
  lastRxMs = millis();
  ++rxCount;
  ensurePeer(mac);
  sendTelemetry(mac);

  Serial.print("RX seq=");
  Serial.print(frame.seq);
  Serial.print(" remote=");
  espnowTestPrintMac(Serial, mac);
  Serial.print(" arm=");
  Serial.print((frame.flags & 0x01) ? 1 : 0);
  Serial.print(" estop=");
  Serial.print((frame.flags & 0x02) ? 1 : 0);
  Serial.print(" L=");
  Serial.print(frame.left);
  Serial.print(" R=");
  Serial.print(frame.right);
  Serial.print(" vmax=");
  Serial.println(frame.vmax);
}

void setupEspNow() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(ESPNOW_TEST_CHANNEL, WIFI_SECOND_CHAN_NONE);
  Serial.print("receiver mac=");
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
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(1000);
  ledcSetup(LEFT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcSetup(RIGHT_ESC_CHANNEL, ESC_PWM_FREQ_HZ, ESC_PWM_RESOLUTION_BITS);
  ledcAttachPin(LEFT_ESC_PIN, LEFT_ESC_CHANNEL);
  ledcAttachPin(RIGHT_ESC_PIN, RIGHT_ESC_CHANNEL);
  writeNeutralEsc();
  setupEspNow();
  Serial.println("receiver ready; ESC outputs forced to neutral");
}

void loop() {
  writeNeutralEsc();
  const uint32_t now = millis();
  if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
    lastStatusMs = now;
    Serial.print("STATUS rx=");
    Serial.print(rxCount);
    Serial.print(" bad=");
    Serial.print(badCount);
    Serial.print(" link=");
    Serial.print(haveRemote && (now - lastRxMs <= LINK_TIMEOUT_MS) ? "UP" : "DOWN");
    if (haveRemote) {
      Serial.print(" last_remote=");
      espnowTestPrintMac(Serial, lastRemoteMac);
      Serial.print(" age_ms=");
      Serial.print(now - lastRxMs);
    }
    Serial.println();
  }
}
