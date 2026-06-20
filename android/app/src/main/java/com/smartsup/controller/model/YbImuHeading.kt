package com.smartsup.controller.model

data class YbImuHeadingCalibrationResult(
    val offsetDegrees: Float,
)

data class YbImuHeadingMode(
    val id: Int,
    val label: String,
)

const val YB_IMU_HEADING_MODE_YBY = 0
const val YB_IMU_HEADING_MODE_YBY_INVERTED = 1
const val YB_IMU_HEADING_MODE_DEFAULT = YB_IMU_HEADING_MODE_YBY

val YB_IMU_HEADING_MODES = listOf(
    YbImuHeadingMode(YB_IMU_HEADING_MODE_YBY, "YBY 原始"),
    YbImuHeadingMode(YB_IMU_HEADING_MODE_YBY_INVERTED, "YBY 反向"),
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
    )
}

fun calibrateYbImuHeadingToNorth(
    telemetry: Telemetry,
    modeId: Int = YB_IMU_HEADING_MODE_DEFAULT,
): YbImuHeadingCalibrationResult? {
    val base = ybImuBaseHeadingDegrees(telemetry, modeId) ?: return null
    return YbImuHeadingCalibrationResult(
        offsetDegrees = normalizeCompassDegrees(-base),
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
        else -> null
    }
}
