#include <Arduino.h>

namespace {
constexpr uint8_t LEFT_ESC_PIN = 25;
constexpr uint8_t RIGHT_ESC_PIN = 26;
constexpr uint8_t ARM_BUTTON_PIN = 17;
constexpr uint8_t STATUS_LED_PIN = 2;

constexpr uint8_t LEFT_ESC_CHANNEL = 0;
constexpr uint8_t RIGHT_ESC_CHANNEL = 1;
constexpr uint32_t ESC_PWM_FREQ_HZ = 50;
constexpr uint8_t ESC_PWM_RESOLUTION_BITS = 16;

constexpr uint16_t ESC_IDLE_US = 1000;
constexpr uint16_t ESC_MAX_US = 2000;
constexpr uint16_t THROTTLE_RAMP_US_PER_TICK = 5;
constexpr uint32_t CONTROL_TICK_MS = 20;
constexpr uint32_t ARM_HOLD_MS = 1500;

bool armed = false;
uint16_t leftPulseUs = ESC_IDLE_US;
uint16_t rightPulseUs = ESC_IDLE_US;
uint32_t lastTickMs = 0;
uint32_t armButtonPressedSinceMs = 0;

uint32_t pulseUsToDuty(uint16_t pulseUs) {
  const uint32_t maxDuty = (1UL << ESC_PWM_RESOLUTION_BITS) - 1;
  return (static_cast<uint32_t>(pulseUs) * maxDuty) / 20000UL;
}

void writeEsc(uint8_t channel, uint16_t pulseUs) {
  ledcWrite(channel, pulseUsToDuty(pulseUs));
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
  }
}

uint16_t readRequestedThrottleUs() {
  // 遥控/BLE/LoRa 输入占位；真实输入链路完成前保持空闲油门。
  return ESC_IDLE_US;
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

  writeEsc(LEFT_ESC_CHANNEL, ESC_IDLE_US);
  writeEsc(RIGHT_ESC_CHANNEL, ESC_IDLE_US);

  Serial.println("Smart SUP controller booted; state=DISARMED");
}

void loop() {
  updateArmState();

  const uint32_t now = millis();
  if (now - lastTickMs < CONTROL_TICK_MS) {
    return;
  }
  lastTickMs = now;

  const uint16_t requested = armed ? readRequestedThrottleUs() : ESC_IDLE_US;
  const uint16_t target = constrain(requested, ESC_IDLE_US, ESC_MAX_US);

  leftPulseUs = rampToward(leftPulseUs, target);
  rightPulseUs = rampToward(rightPulseUs, target);

  writeEsc(LEFT_ESC_CHANNEL, leftPulseUs);
  writeEsc(RIGHT_ESC_CHANNEL, rightPulseUs);
  digitalWrite(STATUS_LED_PIN, armed ? HIGH : (now / 500) % 2);
}
