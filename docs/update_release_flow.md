# GitHub 更新发布流程

## 目标

Android App 从 GitHub Release 检查自身更新，并从同一个 Release 下载 ESP32 固件，通过经典蓝牙 SPP 对 ESP32 执行 OTA 更新。

## Release 产物约定

仓库：`mosanwan/smart_sup`

每次发布 GitHub Release 时，在本地编译后至少上传：

| 产物 | 文件名建议 | 用途 |
| --- | --- | --- |
| Android APK | `smart-sup-controller-<version>.apk` | 手机 App 检测到新版本后下载并调用系统安装器 |
| ESP32 固件 | `smart-sup-esp32-firmware-<version>.bin` | 手机 App 通过经典蓝牙发送给 ESP32 |

App 会读取 GitHub public latest release API，并按文件后缀和关键字自动选择 `.apk` 与 `.bin` 资产。当前仓库已设置为 public，App 检查更新不需要 GitHub Token。

如果以后重新改回 private，GitHub 对未鉴权请求通常会返回 HTTP 404；那时需要重新设计 App 的鉴权方式，避免把长期 token 明文保存在手机端。

本地构建：

```bash
tools/build_release_assets.sh v0.2.3
```

输出目录：

```text
dist/smart-sup-controller-v0.2.3.apk
dist/smart-sup-esp32-firmware-v0.2.3.bin
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

协议：

```text
OTA_BEGIN;SIZE=<bytes>;MD5=<32 hex chars>\n
<raw firmware bytes>
```

ESP32 行为：

- 只有收到 OTA 开始命令后才进入固件更新状态。
- 进入 OTA 前强制锁定，左右 ESC 立即回 1500us 中位/空闲脉宽。
- 写入完成后校验 MD5，通过后重启。
- OTA 过程中如果长时间没有收到数据，会中止更新并保持锁定。

注意：ESP32 必须先通过 USB 刷入带 OTA 分区表的版本，后续才可以通过蓝牙更新 App 下载的 `firmware.bin`。

ESP32 三位硬件编号属于出厂首次 USB 刷入配置，普通 Release/OTA 固件不带 `SMART_SUP_FACTORY_UNIT_ID`，不会覆盖已有编号。

## 安全边界

- 固件更新前必须确认推进器处于安全状态，建议断开推进功率或拆桨。
- OTA 过程中 App 停止发送油门心跳，ESP32 保持锁定。
- 更新失败后不要进行下水测试，先通过 USB 串口或蓝牙状态确认固件仍可正常启动。
