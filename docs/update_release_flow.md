# GitHub 更新发布流程

## 目标

Android App 从 GitHub Release 检查自身更新，并从同一个 Release 下载 ESP32 固件，通过经典蓝牙 SPP 对 ESP32 执行 OTA 更新。

## Release 产物约定

仓库：`mosanwan/smart_sup`

每次发布 GitHub Release 时，在本地编译后至少上传 Android APK。ESP32 固件只有在 `firmware/esp32/` 相对上一个 tag 有变化时才默认上传，避免 App-only 更新也触发主控 OTA。

| 产物 | 文件名建议 | 用途 |
| --- | --- | --- |
| Android APK | `smart-sup-controller-<version>.apk` | 手机 App 检测到新版本后下载并调用系统安装器 |
| ESP32 固件 | `smart-sup-esp32-firmware-<version>.bin` | 可选；手机 App 通过经典蓝牙发送给 ESP32 |
| 发布清单 | `smart-sup-release-<version>.json` | 可选；必须和固件一起上传，App 校验 ESP32 固件资产名称、版本、板型、大小和 SHA-256 |

App 会读取 GitHub public latest release API，并按文件后缀和关键字自动选择 `.apk` 与 `.bin` 资产。没有固件发布清单的 Release 只作为 App 更新，不提供 GitHub 主控 OTA。当前仓库已设置为 public，App 检查更新不需要 GitHub Token。

ESP32 固件更新必须优先使用同一 Release 中的发布清单。清单示例：

```json
{
  "version": "v0.2.8",
  "firmware": {
    "asset": "smart-sup-esp32-firmware-v0.2.8.bin",
    "version": "v0.2.8",
    "board": "lolin32_lite",
    "size": 1048576,
    "sha256": "<64 hex chars>",
    "minAppVersion": "0.2.8"
  }
}
```

App 下载固件后先校验 `asset`、`board`、`size` 和 `sha256`，校验通过才允许进入蓝牙 OTA。清单缺失时，App 可以显示 Release 信息，但不得从 GitHub 自动刷写 ESP32 固件；本地选择 `.bin` 仍保留给开发调试。

本地调试文件名建议保持 `smart-sup-esp32-firmware-<version>.bin`。App 会从该文件名提取目标版本，OTA 后重连时用 ESP32 上报的 `FW=<version>` 做成功确认。

如果以后重新改回 private，GitHub 对未鉴权请求通常会返回 HTTP 404；那时需要重新设计 App 的鉴权方式，避免把长期 token 明文保存在手机端。

本地构建：

```bash
tools/build_release_assets.sh v0.2.3
```

输出目录：

```text
dist/smart-sup-controller-v0.2.3.apk
dist/smart-sup-esp32-firmware-v0.2.3.bin   # 仅 firmware/esp32 有变化时生成
dist/smart-sup-release-v0.2.3.json          # 仅生成固件时生成
```

`tools/build_release_assets.sh <version>` 默认使用 `SMART_SUP_RELEASE_FIRMWARE=auto`：

- `auto`：相对上一个 tag 检查 `firmware/esp32/`，有变化才构建固件。
- `always`：强制构建并上传主控固件。
- `never`：强制跳过主控固件，只发 App。

示例：

```bash
SMART_SUP_RELEASE_FIRMWARE=always tools/build_release_assets.sh v0.2.3
SMART_SUP_RELEASE_FIRMWARE=never tools/build_release_assets.sh v0.2.3
```

构建固件时，脚本会把同一个版本号写入 ESP32 固件内部的 `SMART_SUP_VERSION`。不要只重命名 `.bin` 文件；手动编译调试固件时使用：

```bash
SMART_SUP_VERSION=v0.2.3 pio run -e lolin32_lite
```

本地上传到 GitHub Release：

```bash
tools/upload_release_assets.sh v0.2.3
```

该命令依赖本机已安装并登录 GitHub CLI：

```bash
gh auth login
```

## App 更新

- App 使用当前安装版本与 Release tag 做简单语义版本比较，例如 `v0.2.0` 高于 `0.1.0`。
- 下载 APK 后保存到 App cache，并通过 Android 系统安装器安装。
- Android 可能要求用户授予“允许安装未知来源应用”的权限。
- Android 自更新要求新 APK 与当前已安装 APK 使用同一个签名。正式使用时，需要在本地构建环境配置同一套签名密钥：
  - `SMART_SUP_ANDROID_KEYSTORE`
  - `SMART_SUP_ANDROID_KEYSTORE_PASSWORD`
  - `SMART_SUP_ANDROID_KEY_ALIAS`
  - `SMART_SUP_ANDROID_KEY_PASSWORD`
- 如果没有配置签名密钥，`tools/build_release_assets.sh` 会产出 debug APK，只适合本机调试；它通常不能覆盖安装其他 debug keystore 或 release keystore 签出的 App。

## ESP32 固件更新

蓝牙 OTA 使用当前经典蓝牙 SPP 连接，不需要 Wi-Fi。

连接后 App 先查询控制器信息：

```text
INFO?
```

响应格式：

```text
INFO;FW=<firmware-version>;ID=<unit-id>;BT=<bt-name>;ID_SRC=<source>;OTA=1
```

ESP32 每秒 `STATUS` 状态行也必须带 `FW=<firmware-version>`，用于 App 重连后确认 OTA 是否真正生效。

OTA 协议优先使用分块确认模式。App 开始时请求 `PROTO=2`：

```text
OTA_BEGIN;SIZE=<bytes>;MD5=<32 hex chars>;PROTO=2;CHUNK=1024\n
```

如果 ESP32 回包中带 `PROTO=2;CHUNK=<bytes>`，App 必须按块发送并等待每块确认：

```text
OTA_CHUNK;OFFSET=<written>;LEN=<chunk-bytes>;CRC=<crc32-decimal>\n
<chunk binary bytes>
```

分块状态回包：

```text
OTA;READY;SIZE=<bytes>;PROTO=2;CHUNK=<bytes>
OTA;ACK;OFFSET=<written>;CRC=<crc32-decimal>
OTA;NACK;OFFSET=<expected>;ERR=<reason>
OTA;PROGRESS=<written>/<bytes>
OTA;OK;BYTES=<bytes>
OTA;ERR=<reason>
```

只有旧固件没有在 `OTA;READY` 中声明 `PROTO=2` 时，App 才回退到旧的连续字节流：

```text
OTA_BEGIN;SIZE=<bytes>;MD5=<32 hex chars>\n
<raw firmware bytes>
```

旧协议状态回包：

```text
OTA;READY;SIZE=<bytes>
OTA;PROGRESS=<written>/<bytes>
OTA;OK;BYTES=<bytes>
OTA;ERR=<reason>
```

ESP32 行为：

- 只有收到 OTA 开始命令后才进入固件更新状态。
- 进入 OTA 前强制锁定，左右 ESC 立即回 1500us 中位/空闲脉宽。
- 写入完成后校验 MD5，通过后连续发送 `OTA;OK`，再短暂等待并重启。
- 分块 OTA 中，ESP32 先对整块数据做 CRC32 校验，校验通过才写入 flash；`NACK` 表示该块没有写入，App 可以重发。
- OTA 过程中如果长时间没有收到数据，会中止更新并保持锁定。
- OTA 过程中经典蓝牙链路进入独占模式，App 不得发送普通控制命令、轨迹同步或校准命令。
- App 必须等待 `OTA;READY` 后才发送固件二进制，等待 `OTA;OK` 后才认为 ESP32 已接受固件；如果二进制已经写满但蓝牙因 ESP32 重启先断开，App 可以进入重连验证状态。
- ESP32 重启并重新连接后，App 必须看到 `FW` 等于目标版本才把 OTA 标记为成功。

开发调试时，设置页保留“测试固定路径 OTA”按钮。它会从 App 外部文件目录和 `Download` 中选择版本号最高的 `smart-sup-esp32-firmware-*.bin`，用于跳过系统文件选择器直接验证 OTA。

注意：ESP32 必须先通过 USB 刷入带 OTA 分区表的版本，后续才可以通过蓝牙更新 App 下载的 `firmware.bin`。

ESP32 三位硬件编号属于出厂首次 USB 刷入配置，普通 Release/OTA 固件不带 `SMART_SUP_FACTORY_UNIT_ID`，不会覆盖已有编号。

## 安全边界

- 固件更新前必须确认推进器处于安全状态，建议断开推进功率或拆桨。
- OTA 过程中 App 停止发送油门心跳，ESP32 保持锁定。
- 更新失败后不要进行下水测试，先通过 USB 串口或蓝牙状态确认固件仍可正常启动。
