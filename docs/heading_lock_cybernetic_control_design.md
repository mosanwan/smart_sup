# 航向锁定工程控制论设计

## 设计目标

目标不是“更猛地反打”，而是让航向锁定闭环变成阻尼足够的稳定系统：

- 航向误差 `e` 收敛到容差内。
- 船头角速度 `r` 在接近目标前被提前压低。
- 不因反打形成反向过冲和来回振荡。
- 在传感异常、方向错误、高推力或水流扰动下进入保守状态。

## 控制论五要素

| 要素 | 本项目定义 |
| --- | --- |
| 期望状态 `Reference` | `targetHeading`，并期望 `e = 0`、`r = 0` |
| 被控对象 `Plant` | 桨板船体 yaw 动力学 + 双电机差速 + ESC/PWM 斜率限制 + 水流扰动 |
| 执行器 `Actuator` | 左右 ESC 输出，分解为基础推力 `b` 和差速 `d` |
| 传感器 `Sensor` | 航向 `heading`、角速度 `YBGZ` 或航向差分、加速度扰动线索 |
| 控制器 `Controller` | ESP32 固件快速锁航控制器；Android 只做监督、目标生成和参数下发 |

闭环信号流：

```text
targetHeading
    │
    ↓
[比较器] e = target - heading
    │
    ├──────────────┐
    ↓              │
[阻尼控制器] ←── r = headingRate
    │
    ↓
[限幅/死区/安全门控]
    │
    ↓
L/R 输出 -> ESC -> 电机 -> 船体 yaw
    │                         │
    └──── heading / YBGZ / accel 反馈
```

## 控制器归属

锁航动态控制必须放在 ESP32 固件里，而不是放在 Android App 里。原因是提前反打依赖 `YBGZ`、航向差分和 PWM 斜率的快速闭环；如果经由手机传感器、蓝牙心跳和 App 调度再下发最终 `L/R`，控制频率和延迟都不可控，容易把“提前反打”变成“滞后反打”。

职责划分：

| 模块 | 职责 |
| --- | --- |
| Android App | 用户交互、目标航向设定、基础推力/档位、限幅参数、控制心跳、日志显示 |
| ESP32 固件 | 读取主控 IMU、计算 `e/r/e_pred`、执行 PD 阻尼和动态 `BRAKE`、输出左右 ESC |
| Android 自动导航 | 保存路线和计算上层目标航向，不直接闭合快速 yaw 环 |

单一控制器规则：

- 固件 `HLOCK` 激活时，Android 不得再本地计算锁航最终 `L/R`。
- Android 只发送 `targetHeading`、`basePercent`、限幅和心跳；固件每个 `20ms` 控制周期重算差速。
- 固件锁航只使用主控 IMU 快速数据。`YBIMU=1` 且 `YBY/YBGZ` 新鲜有效才允许启用提前反打。
- 手机指南针不进入固件快速反打闭环；它可以用于 UI 显示、人工参考，或后续作为低速上层目标来源。
- 心跳超时、IMU 超时、故障或用户取消时，固件必须退出 `HLOCK` 并回空挡或锁定。

## 变量定义

```text
heading          当前船头航向，单位 deg
targetHeading    目标航向，单位 deg
e                航向误差 = shortestCompassError(targetHeading, heading)，范围 -180..180 deg
r                当前船头角速度，单位 deg/s；航向增加为正
b                基础推力 = (leftBase + rightBase) / 2
d                差速控制量，正值表示让航向增加，负值表示让航向减小
left             b + d
right            b - d
```

角速度 `r` 的来源优先级：

1. 主控 IMU `YBGZ`，必须先确认符号和航向增加方向一致。
2. 航向差分：`r = shortestCompassError(headingNow, headingPrev) / dt`。
3. 两者都不可用时，不启用提前反打，只保留当前比例锁航。

加速度计不直接决定 yaw 反打方向，只作为扰动观测：

- 加速度模长或横向加速度异常时，降低反打强度。
- 出现冲击/安装松动迹象时，不做参数学习。

## 被控对象灰箱模型

桨板 yaw 动力学可按二阶阻尼系统近似：

```text
J * yawAcceleration + D * yawRate = K * d_effective + W
```

其中：

- `J`：船体转动惯量，随载重变化。
- `D`：水阻尼，随速度、桨叶、水流变化。
- `K`：差速到 yaw 加速度的增益。
- `W`：水流、风、波浪、左右电机不一致等总扰动。
- `d_effective`：经过 ESC 斜率限制、死区、输出限幅后的实际差速。

控制器不能假设 `J/D/K` 固定，所以第一版采用灰箱策略：

- 快回路：用 `e` 和 `r` 做阻尼控制。
- 慢回路：用日志辨识 `K/D/延迟/死区`，再调整参数。
- 安全回路：任何方向错误、传感超时、误差发散都取消锁航。

## 控制律

核心控制律用 PD 阻尼，而不是单纯“误差越大推越大”：

```text
d_pd = Kp * e - Kd * r
```

含义：

- `Kp * e`：误差项，负责把船头推向目标。
- `-Kd * r`：阻尼项，负责在船头已经朝目标快速转动时减小推力，必要时提前反打。

这回答了“提前反打怎么算”：不是等 `e` 变号，而是当 `Kd * r` 大于 `Kp * e` 时，`d_pd` 会自然变成反向。

预测项用于提前判断是否进入刹车模式：

```text
e_pred = e - r * lookAheadSeconds
needBrake =
    sign(e) == sign(r) &&
    abs(r) >= minBrakeRateDegS &&
    (sign(e_pred) != sign(e) || abs(e_pred) <= brakeWindowDeg)
```

`sign(e) == sign(r)` 表示船头正在朝目标方向转。若预测误差 `e_pred` 将越过 0 或进入刹车窗口，就进入 `BRAKE`。

## 反打强度

反打强度随角速度动态调整：

```text
rateRatio = clamp(
    (abs(r) - minBrakeRateDegS) / (fullBrakeRateDegS - minBrakeRateDegS),
    0,
    1
)

brakePercent = minEffectiveBrakePercent +
    (maxBrakePercent - minEffectiveBrakePercent) * rateRatio

d_brake = -sign(r) * brakePercent
```

初始建议：

| 参数 | 初始值 |
| --- | --- |
| `minBrakeRateDegS` | `8°/s` |
| `fullBrakeRateDegS` | `35°/s` |
| `minEffectiveBrakePercent` | `20%` |
| `maxBrakePercent` | `35%` |

因为电机 `9%` 才刚起转，反打不能落在 `10%-15%` 这种死区边缘。

## 反打时间

反打时间也随角速度动态调整，但必须每个控制周期重算，不能发一次固定延时命令。

```text
brakeHoldTargetMs = minBrakeHoldMs +
    (maxBrakeHoldMs - minBrakeHoldMs) * rateRatio
```

初始建议：

| 参数 | 初始值 |
| --- | --- |
| `minBrakeHoldMs` | `250ms` |
| `maxBrakeHoldMs` | `800ms` |
| 控制刷新周期 | 固件 PWM 控制周期 `20ms`；状态上报可低频 |

退出 `BRAKE` 的条件优先级：

```text
abs(r) <= settleRateDegS
OR sign(r) != brakeStartRateSign
OR brakeElapsedMs >= brakeHoldTargetMs
OR brakeElapsedMs >= maxBrakeHoldMs
OR 航向源/角速度源失效
```

所以反打不会被设计成“打满 800ms”。`800ms` 是硬上限；角速度降下来或变号时必须立刻停。

## 会不会反打也打过头

会，所以设计上必须把反打视为另一个可能发散的闭环，而不是一次性动作。

防反打过头的稳定性措施：

1. **角速度负反馈**：`-Kd * r` 本质上是阻尼项，角速度越大反向抑制越强。
2. **每周期重算**：`BRAKE` 按 ESP32 固件控制周期重新判断，不做长延时盲打；状态上报可以低频显示。
3. **变号即停**：如果 `r` 变号，说明船头已经开始反向转，必须退出反打。
4. **低速即停**：如果 `abs(r) <= settleRateDegS`，进入 `SETTLE`，输出 0 或很小修正。
5. **无积分项**：反打不累积历史误差，避免越打越大。
6. **硬限幅**：`maxBrakePercent`、`maxBrakeHoldMs`、单侧输出上限均不可突破。
7. **高推力禁止主动反推**：`abs(b) >= 70%` 时反打只能减小快侧或把慢侧拉到 0，不允许突然反向。

## 状态机

只保留三个状态，避免复杂状态机：

| 状态 | 控制策略 |
| --- | --- |
| `CORRECT` | 正常 `d = Kp * e - Kd * r`，受最小有效输出和限幅约束 |
| `BRAKE` | 预测将过冲时，按 `d_brake` 给提前反向力 |
| `SETTLE` | 误差小、角速度低时输出 0 或很小修正，等待稳定 |

切换：

```text
CORRECT -> BRAKE:
  needBrake == true

BRAKE -> SETTLE:
  abs(r) <= settleRateDegS 或 sign(r) 变号

BRAKE -> CORRECT:
  brakeElapsedMs >= brakeHoldTargetMs 且 abs(e) > toleranceDeg

SETTLE -> CORRECT:
  abs(e) > toleranceDeg
```

## 多变量解耦

推进和转向必须解耦：

```text
b = 基础前进/后退推力
d = 航向控制差速
left = b + d
right = b - d
```

控制器只调 `d`，不直接改 `b`。这样普通档位、声控档位、自动导航基础推力不会和锁航阻尼互相污染。

限幅顺序：

1. 计算 `d_raw`。
2. 应用死区补偿：小于有效起转区的反打提升到 `minEffectiveBrakePercent`。
3. 应用 `abs(b) < 70%` 允许反推规则。
4. 应用单侧输出上限和语音/手动来源限幅。
5. 应用 ESC 反向设置。

## 扰动抑制

扰动统一看作 `W`：

- 水流和风：表现为同向误差长期不收敛。
- 左右电机推力不一致：表现为同样 `d` 下角速度响应偏差。
- 波浪/碰撞/手机晃动：表现为加速度异常或航向突变。
- ESC 斜率限制：表现为控制命令和实际角速度响应有延迟。

补偿分级：

| 级别 | 信号 | 补偿 |
| --- | --- | --- |
| 正常 | 误差收敛，角速度可解释 | 正常 PD + 预测刹车 |
| 轻度 | 收敛慢但方向正确 | 允许自适应补偿小幅增加 |
| 中度 | 预测刹车后仍过冲明显 | 降低 `Kp`、增加 `Kd` 或提前量，保留低功率 |
| 重度 | 误差持续扩大、角速度方向异常、传感超时 | 取消锁航、锁定并回空挡 |

进入 `BRAKE` 时，自适应补偿冻结或衰减，不能继续给同向推力。

## 系统辨识

先不要追求精确模型，先辨识几个控制必需参数：

| 参数 | 怎么测 |
| --- | --- |
| `deadzonePercent` | 水中左右分别输出，记录刚能产生可见角速度的百分比 |
| `responseDelayMs` | `d` 阶跃变化后，`r` 开始明显变化的延迟 |
| `yawAccelPerPercent` | 固定 `d=20/30/35%`，记录 `r` 上升斜率 |
| `naturalDamping` | 取消差速后，记录 `r` 衰减到一半的时间 |
| `overshootDeg` | 30°/60° 转向自然过冲角 |

这些数据决定：

- `lookAheadSeconds`
- `Kp`
- `Kd`
- `minEffectiveBrakePercent`
- `maxBrakePercent`
- `brakeHoldMs`

## 初始参数建议

| 参数 | 初始值 | 调整方向 |
| --- | --- | --- |
| `Kp` | 由当前比例修正等效换算 | 过冲大就降，转不动就升 |
| `Kd` | 从小值开始 | 过冲大就升，反向摆动就降 |
| `lookAheadSeconds` | `0.6s` | 反打太晚升到 `0.8-1.2s` |
| `brakeWindowDeg` | `3°` | 过线才刹就增大 |
| `minEffectiveBrakePercent` | `20%` | 反打无效就升 |
| `maxBrakePercent` | `35%` | 刹不住可升到 `40%-45%`，先看电流温升 |
| `minBrakeHoldMs` | `250ms` | 电机来不及响应就升 |
| `maxBrakeHoldMs` | `800ms` | 反打过头就降 |
| `settleRateDegS` | `3°/s` | 停稳后还抖就增大 |

## 固件实现映射

现有 ESP32 固件已经有 `updateHeadingLockControl(now)` 入口和 `MODE=HEADING_LOCK` 命令解析骨架。新的实现应把该函数从比例差速升级为固件快速闭环：

1. `applyHeadingLockCommand()` 只保存目标航向、基础推力、限幅和请求编号，不直接生成最终输出。
2. `updateHeadingLockControl(now)` 每个控制周期读取 `headingDegrees`、`YBGZ` 或航向差分，计算 `e/r/e_pred`。
3. `CORRECT` 阶段计算 `d_pd = Kp * e - Kd * r`。
4. `BRAKE` 阶段按角速度动态计算 `d_brake` 和 `brakeHoldTargetMs`，并且每周期检查退出条件。
5. `SETTLE` 阶段在误差和角速度都低时输出 0 或很小修正，避免抖动。
6. 最终通过已有 ESC 死区、反向配置、单侧限幅、斜率限制、电压/电流/温度保护输出。

状态回包至少增加或保留：

- `HLOCK=ACTIVE`
- `HPHASE=CORRECT|BRAKE|SETTLE`
- `HERR`
- `HRATE`
- `HPRED`
- `HPD`
- `HBRK`
- `HCORR`
- `BMS` 或等效刹车剩余/目标时间字段

## 实施阶段

1. **固件观测模式**：ESP32 记录 `e/r/e_pred/phase建议/d建议`，但仍输出旧比例锁航或保持普通油门。
2. **启用固件 PD 阻尼但不主动反推**：只减少同向差速，观察过冲是否下降。
3. **低功率空档动态反打**：`min=20%`、`max=25%`、`maxHold=500ms`。
4. **提高到有效刹车**：逐步到 `max=35%`、`maxHold=800ms`。
5. **1档/2档前进锁航**：验证 `b/d` 解耦和 `abs(b)<70%` 反推规则。
6. **再考虑刹车阶段专用斜率**：如果 ESC 斜率导致反打到达太慢，再做固件安全变更。
7. **停用 App 本地快速锁航路径**：Android 只保留目标/基础推力/心跳，不再计算锁航最终 `L/R`。

## 可观测性

控制页或日志必须显示：

- `phase = CORRECT/BRAKE/SETTLE`
- `e`
- `r`
- `e_pred`
- `d_pd`
- `d_brake`
- `d_final`
- `brakeElapsedMs`
- `brakeHoldTargetMs`
- `left/right`
- `YBGZ`
- `YBAX/YBAY/YBAZ`

没有这些观测，不应继续调高反打。

## 硬安全边界

- 未解锁不输出。
- 航向源或角速度源超时，退出锁航。
- `abs(b) >= 70%` 不主动反推。
- 单次 `BRAKE` 不超过 `maxBrakeHoldMs`。
- 角速度变号立即退出 `BRAKE`。
- 误差持续扩大触发现有锁定保护。
- 电流、温度、电压异常时回空挡或限功率。
- Android 和 ESP32 不得同时闭合同一个锁航快速环；固件 `HLOCK` 激活时 Android 只能做监督和心跳。
