# GPS 实时定位、轨迹记录和回放方案

## 目标

GPS 第一版只承担定位、轨迹缓存、手机同步和历史回放，不参与推进解锁、油门输出或安全保护闭环。GPS 异常不得影响手动推进控制；后续如果把 GPS 用于自动导航，需要单独增加退出自动导航和人工接管规则。

## 记录策略

ESP32 以 `1Hz` 记录 GPS 轨迹点。长期存储的用户数据只包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| UTC 时间 | `uint32_t` | GPS UTC Unix 秒 |
| 纬度 | `int32_t` | `纬度 * 1e7` |
| 经度 | `int32_t` | `经度 * 1e7` |

固件内部每条记录额外保留 `session_id` 和 `seq`，用于环形覆盖、断电后扫描和 Android 增量同步。这些字段不作为用户轨迹内容展示。

当前建议的 flash 单点存储单元为 `16 字节`：

```text
utc_seconds: uint32_t
lat_e7:      int32_t
lon_e7:      int32_t
session_id:  uint8_t
seq24:       uint8_t[3]
```

`session_id` 每次 ESP32 上电递增一次，用于区分不同上电周期。`seq24` 是轨迹记录递增序号，用于同步和判断新旧。

## 有效点过滤

只有同时满足以下条件时，ESP32 才写入轨迹：

- NMEA `RMC` 状态为有效定位。
- NMEA `GGA` fix quality 大于 `0`。
- GPS 卫星数不少于 `4`。
- UTC 时间有效，并且比上一条已记录点更新。
- 经纬度解析成功，且不为 `0,0`。
- 经纬度在合法范围内。
- 相对上一条已记录点推算速度不超过 `5 m/s`。

如果超过 `30 秒`没有有效点，下一次重新定位时先等待 `2-3` 个稳定点，再继续记录。Android 回放时把这段显示为断线，不强行把断点前后连成一条直线。

## 漂移过滤

漂移过滤用两点距离和 GPS 时间差计算：

```text
dt = 新点 UTC - 上一条已记录点 UTC
speed = distance_m / dt
```

当 `speed > 5 m/s` 时，认为新点是 GPS 漂移点，不写入 flash。`5 m/s` 约等于 `18 km/h`，略高于当前实测极限速度 `15 km/h`。

## 存储分区

ESP32 4M Flash 当前保留双 OTA App 分区。GPS 轨迹使用独立 raw data 分区 `tracklog`，替代原 `spiffs` 空间：

```text
tracklog, data, 0x40, 0x310000, 0xF0000
```

`0xF0000` 约为 `960 KiB`。按 `16 字节/点` 计算，可存：

```text
983040 / 16 = 61440 点
```

1 秒记录 1 点时，约可保留：

```text
61440 秒 = 17.1 小时
```

注意：修改分区表后，已经刷过旧分区表的 ESP32 不能只靠普通 OTA 切换到 `tracklog` 分区布局。首次启用 GPS 轨迹缓存时，建议通过 USB/串口完整刷入固件和新分区表。

## 存满策略

ESP32 轨迹分区只作为最近轨迹缓存。存满后自动环形覆盖最旧记录，不停止记录。

Android App 负责长期保存。手机连接后按 `seq` 增量同步 ESP32 上尚未同步的点。如果 Android 上次同步位置已经被覆盖，App 只能从 ESP32 当前最旧点继续同步，并提示“ESP32 上较早轨迹已被覆盖”。

## 分区初始化和脏数据处理

`tracklog` 是 raw flash 分区，首次从旧分区布局或未擦除 flash 切换过来时，分区内可能残留随机字节。固件启动扫描不能只用 UTC、经纬度合法范围判断记录有效，还必须检查 `seq` 是否形成连续区间。

如果扫描到的候选记录数量很少但 `seq` 跨度明显不连续，或最新记录时间阻塞当前 GPS 时间写入，应按脏分区处理：擦除整个 `tracklog` 分区，重置 `COUNT/OLDEST/NEWEST/NEXT`，从下一条有效 GPS 点重新开始记录。该处理只影响 GPS 轨迹缓存，不改变推进解锁、油门输出或安全保护状态。

## 蓝牙同步协议

同步继续使用经典蓝牙 SPP，不增加 Wi-Fi。

查询日志状态：

```text
LOG_INFO
```

响应：

```text
LOG_INFO;REC=16;CAP=61440;COUNT=1200;OLDEST=1;NEWEST=1200;SESSION=12;RATE=1
```

读取轨迹点：

```text
LOG_READ;FROM=1001;LIMIT=256
```

响应为多行：

```text
LOG_BEGIN;FROM=1001;COUNT=32
LOG_POINT;SEQ=1001;SID=12;T=1781510400;LAT=221234567;LON=1131234567
...
LOG_END;NEXT=1033
```

`FROM` 是 `seq`。`LIMIT` 调试加速阶段允许 `1..256`，Android 默认用 `256` 批量拉取历史轨迹。这个值会让单次蓝牙输出更长，历史大批量同步建议在主控锁定或低风险状态下执行；如后续实测影响控制心跳，应回退到 `64` 或改为更高效的二进制/压缩同步。Android 应小批量重复读取，直到 `NEXT > NEWEST`。固件读取时应从 `FROM` 对应的环形缓冲偏移附近开始顺序输出，不能每批都从最旧记录线性扫描到 `FROM`；否则大批量同步会随着 `FROM` 增大逐批变慢。若固件为了避免预扫描而无法提前知道本批实际点数，`LOG_BEGIN;COUNT=0` 可表示本批数量未知，Android 以实际收到的 `LOG_POINT` 和 `LOG_END;NEXT` 为准。

## Android 功能

Android App 负责：

- 显示实时 GPS 状态和当前位置。
- 使用 MapLibre Android SDK 渲染地图，底图使用 MapTiler 的 satellite/hybrid 免费方案，避免 Google billing/信用卡依赖。
- 自动同步 ESP32 `tracklog` 分区中的轨迹点。
- 本地长期保存轨迹。
- 按轨迹列表选择、删除和回放轨迹。

Android 本地保存仍以点为基本单位。回放界面按连续时间片段把点归组成轨迹列表：相邻 GPS 点的 UTC 时间间隔不超过 `2 小时` 时归为同一条轨迹；如果间隔超过 `2 小时`，视为下一条轨迹。选择某条轨迹后，地图和时间轴只播放该轨迹；删除轨迹时只删除该轨迹包含的点，不影响其他轨迹。

地图 API Key 通过 Gradle `BuildConfig` 配置，不写入源码。没有 API Key 时 App 仍可编译，但地图页需要提示未配置。

本地调试可用下面任一方式传入 API Key：

```bash
MAPTILER_API_KEY=你的Key ./gradlew :app:assembleDebug
```

或在 Android 工程的 `local.properties` 中加入：

```properties
MAPTILER_API_KEY=你的Key
```

## 安全边界

- GPS 轨迹记录不得改变上电默认锁定、失联回空闲、低电压保护和 IMU 异常保护。
- GPS 无定位、定位跳变或轨迹分区读写异常，不得触发解锁或油门变化。
- 同步轨迹时不能阻塞 20ms 控制周期。
- 蓝牙同步命令不能代替控制心跳；失联保护仍按控制命令超时执行。
