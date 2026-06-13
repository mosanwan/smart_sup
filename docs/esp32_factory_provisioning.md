# ESP32 出厂编号刷入流程

## 目标

每个 ESP32 控制器在出厂第一次 USB 刷入时分配一个固定三位编号。编号保存到 ESP32 NVS/flash，后续 App 控制、App OTA 固件更新都不能修改这个编号。

蓝牙名称格式：

```text
SmartSUP-000
SmartSUP-001
SmartSUP-002
```

## 编号台账

项目用 `config/esp32_unit_registry.json` 记录当前最大编号和已分配编号。初始状态 `current_max_id` 为 `-1`，第一次分配会得到 `000`。

这个文件需要跟随源码提交，避免多台 ESP32 重复使用同一个编号。

## 出厂刷入

连接待刷入 ESP32 后运行：

```bash
tools/provision_esp32_unit.py --flash --port /dev/ttyUSB0
```

脚本会：

1. 读取 `config/esp32_unit_registry.json`。
2. 分配下一个编号，例如 `000`。
3. 更新编号台账。
4. 使用 `SMART_SUP_FACTORY_UNIT_ID=<编号>` 编译并通过 USB 上传固件。

只分配编号但不刷入：

```bash
tools/provision_esp32_unit.py --note "reserved for bench controller"
```

指定编号刷入：

```bash
tools/provision_esp32_unit.py --id 007 --flash --port /dev/ttyUSB0
```

## 固件行为

ESP32 启动时按下面顺序决定编号：

1. 如果 NVS 已经有 `unit_id`，直接使用 NVS 编号。
2. 如果 NVS 没有编号，且固件编译时带有 `SMART_SUP_FACTORY_UNIT_ID=0..999`，将该编号写入 NVS。
3. 如果两者都没有，则使用未出厂配置的临时默认编号 `000`，状态会标记为未 provisioned。

普通 Release/OTA 固件不带出厂编号参数，因此不会覆盖已写入的 NVS 编号。

## 查询

可以通过 USB 串口或经典蓝牙 SPP 查询：

```text
ID?
```

响应示例：

```text
ID;VALUE=001;BT=SmartSUP-001;SRC=NVS;PROVISIONED=1
```

`ID_SET` 命令不再用于生产流程，固件会返回：

```text
ID;ERR=FACTORY_ONLY
```
