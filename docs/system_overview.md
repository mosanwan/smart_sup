# 系统总览

## 目标

构建一套用于智能桨板的双推进电控系统。第一阶段先完成安全可控的推进控制闭环：可靠上电、解锁、油门输出、失联保护、低电压保护和基础遥测。

## 高层架构

```mermaid
flowchart LR
    Battery["7S8P 三元锂电池组"] --> BMS["60A 保护板 / BMS"]
    BMS --> Power["主开关 / 保险 / 直流母线"]
    Power --> ESC_L["左侧 100A 电调"]
    Power --> ESC_R["右侧 100A 电调"]
    ESC_L --> Motor_L["左侧 6010 170KV 电机"]
    ESC_R --> Motor_R["右侧 6010 170KV 电机"]
    Controller["ESP32 控制器"] --> ESC_L
    Controller --> ESC_R
    Controller --> Sensors["电压 / 电流 / 温度 / 进水检测 / GPS"]
    Remote["ESP32-C3 SuperMini 遥控器 / ESP-NOW"] --> Controller
    Voice["Android 本地 ASR / 语音命令解析"] --> Android["Android App / 语音与轨迹维护"]
    Android -. "后续辅助链路" .-> Controller
    Controller --> TrackLog["GPS 轨迹缓存"]
    TrackLog --> Android
```

## 推荐迭代顺序

1. 建立固件工程和双 ESC PWM 输出，保持上电锁定。
2. 加入电池电压采样、ESC/电池温度采样和日志输出。
3. 加入遥控输入，并实现失联保护。
4. 做岸上限流低功率测试，确认解锁、油门曲线、急停、故障降功率。
5. 做水下低功率测试，记录 ESC 灌胶后温升。
6. 再逐步提高功率，并修正散热、线束和结构。

## 当前关键假设

- ESC 按双向 RC PWM 信号处理：约 1000us 最大后退、1500us 中位/空闲、2000us 最大前进。实际以电调说明书和低功率实测为准。
- 第一版实时遥控链路改为 ESP32-C3 SuperMini 遥控器通过 ESP-NOW 直连主控，命令格式、绑定方式和失联保护见 [ESP-NOW 遥控 MVP](espnow_control_mvp.md)。
- Android 本地语音控制只作为低速辅助输入，方案和限幅规则见 [Android 本地语音控制方案](voice_control_plan.md)；语音链路不作为第一版主遥控心跳。
- 航向锁定和角度转向仍优先使用 Android 手机指南针航向，由 App 本地计算目标航向、误差和左右 ESC 功率；ESP32 不再使用 IMU 磁力计做航向闭环，只执行最终左右功率并保留解锁、失联、限幅和 PWM 安全保护。手机必须固定在板体上且相对船体方向不变。航向策略需要按 ESP-NOW 主遥控链路重新接入。
- GPS 第一版只用于实时定位、1Hz 轨迹缓存、Android 同步和历史回放，不参与推进安全闭环；方案见 [GPS 实时定位、轨迹记录和回放方案](gps_track_plan.md)。
- 自动导航第一版只在 Android App 中保存路线和计算左右推进输出，ESP32 不保存路线、不独立规划路径；App 必须持续发送受限控制心跳，ESP32 继续负责失联、限幅和故障保护。方案见 [自动导航路线和执行方案](auto_navigation_plan.md)。
- 每个 ESP32 的三位硬件编号在出厂第一次 USB 刷入时写入 NVS/flash，流程见 [ESP32 出厂编号刷入流程](esp32_factory_provisioning.md)。
- Android App 和 ESP32 固件更新走 GitHub Release，流程见 [GitHub 更新发布流程](update_release_flow.md)。
- 两个 100A ESC 不应由 60A BMS 长时间满功率供电，系统持续功率需要按 BMS、线束、电芯放电能力和散热重新核算。
- ESP32 与 ESC 信号地需要可靠共地；ESC BEC 是否给 ESP32 供电需单独验证，优先使用独立降压模块。
