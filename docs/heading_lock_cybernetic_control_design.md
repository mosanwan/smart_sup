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
| ESP32 固件 | 读取主控 IMU、计算 `e/r/r_dot/e_pred`、执行航向外环、角速度内环、短时扰动补偿和动态 `BRAKE`、输出左右 ESC |
| Android 自动导航 | 保存路线和计算上层目标航向，不直接闭合快速 yaw 环 |

单一控制器规则：

- 固件 `HLOCK` 激活时，Android 不得再本地计算锁航最终 `L/R`。
- Android 启动、取消或更新锁航时发送 `MODE=HEADING_LOCK` 事件；锁航保持期间只发送 `MODE=KEEPALIVE` 保活，不得反复发送锁航启动命令。固件每个 `20ms` 控制周期重算差速。
- `HBOOST` 积分、`HFHAT` 观测和衰减都必须按固件控制周期执行，不能按主循环空转次数执行，否则补偿会在有效 IMU 时间步之间被过快清零。
- 固件锁航只使用主控 IMU 快速数据。`YBIMU=1` 且 `YBHDG/YBGZ` 新鲜有效才允许启用提前反打。
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

控制器不能假设 `J/D/K` 固定，也不能把某次水流和风向下测到的“转向效果”长期记成电机特性。实际实现中把模型压缩成短时可观测形式：

```text
r_dot = b0 * d_effective + f
```

其中：

- `r_dot`：船头角加速度，可由 `YBGZ` 差分并低通得到。
- `b0`：粗略执行器能力常量，只要求方向正确、量级保守，不追求精确。
- `f`：当前总扰动，包含水流、风、波浪、载重变化、电机非线性、电池电压下降和左右推进不一致。

第一版采用灰箱 + 短时扰动观测策略：

- 快回路：航向外环生成目标角速度，角速度内环控制 `YBGZ` 跟随。
- 扰动回路：只估计当前会话里的 `f_hat`，不跨环境长期保存。
- 离线辨识：只辨识硬件相对稳定边界，例如死区、斜率延迟、最大安全差速。
- 安全回路：方向错误、传感超时或扰动观测异常时冻结补偿并进入保守限幅；误差持续同向发散且船头仍在远离目标方向转动时回传 `HWARN=HEADING_LOCK_DIVERGED`，临时冻结 `HBOOST` 和转向修正，输出回到基础推力；船头停止远离目标或开始回目标后清零 `HBOOST` 并恢复差速修正，但不自动退出用户授权的锁航状态。

## 控制律

核心控制律改成“航向外环 + 角速度内环”，而不是直接用航向误差输出差速：

```text
e = shortestCompassError(targetHeading, heading)
r_ref = clamp(K_heading * e, -r_max, r_max)
```

`r_ref` 是期望船头角速度。误差大时允许更快转向，接近目标时自动降速。

角速度内环让实际 `r` 跟随 `r_ref`：

```text
r_err = r_ref - r
v = K_rate * r_err - K_accel * r_dot
```

含义：

- `K_heading * e`：决定“现在应该转多快”。
- `K_rate * r_err`：决定“为了达到目标角速度，需要多少角加速度”。
- `-K_accel * r_dot`：抑制角速度变化过快，减少过冲和抖动。

再用当前会话的短期补偿处理持续无响应：

```text
boostStep = K_i * r_err * dt
if abs(e) > quietHoldBand and abs(r_err) is persistent:
    boostStep = atLeast(abs(boostStep), minBoostRate * dt) * sign(r_err)
HBOOST += clamp(boostStep, -boostRateLimit * dt, boostRateLimit * dt)
d_raw = v + HBOOST
```

这里 `HBOOST` 是当前锁航会话内的角速度误差积分补偿，不是长期效果记忆。水流、风、电机非线性、电池变化、静止时的推力死区都会通过“有差速但角速度不足”表现为持续 `r_err`，从而让 `HBOOST` 在上限和变化率限制内逐步加差速。补偿不能慢到几十秒才越过有效区：误差超过安静保持带且角速度持续跟不上时，固件给 `HBOOST` 一个受限的最低爬升速度。短暂进入 `BRAKE`、角加速度尖峰或单个控制周期不满足更新条件时，不能按控制周期快速清空已积累的补偿，否则会出现差速从高值突然掉回起转门槛；这类瞬时门控应冻结或慢衰减。只有误差收敛进入安静保持带、用户取消、蓝牙断开、IMU 异常或误差同向发散且船头仍在远离目标方向转动时才快速衰减或清零；其中误差发散表示闭环符号、左右电机映射或航向源可能不可信，不能继续用积分补偿加大差速。船头停止远离目标或开始回目标后，应恢复 PD 差速修正，但 `HBOOST` 从 0 重新建立，避免旧积分直接打满。

`f_hat` 仍作为总扰动观测量记录：

```text
f_observed = r_dot - b0 * d_effective
f_hat = lowPass(f_hat, f_observed, alpha)
```

第一版不把 `f_hat` 直接写入长期参数；后续只有在现场日志证明方向和量级稳定后，才考虑把 `-f_hat` 的一部分叠加到 `d_raw`。

### 小误差安静保持带

实测电机在约 `9%` 以下基本不转，因此空档锁航为了跨过死区，会在误差明显存在时把很小的修正抬到 `10%` 左右。但这个门槛不能直接作用在容差边界附近，否则 `HERR` 只有 `1°-2°`、内环输出只有 `0.x%` 时，会在 `0%` 和 `10%` 之间来回切换，形成可感知的推力跳变。

固件第一版采用“容差外再加安静保持带”的处理：

```text
quietHoldBand = toleranceDeg + 1deg
```

当航向误差仍在 `quietHoldBand` 内，且角速度低于停稳阈值时，固件进入 `SETTLE`，输出 `0` 差速，并快速衰减 `HBOOST`；此时不把小修正抬高到最低有效推力。只有误差超过该安静保持带后，空档锁航才启用 `10%` 最低有效差速和最低补偿爬升速度。这样牺牲了约 `1°` 的极限静态精度，换来岸上和低速水面状态下更稳定、不抖动的锁航体验。

预测项用于提前判断是否进入刹车模式：

```text
e_pred = e - r * lookAheadSeconds
needBrake =
    sign(e) == sign(r) &&
    abs(r) >= minBrakeRateDegS &&
    (sign(e_pred) != sign(e) || abs(e_pred) <= brakeWindowDeg)
```

`sign(e) == sign(r)` 表示船头正在朝目标方向转。若预测误差 `e_pred` 将越过 0 或进入刹车窗口，就进入 `BRAKE`。

## 短时扰动观测

扰动观测只在当前控制会话内生效，不写入长期 NVS，不作为跨水域的电机模型。

简化观测律：

```text
f_observed = r_dot - b0 * d_effective
f_hat = lowPass(f_hat, f_observed, alpha)
```

更新条件必须严格：

- `YBHDG/YBGZ` 新鲜有效。
- `dt` 在正常范围内，`r_dot` 未出现明显尖峰。
- 当前输出没有撞到单侧限幅、总限幅或高推力禁止反推边界。
- `abs(d_effective)` 高于有效死区，避免用无效小输出学习。
- 加速度模长、roll/pitch、I2C 读数没有异常。
- 不在 `BRAKE` 初始大斜率切换瞬间。

如果条件不满足，冻结 `f_hat` 或按固定时间常数衰减到 0：

```text
f_hat = f_hat * decay
```

退出锁航、角度转向完成、用户取消、蓝牙断开或 IMU 异常时：

```text
f_hat = 0
rateIntegral = 0
```

稳定性边界：

- `f_hat` 有最大绝对值限制。
- `f_hat` 每秒变化量有限制。
- 连续多次观测残差方向混乱时，关闭扰动补偿，仅保留角速度内环。
- 扰动补偿只能改变差速 `d`，不能修改基础推力 `b`。

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

1. **角速度负反馈**：角速度内环中的 `r_err = r_ref - r` 本质上是阻尼来源，实际角速度越接近或超过目标角速度，差速输出越小，必要时转为反向抑制。
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
| `CORRECT` | 正常计算 `r_ref/r_err/v`，再用 `f_hat` 做短时扰动补偿，受最小有效输出和限幅约束 |
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
4. 应用单侧输出上限和语音/手动来源限幅；当前锁航/转向最终语义输出正推最高 `70%`、反推最高 `60%`。
5. 输出用户语义 `left/right`，再在固件最低层按 ESP32 NVS 中的 ESC 反向配置映射为实际 PWM。

## 扰动抑制

扰动统一看作当前总扰动 `f`，而不是长期记忆表：

- 水流和风：表现为同向误差长期不收敛。
- 左右电机推力不一致：表现为同样 `d` 下角速度响应偏差。
- 波浪/碰撞/手机晃动：表现为加速度异常或航向突变。
- ESC 斜率限制：表现为控制命令和实际角速度响应有延迟。
- 电机非线性和电池电压变化：表现为 `r_dot - b0 * d_effective` 的短时残差。

补偿分级：

| 级别 | 信号 | 补偿 |
| --- | --- | --- |
| 正常 | 误差收敛，角速度可解释 | 航向外环 + 角速度内环 |
| 轻度 | 收敛慢但方向正确 | 开启短时 `f_hat` 补偿，小幅增加差速 |
| 中度 | 扰动估计较大或预测刹车后仍过冲明显 | 降低目标角速度 `r_ref`、提前刹车、保留低功率 |
| 重度 | 误差持续扩大且船头仍在远离目标方向转动、角速度方向异常、传感超时 | 临时冻结扰动补偿和转向修正，回到基础推力；停止远离目标后恢复修正，硬安全故障才退出 |

进入 `BRAKE` 时，短时扰动补偿冻结或衰减，不能继续给同向推力。

## 系统辨识

不要做跨环境的“效果记忆”。系统辨识只用于得到相对稳定的硬件边界和保守初值：

| 参数 | 怎么测 |
| --- | --- |
| `deadzonePercent` | 水中左右分别输出，记录刚能产生可见角速度的百分比 |
| `responseDelayMs` | `d` 阶跃变化后，`r` 开始明显变化的延迟 |
| `b0` 初值 | 固定 `d=20/30/35%`，只取保守低值作为粗略执行器能力 |
| `naturalDamping` | 取消差速后，记录 `r` 衰减到一半的时间 |
| `overshootDeg` | 30°/60° 转向自然过冲角 |

这些数据决定：

- `lookAheadSeconds`
- `K_heading`
- `K_rate`
- `K_accel`
- `b0` 初值
- `minEffectiveBrakePercent`
- `maxBrakePercent`
- `brakeHoldMs`

明确不做：

- 不把某次水流、风向、载重下测到的 `yawAccelPerPercent` 写成长期表。
- 不把短时 `f_hat` 保存到 NVS。
- 不按水域/风向建立人工环境档案。
- 不用加速度计直接反推 yaw 转矩。

## 初始参数建议

| 参数 | 初始值 | 调整方向 |
| --- | --- | --- |
| `K_heading` | 由当前比例修正等效换算 | 过冲大就降，转不动就升 |
| `K_rate` | 从小值开始 | 角速度跟不上就升，反向摆动就降 |
| `K_accel` | 从小值开始 | 角速度变化过猛或刹车抖动就升 |
| `b0` | 保守低值 | 补偿太猛就降，明显无力再小步升 |
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
2. `updateHeadingLockControl(now)` 每个控制周期读取 `headingDegrees`、`YBGZ` 或航向差分，计算 `e/r/r_dot/e_pred`。
3. `CORRECT` 阶段计算 `r_ref`、`r_err`、`v` 和短时扰动补偿后的 `d_raw`。
4. `BRAKE` 阶段按角速度动态计算 `d_brake` 和 `brakeHoldTargetMs`，冻结或衰减 `f_hat`，并且每周期检查退出条件。
5. `SETTLE` 阶段在误差和角速度都低时输出 0 或很小修正，避免抖动。
6. 最终通过已有 ESC 死区、单侧限幅、斜率限制、电压/电流/温度保护输出，并只在最低层应用 NVS 中的 ESC 反向配置。

状态回包至少增加或保留：

- `HLOCK=ACTIVE`
- `HPHASE=CORRECT|BRAKE|SETTLE`
- `HERR`
- `HRATE`
- `HPRED`
- `HPD`
- `HBRK`
- `HCORR`
- `HBOOST` 表示当前会话短期补偿，来自持续 `HRERR` 积分，直接叠加到 `CORRECT` 阶段差速
- `HFHAT` 表示短时扰动观测量，先记录不直接叠加到输出
- `BMS` 或等效刹车剩余/目标时间字段

## 实施阶段

1. **固件观测模式**：ESP32 记录 `e/r/r_dot/e_pred/f_hat候选/phase建议/d建议`，但仍输出旧比例锁航或保持普通油门。
2. **启用航向外环 + 角速度内环但不主动反推**：只减少同向差速，观察过冲是否下降。
3. **低功率空档动态反打**：`min=20%`、`max=25%`、`maxHold=500ms`。
4. **启用短时扰动观测但只记录不补偿**：验证 `f_hat` 在水流、风和电池变化下不会长期漂移。
5. **启用 `HRERR` 积分补偿 `HBOOST`**：先限制最大补偿和变化率，让持续无响应时自动加差速，同时确认不会把环境扰动误放大。
6. **提高到有效刹车**：逐步到 `max=35%`、`maxHold=800ms`。
7. **1档/2档前进锁航**：验证 `b/d` 解耦和 `abs(b)<70%` 反推规则。
8. **再考虑刹车阶段专用斜率**：如果 ESC 斜率导致反打到达太慢，再做固件安全变更。
9. **停用 App 本地快速锁航路径**：Android 只保留目标/基础推力/心跳，不再计算锁航最终 `L/R`。

## 可观测性

控制页或日志必须显示：

- `phase = CORRECT/BRAKE/SETTLE`
- `e`
- `r`
- `r_ref`
- `r_dot`
- `e_pred`
- `f_hat` 或 `HBOOST`
- `d_raw`
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
