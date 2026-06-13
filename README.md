# 智能桨板

智能桨板项目，用 ESP32 控制双无刷推进系统，并围绕电池、电调、水冷灌胶、遥控/传感和安全联锁逐步迭代。

## 当前硬件方案

| 模块 | 方案 |
| --- | --- |
| 电池 | 7S8P 三元锂电池组 |
| 保护 | 60A 保护板 |
| 电机 | 2 x 6010 无刷电机，170KV |
| 电调 | 2 x 100A ESC，灌胶后水下水冷 |
| 控制器 | ESP32，参考 hallard/LoLin32-Lite-Lora |
| 参考资料 | 放在 `ref/`，包括图片、文档、规格书和实验记录 |

## 目录结构

```text
.
├── docs/                 # 设计记录、安全清单、硬件方案
├── firmware/esp32/       # ESP32 固件起步工程
├── ref/                  # 外部参考资料、图片、规格书
└── tools/                # 后续脚本工具
```

## 开发入口

固件工程使用 PlatformIO 起步：

```bash
cd firmware/esp32
pio run
```

Ubuntu 首次使用时需要确认本机已安装 PlatformIO，并且当前用户有串口权限：

```bash
python3 -m pip install --user platformio
sudo usermod -aG dialout "$USER"
```

加入 `dialout` 组后需要重新登录终端会话。ESP32 连接后可用下面命令查看串口名：

```bash
pio device list
```

ESP32 出厂硬件编号用项目台账分配，并在第一次 USB 刷入时固定到 NVS/flash：

```bash
tools/provision_esp32_unit.py --flash --port /dev/ttyUSB0
```

编号流程见 [ESP32 出厂编号刷入流程](docs/esp32_factory_provisioning.md)。

默认固件只做安全的 ESC PWM 输出骨架：上电保持未解锁，需要明确解锁后才允许输出高于空闲值的油门。

## 接线图

- [详细接线图](docs/wiring_diagram.md)

## 重要安全边界

这是高能量电池和大功率推进系统，第一次水下或满功率测试前，请至少完成：

- 电池组、BMS、线束、保险、主开关、急停、充电口的电流和温升校核。
- ESC 灌胶后散热验证，避免只在静水中依赖理论水冷。
- 推进器物理防护，避免空载误转和近人测试。
- 软件默认上电锁定，通信丢失、低电压、过温、过流时进入安全油门。
- 岸上低压限流测试，再进水低功率测试，最后逐级升功率。

## 参考链接

- [hallard/LoLin32-Lite-Lora](https://github.com/hallard/LoLin32-Lite-Lora)
