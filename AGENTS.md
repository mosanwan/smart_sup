# 智能桨板 Agent 指南

## 项目背景

这个仓库用于智能桨板项目，当前硬件方案包括：

- 7S8P 三元锂电池组
- 60A 电池保护板 / BMS
- 2 个 6010 无刷电机，170KV
- 2 个 100A 电调，计划灌胶后放在水下水冷
- ESP32 控制器，参考 `hallard/LoLin32-Lite-Lora`

外部参考资料统一放在 `ref/`。

## 工作规则

- 涉及硬件、电池、电调、电机、防水和散热的修改，都按安全敏感变更处理。
- 修改架构、固件行为或硬件假设前，先阅读 `README.md`、`docs/system_overview.md`、`docs/hardware_plan.md` 和 `docs/safety_checklist.md`。
- 设计假设先写进 `docs/`，再落实到固件代码里。
- 保持分阶段测试思路：岸上低功率、单电机、双电机、短时下水测试，再到更长时间和更高功率测试，并记录日志。
- 后续对话、文档、注释和项目说明优先使用中文；必要的技术缩写和库名可以保留英文。

## 固件规则

- ESP32 固件必须默认上电未解锁。
- 系统明确解锁前，ESC 输出必须保持空闲脉宽。
- 遥控输入丢失、传感器异常、低电压、过温或看门狗复位时，系统应退回空闲油门或保守限功率状态。
- 除非同步更新 `docs/hardware_plan.md`，不要占用 LoLin32-Lite-LoRa 参考资料中已经用于 LoRa/I2C 的引脚。
- PWM 假设必须明确记录：频率、空闲脉宽、最大脉宽、校准方式和刷新周期。

## Android 规则

- Android 本地 AI/ASR 模型会显著增大 App 体积，开发和验证阶段优先使用本机直连安装。
- 当检测到 Android 手机通过 USB 连接到电脑并可被 `adb devices` 识别时，直接用 Gradle/ADB 安装到手机测试，不必走 GitHub Release 发布流程。
- 只有在需要正式分发、留存版本归档，或用户明确要求时，才按 `docs/update_release_flow.md` 走 GitHub Release。

## 仓库结构

```text
docs/                 项目设计、硬件方案、安全清单
android/              Android 控制端 App
firmware/esp32/       ESP32 PlatformIO 固件
ref/                  规格书、照片、手册和外部参考资料
tools/                辅助脚本和数据分析工具
.codex/skills/        项目级 Codex 技能
```

## 验证方式

- 安装 PlatformIO 后，在 `firmware/esp32` 下运行 `pio run` 编译固件。
- 如果本机缺少某个命令或工具，最终回复里要明确说明。
