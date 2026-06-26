# 航向锁定提前反向力方案

工程控制论版本的闭环设计见 [航向锁定工程控制论设计](heading_lock_cybernetic_control_design.md)。本文保留具体反打触发、参数和测试细节。

## 背景

当前航向锁定主要按航向误差计算差速修正：误差越大，左右电机差速越大。实测中，船头已经接近目标航向时仍有转动惯性；如果只等误差变小或变号后再减小修正，就容易转过头，然后再反向修正，形成来回摆动。

目标是在接近目标航向前提前给反向转向力，让船头更快停在目标附近，而不是过线后才纠正。

这套算法应运行在 ESP32 固件中。Android App 的调度频率、蓝牙链路和手机传感器路径都不适合做动态反打；App 只负责发送目标航向、基础推力、限幅参数和心跳，ESP32 在本地控制周期内读取主控 IMU 并计算最终左右 ESC 输出。

## 关键判断

提前反向力不应主要依赖加速度计。加速度计能看到线性加速度、倾斜和水面扰动，但对绕竖直轴的 yaw 旋转并不直接可靠。判断船头是否会转过头，优先使用：

1. 当前航向误差 `errorDeg`：目标航向减当前航向，按 `-180..180` 归一化。
2. 当前航向角速度 `headingRateDegS`：优先由主控 IMU 的 `YBGZ` 换算并确认方向；不可用时，用连续航向读数差分估算。
3. 预测误差 `predictedErrorDeg`：用当前角速度预测短时间后的误差。

加速度计用于辅助门控：

- 水面冲击、剧烈线性加速度或姿态异常时，降低反打强度或暂缓学习参数。
- 记录 `YBAX/YBAY/YBAZ` 和加速度模长，用于判断测试数据是否受波浪、碰撞或安装松动影响。

## 控制思路

第一版采用“PD + 预测刹车”的保守方案：

```text
errorDeg = shortestCompassError(targetHeading, currentHeading)
predictedHeading = currentHeading + headingRateDegS * lookAheadSeconds
predictedErrorDeg = shortestCompassError(targetHeading, predictedHeading)
```

当船头正在朝目标方向转动，并且预测误差会接近 0 或越过 0，就提前进入刹车区：

```text
movingTowardTarget = abs(predictedErrorDeg) < abs(errorDeg)
willOvershoot = sign(predictedErrorDeg) != sign(errorDeg)
nearStopLine = abs(predictedErrorDeg) <= brakeWindowDeg
needBrake = movingTowardTarget && abs(headingRateDegS) >= minBrakeRateDegS &&
    (willOvershoot || nearStopLine)
```

进入刹车区后，不再继续按误差方向加差速，而是按当前转动方向给反向差速。反打推力必须随转动速度动态调整：转得越快，反打越大；转得慢时只给刚好有效的反打，避免来回振荡。

```text
brakeDirection = -sign(headingRateDegS)
rateRatio = (abs(headingRateDegS) - minBrakeRateDegS) /
    (fullBrakeRateDegS - minBrakeRateDegS)
rateRatio = clamp(rateRatio, 0, 1)
brakePercent = minEffectiveBrakePercent +
    (maxBrakePercent - minEffectiveBrakePercent) * rateRatio
correction = brakeDirection * brakePercent
```

其中 `headingRateDegS` 的符号必须使用实测校准后的“船头航向增加为正”的方向。若直接用 `YBGZ`，必须先通过低功率原地转向测试确认 `YBGZ` 正负；若方向不确定，第一版应使用航向差分估算角速度，避免反打方向错误。

实测电机约 `9%` 才刚开始转动，因此反打不能按 `10%-15%` 设计；这基本处在死区边缘，难以抵消船体惯性。进入 `BRAKE` 后，反打目标应至少高于有效起转门槛一段余量。

反打时间也按角速度动态计算，但不能作为一次性固定延时命令。ESP32 固件应每个控制周期重新计算是否继续 `BRAKE`：

```text
brakeHoldTargetMs = minBrakeHoldMs +
    (maxBrakeHoldMs - minBrakeHoldMs) * rateRatio
```

`brakeHoldTargetMs` 只表示当前这次反打最多允许持续多久。实际退出条件优先级更高：

```text
exitBrake =
    abs(headingRateDegS) <= settleRateDegS ||
    sign(headingRateDegS) != brakeStartRateSign ||
    brakeElapsedMs >= brakeHoldTargetMs ||
    brakeElapsedMs >= maxBrakeHoldMs
```

也就是说，转得越快，允许反打时间越长；但一旦船头角速度已经降到很低，或者角速度变号，必须立刻退出反打，不能继续把船头往反方向推。

## 建议初始参数

| 参数 | 初始值 | 说明 |
| --- | --- | --- |
| 预测提前量 `lookAheadSeconds` | `0.5s` | 先保守，后续按实测调到 `0.4-0.8s` |
| 刹车窗口 `brakeWindowDeg` | `3°` | 预测误差进入该范围开始准备反打 |
| 最小刹车角速度 `minBrakeRateDegS` | `8°/s` | 船头转得很慢时不反打，避免抖动 |
| 满刹车角速度 `fullBrakeRateDegS` | `35°/s` | 达到该角速度后使用最大反打 |
| 最小有效反打 `minEffectiveBrakePercent` | `20%` | 电机约 `9%` 才刚起转，反打必须明显高于死区 |
| 最大反打 `maxBrakePercent` | `35%` | 第一版有效反打上限；确认电流和温升后再考虑 `40%-45%` |
| 最短反打保持 `minBrakeHoldMs` | `250ms` | 给 ESC 和电机一点有效响应时间 |
| 动态反打最长 `maxBrakeHoldMs` | `800ms` | 速度越快越接近该上限 |
| 结束角速度 `settleRateDegS` | `3°/s` | 误差和角速度都小才认为停稳 |

这些参数只作为第一版低功率水测起点，不作为满功率设置。

注意：当前固件有油门斜率限制，文档记录为每周期最多变化 `5us`，约 `25%/s`。如果从正推切到 `20%-35%` 反推，实际 PWM 到达有效反推区可能需要数百毫秒到 1 秒以上。若实测反打明显滞后，有两个选择：

1. 不改固件斜率：把 `lookAheadSeconds` 提高到 `0.8-1.2s`，更早进入 `BRAKE`。
2. 增加“锁航刹车阶段专用斜率”：只在 `BRAKE` 状态、航向源有效且有最长时间限制时，允许更快靠近反打目标；普通油门和非刹车锁航仍保持原斜率限制。

第二种方案更直接，但属于安全敏感固件变更，必须先低功率验证电流、温升和方向。

## 与现有差速规则的关系

- 空档原地转向：允许正反推，反打可直接输出反向差速，但仍受空档转向最大差值和单侧输出上限约束。
- 非空档且基础推力绝对值低于 `70%`：沿用现有规则，允许慢侧跨过 `0%` 进入反推，反打仍受单侧输出上限约束。
- 非空档且基础推力绝对值达到或超过 `70%`：不主动反推，反打只允许减小快侧或把慢侧拉到 `0%`，不得突然给反向推力。
- 进入刹车区时，自适应补偿应冻结或快速衰减，不能继续增加同向差速。
- 如果刹车后误差仍在约 `3s` 内持续扩大，继续触发现有“航向误差持续扩大”保护，取消锁航、锁定并回空挡。

## 状态机

第一版建议只加三个状态，不做复杂完整状态机：

| 状态 | 行为 |
| --- | --- |
| `CORRECT` | 按当前误差计算正常差速修正 |
| `BRAKE` | 预测会过冲时，按当前角速度方向提前反打 |
| `SETTLE` | 误差小且角速度低时输出 0 或很小修正，等待稳定 |

状态切换：

```text
CORRECT -> BRAKE:
  needBrake == true

BRAKE -> SETTLE:
  abs(headingRateDegS) <= settleRateDegS 或 headingRateDegS 已经变号

BRAKE -> CORRECT:
  反打达到动态保持时间或 predictedErrorDeg 重新远离 0

SETTLE -> CORRECT:
  abs(errorDeg) > toleranceDeg
```

反打确实可能打过头，所以第一版必须避免“定时盲打”。防过头规则：

- `BRAKE` 状态按 ESP32 固件控制周期重新计算，不使用一次性长延时；状态上报可以低频显示。
- 角速度变号时立即退出 `BRAKE`，因为这表示船头已经开始反向转动。
- 角速度低于 `settleRateDegS` 时退出 `BRAKE` 并进入 `SETTLE`，此时输出 0 或很小修正。
- 反打没有积分项，不能因为之前过冲而累积更大的反向力。
- 单次反打受 `maxBrakeHoldMs` 硬限制，传感器异常或状态不可信时直接取消锁航或回空挡。

## 数据记录

实现前后都要在日志或控制页显示以下字段，方便调参：

- `errorDeg`
- `headingRateDegS`
- `predictedErrorDeg`
- `headingControlPhase`：`CORRECT/BRAKE/SETTLE`
- `brakeElapsedMs`
- `brakeHoldTargetMs`
- `baseCorrectionPercent`
- `brakeCorrectionPercent`
- `finalCorrectionPercent`
- `leftOutputPercent/rightOutputPercent`
- `YBAX/YBAY/YBAZ/YBGZ`

没有这些字段就很难判断过冲是控制滞后、传感器延迟、反打太弱、反打太晚，还是左右电机方向/推力不一致。

其中原始计算和安全裁剪应在 ESP32 固件完成；Android 控制页只展示固件回传的状态，不再用本地锁航算法推导最终 `L/R`。

## 测试顺序

1. 岸上不接桨或低风险固定状态，只验证 `YBGZ` 和航向变化率符号：左转、右转时记录 `YBY`、换算航向和 `YBGZ`。
2. 水里空档低功率原地转向，目标偏移 `30°`，只记录不启用反打，估计自然过冲角和角速度。
3. 启用动态反打，`minEffectiveBrakePercent=20%`、`maxBrakePercent=25%`，重复 `30°` 转向。
4. 如果仍明显刹不住，把 `maxBrakePercent` 提到 `30%-35%`，观察是否减少过冲且不产生反向振荡。
5. 再测试 `1档/2档` 前进锁航，不测试高推力反打。
6. 每轮记录电池电压、电流、ESC 温度、电机温度和左右下发值。

## 不做的事

- 不用加速度计直接判断 yaw 是否会过冲。
- 不在高推力下突然反推。
- 不把反打做成无限积分项。
- 不在航向源、角速度或 IMU 数据超时时继续锁航。
