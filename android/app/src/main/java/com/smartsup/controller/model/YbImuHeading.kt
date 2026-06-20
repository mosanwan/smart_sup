package com.smartsup.controller.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

data class YbImuHeadingCalibrationResult(
    val offsetDegrees: Float,
    val usingQuaternion: Boolean,
)

fun ybImuHeadingDegrees(
    telemetry: Telemetry,
    offsetDegrees: Float,
): Float? {
    val base = ybImuBaseHeadingDegrees(telemetry) ?: return null
    return normalizeCompassDegrees(base + offsetDegrees)
}

fun calibrateYbImuHeadingToPhone(
    phoneHeadingDegrees: Float,
    telemetry: Telemetry,
): YbImuHeadingCalibrationResult? {
    val base = ybImuBaseHeadingDegrees(telemetry) ?: return null
    return YbImuHeadingCalibrationResult(
        offsetDegrees = normalizeCompassDegrees(phoneHeadingDegrees - base),
        usingQuaternion = quaternionForwardHeadingDegrees(telemetry) != null,
    )
}

fun ybImuHeadingAlgorithmLabel(telemetry: Telemetry): String {
    return if (quaternionForwardHeadingDegrees(telemetry) != null) {
        "四元数船头投影 + 偏置"
    } else {
        "YBY 兜底 + 偏置"
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

private fun ybImuBaseHeadingDegrees(telemetry: Telemetry): Float? {
    return quaternionForwardHeadingDegrees(telemetry)
        ?: telemetry.ybYawDegrees?.let(::normalizeCompassDegrees)
}

private fun quaternionForwardHeadingDegrees(telemetry: Telemetry): Float? {
    val quat = normalizedQuaternion(telemetry) ?: return null
    val forward = rotateVectorByQuaternion(
        q = quat,
        x = YB_IMU_FORWARD_AXIS_X,
        y = YB_IMU_FORWARD_AXIS_Y,
        z = 0f,
    )
    val horizontalMagnitude = sqrt(forward.x * forward.x + forward.y * forward.y)
    if (horizontalMagnitude < 0.05f) {
        return null
    }
    val headingRadians = atan2(forward.x.toDouble(), forward.y.toDouble())
    return normalizeCompassDegrees((headingRadians * 180.0 / PI).toFloat())
}

private data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float)

private data class Vector3(val x: Float, val y: Float, val z: Float)

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

private const val YB_IMU_FORWARD_AXIS_X = 1f
private const val YB_IMU_FORWARD_AXIS_Y = 0f
