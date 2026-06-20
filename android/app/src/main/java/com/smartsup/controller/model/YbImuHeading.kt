package com.smartsup.controller.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

data class YbImuHeadingCalibrationResult(
    val offsetDegrees: Float,
    val usingQuaternion: Boolean,
)

data class YbImuHeadingMode(
    val id: Int,
    val label: String,
)

data class YbImuHeadingCandidate(
    val mode: YbImuHeadingMode,
    val rawDegrees: Float?,
    val calibratedDegrees: Float?,
)

const val YB_IMU_HEADING_MODE_YBY = 0
const val YB_IMU_HEADING_MODE_YBY_INVERTED = 1
const val YB_IMU_HEADING_MODE_Q_NORMAL_X_POS = 2
const val YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG = 3
const val YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS = 4
const val YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG = 5
const val YB_IMU_HEADING_MODE_Q_INVERSE_X_POS = 6
const val YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG = 7
const val YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS = 8
const val YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG = 9
const val YB_IMU_HEADING_MODE_Q_NORMAL_X_POS_SWAP = 10
const val YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG_SWAP = 11
const val YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS_SWAP = 12
const val YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG_SWAP = 13
const val YB_IMU_HEADING_MODE_Q_INVERSE_X_POS_SWAP = 14
const val YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG_SWAP = 15
const val YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS_SWAP = 16
const val YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG_SWAP = 17
const val YB_IMU_HEADING_MODE_DEFAULT = YB_IMU_HEADING_MODE_Q_NORMAL_X_POS

val YB_IMU_HEADING_MODES = listOf(
    YbImuHeadingMode(YB_IMU_HEADING_MODE_YBY, "YBY 原始"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_YBY_INVERTED, "YBY 反向"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_X_POS, "四元数 正向 +X"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG, "四元数 正向 -X"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS, "四元数 正向 +Y"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG, "四元数 正向 -Y"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_X_POS, "四元数 反向 +X"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG, "四元数 反向 -X"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS, "四元数 反向 +Y"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG, "四元数 反向 -Y"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_X_POS_SWAP, "四元数 正向 +X 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG_SWAP, "四元数 正向 -X 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS_SWAP, "四元数 正向 +Y 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG_SWAP, "四元数 正向 -Y 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_X_POS_SWAP, "四元数 反向 +X 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG_SWAP, "四元数 反向 -X 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS_SWAP, "四元数 反向 +Y 交换XY"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG_SWAP, "四元数 反向 -Y 交换XY"),
)

fun ybImuHeadingDegrees(
    telemetry: Telemetry,
    offsetDegrees: Float,
    modeId: Int = YB_IMU_HEADING_MODE_DEFAULT,
): Float? {
    val base = ybImuBaseHeadingDegrees(telemetry, modeId) ?: return null
    return normalizeCompassDegrees(base + offsetDegrees)
}

fun calibrateYbImuHeadingToPhone(
    phoneHeadingDegrees: Float,
    telemetry: Telemetry,
    modeId: Int = YB_IMU_HEADING_MODE_DEFAULT,
): YbImuHeadingCalibrationResult? {
    val base = ybImuBaseHeadingDegrees(telemetry, modeId) ?: return null
    return YbImuHeadingCalibrationResult(
        offsetDegrees = normalizeCompassDegrees(phoneHeadingDegrees - base),
        usingQuaternion = quaternionHeadingModeSpec(modeId) != null,
    )
}

fun coerceYbImuHeadingModeId(modeId: Int): Int {
    return if (YB_IMU_HEADING_MODES.any { it.id == modeId }) modeId else YB_IMU_HEADING_MODE_DEFAULT
}

fun ybImuHeadingModeLabel(modeId: Int): String {
    val actualMode = coerceYbImuHeadingModeId(modeId)
    return YB_IMU_HEADING_MODES.first { it.id == actualMode }.label
}

fun ybImuHeadingAlgorithmLabel(telemetry: Telemetry, modeId: Int = YB_IMU_HEADING_MODE_DEFAULT): String {
    val label = ybImuHeadingModeLabel(modeId)
    return if (ybImuBaseHeadingDegrees(telemetry, modeId) != null) {
        "$label + 偏置"
    } else {
        "$label 暂无读数"
    }
}

fun ybImuHeadingCandidates(
    telemetry: Telemetry,
    offsetDegrees: Float,
): List<YbImuHeadingCandidate> {
    return YB_IMU_HEADING_MODES.map { mode ->
        val raw = ybImuBaseHeadingDegrees(telemetry, mode.id)
        YbImuHeadingCandidate(
            mode = mode,
            rawDegrees = raw,
            calibratedDegrees = raw?.let { normalizeCompassDegrees(it + offsetDegrees) },
        )
    }
}

fun normalizeCompassDegrees(degrees: Float): Float {
    return ((degrees % 360f) + 360f) % 360f
}

fun shortestHeadingDelta(target: Float, current: Float): Float {
    var delta = (target - current) % 360f
    if (delta > 180f) {
        delta -= 360f
    }
    if (delta < -180f) {
        delta += 360f
    }
    return delta
}

private fun ybImuBaseHeadingDegrees(telemetry: Telemetry, modeId: Int): Float? {
    return when (modeId) {
        YB_IMU_HEADING_MODE_YBY -> telemetry.ybYawDegrees?.let(::normalizeCompassDegrees)
        YB_IMU_HEADING_MODE_YBY_INVERTED -> telemetry.ybYawDegrees?.let { normalizeCompassDegrees(-it) }
        else -> quaternionForwardHeadingDegrees(telemetry, modeId)
    }
}

private fun quaternionForwardHeadingDegrees(telemetry: Telemetry, modeId: Int): Float? {
    val spec = quaternionHeadingModeSpec(modeId) ?: return null
    val quat = normalizedQuaternion(telemetry) ?: return null
    val forward = rotateVectorByQuaternion(
        q = if (spec.inverseQuaternion) quat.conjugate() else quat,
        x = spec.axisX,
        y = spec.axisY,
        z = 0f,
    )
    val horizontalMagnitude = sqrt(forward.x * forward.x + forward.y * forward.y)
    if (horizontalMagnitude < 0.05f) {
        return null
    }
    val headingRadians = if (spec.swapWorldXY) {
        atan2(forward.y.toDouble(), forward.x.toDouble())
    } else {
        atan2(forward.x.toDouble(), forward.y.toDouble())
    }
    return normalizeCompassDegrees((headingRadians * 180.0 / PI).toFloat())
}

private data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float)

private data class Vector3(val x: Float, val y: Float, val z: Float)

private data class QuaternionHeadingSpec(
    val axisX: Float,
    val axisY: Float,
    val inverseQuaternion: Boolean,
    val swapWorldXY: Boolean,
)

private fun quaternionHeadingModeSpec(modeId: Int): QuaternionHeadingSpec? {
    return when (modeId) {
        YB_IMU_HEADING_MODE_Q_NORMAL_X_POS -> QuaternionHeadingSpec(1f, 0f, false, false)
        YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG -> QuaternionHeadingSpec(-1f, 0f, false, false)
        YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS -> QuaternionHeadingSpec(0f, 1f, false, false)
        YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG -> QuaternionHeadingSpec(0f, -1f, false, false)
        YB_IMU_HEADING_MODE_Q_INVERSE_X_POS -> QuaternionHeadingSpec(1f, 0f, true, false)
        YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG -> QuaternionHeadingSpec(-1f, 0f, true, false)
        YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS -> QuaternionHeadingSpec(0f, 1f, true, false)
        YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG -> QuaternionHeadingSpec(0f, -1f, true, false)
        YB_IMU_HEADING_MODE_Q_NORMAL_X_POS_SWAP -> QuaternionHeadingSpec(1f, 0f, false, true)
        YB_IMU_HEADING_MODE_Q_NORMAL_X_NEG_SWAP -> QuaternionHeadingSpec(-1f, 0f, false, true)
        YB_IMU_HEADING_MODE_Q_NORMAL_Y_POS_SWAP -> QuaternionHeadingSpec(0f, 1f, false, true)
        YB_IMU_HEADING_MODE_Q_NORMAL_Y_NEG_SWAP -> QuaternionHeadingSpec(0f, -1f, false, true)
        YB_IMU_HEADING_MODE_Q_INVERSE_X_POS_SWAP -> QuaternionHeadingSpec(1f, 0f, true, true)
        YB_IMU_HEADING_MODE_Q_INVERSE_X_NEG_SWAP -> QuaternionHeadingSpec(-1f, 0f, true, true)
        YB_IMU_HEADING_MODE_Q_INVERSE_Y_POS_SWAP -> QuaternionHeadingSpec(0f, 1f, true, true)
        YB_IMU_HEADING_MODE_Q_INVERSE_Y_NEG_SWAP -> QuaternionHeadingSpec(0f, -1f, true, true)
        else -> null
    }
}

private fun Quaternion.conjugate(): Quaternion {
    return Quaternion(w = w, x = -x, y = -y, z = -z)
}

private fun normalizedQuaternion(telemetry: Telemetry): Quaternion? {
    val w = telemetry.ybQuatW ?: return null
    val x = telemetry.ybQuatX ?: return null
    val y = telemetry.ybQuatY ?: return null
    val z = telemetry.ybQuatZ ?: return null
    val norm = sqrt(w * w + x * x + y * y + z * z)
    if (norm !in 0.70f..1.30f) {
        return null
    }
    return Quaternion(w / norm, x / norm, y / norm, z / norm)
}

private fun rotateVectorByQuaternion(
    q: Quaternion,
    x: Float,
    y: Float,
    z: Float,
): Vector3 {
    val tx = 2f * (q.y * z - q.z * y)
    val ty = 2f * (q.z * x - q.x * z)
    val tz = 2f * (q.x * y - q.y * x)

    return Vector3(
        x = x + q.w * tx + (q.y * tz - q.z * ty),
        y = y + q.w * ty + (q.z * tx - q.x * tz),
        z = z + q.w * tz + (q.x * ty - q.y * tx),
    )
}
