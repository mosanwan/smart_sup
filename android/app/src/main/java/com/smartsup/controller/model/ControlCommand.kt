package com.smartsup.controller.model

import java.util.Locale

data class ControlCommand(
    val leftThrottlePercent: Int = 0,
    val rightThrottlePercent: Int = 0,
    val armed: Boolean = false,
    val source: CommandSource = CommandSource.App,
    val mode: ControlCommandMode = ControlCommandMode.Throttle,
    val headingSource: HeadingSource = HeadingSource.Imu,
    val phoneHeadingDegrees: Float? = null,
    val turnDirection: TurnDirection? = null,
    val turnAngleDegrees: Int? = null,
    val turnRequestId: Int? = null,
    val headingLockEnabled: Boolean = false,
    val headingLockRequestId: Int? = null,
    val headingLockBaseThrottlePercent: Int = 0,
    val headingLockToleranceDegrees: Int = 2,
    val headingLockFullCorrectionDegrees: Int = 45,
    val headingLockNeutralPivotMinDifferencePercent: Int = 10,
    val headingLockNeutralPivotMaxDifferencePercent: Int = 60,
    val headingLockTargetDegrees: Float? = null,
    val stationTargetLatitude: Double? = null,
    val stationTargetLongitude: Double? = null,
    val stationCurrentLatitude: Double? = null,
    val stationCurrentLongitude: Double? = null,
    val stationOutputLimitPercent: Int = 40,
    val voicePowerLimitPercent: Int = 70,
) {
    init {
        require(leftThrottlePercent in -100..100)
        require(rightThrottlePercent in -100..100)
        require(voicePowerLimitPercent in 5..100) { "声控功率限制只允许 5..100%" }
        phoneHeadingDegrees?.let {
            require(it in 0f..360f) { "手机船头航向只允许 0..360 度" }
        }
        if (mode == ControlCommandMode.TurnAngle) {
            require(armed) { "角度转向命令必须在已解锁状态下发送" }
            require(source == CommandSource.Voice) { "角度转向当前只开放语音来源" }
            requireNotNull(turnDirection) { "角度转向命令缺少方向" }
            requireNotNull(turnAngleDegrees) { "角度转向命令缺少角度" }
            require(turnAngleDegrees in 1..180) { "角度转向命令只允许 1..180 度" }
            requireNotNull(turnRequestId) { "角度转向命令缺少请求 ID" }
            require(turnRequestId in 1..65535) { "角度转向请求 ID 超出范围" }
        }
        if (mode == ControlCommandMode.HeadingLock) {
            require(armed) { "航向锁定命令必须在已解锁状态下发送" }
            require(headingLockBaseThrottlePercent in -100..100)
            require(headingLockToleranceDegrees in 1..20) { "航向锁定容差只允许 1..20 度" }
            require(headingLockFullCorrectionDegrees in 5..180) { "航向锁定最大转向角只允许 5..180 度" }
            require(headingLockNeutralPivotMinDifferencePercent in 0..100) { "空档原地转向最小差值只允许 0..100%" }
            require(headingLockNeutralPivotMaxDifferencePercent in 0..60) { "空档最大反推只允许 0..60%" }
            require(headingLockNeutralPivotMaxDifferencePercent >= headingLockNeutralPivotMinDifferencePercent) {
                "空档最大反推必须大于等于最小有效转向差"
            }
            require(headingLockFullCorrectionDegrees > headingLockToleranceDegrees) {
                "航向锁定最大转向角必须大于容差"
            }
            if (headingLockEnabled) {
                requireNotNull(headingLockRequestId) { "航向锁定命令缺少请求 ID" }
                require(headingLockRequestId in 1..65535) { "航向锁定请求 ID 超出范围" }
            }
            headingLockTargetDegrees?.let {
                require(it in 0f..360f) { "航向锁定目标航向只允许 0..360 度" }
            }
        }
        if (mode == ControlCommandMode.StationKeep) {
            require(armed) { "定点保持命令必须在已解锁状态下发送" }
            require(source == CommandSource.App) { "定点保持命令只允许 App 来源" }
            require(stationOutputLimitPercent in 0..40) { "定点保持输出限幅只允许 0..40%" }
            val targetLatitude = requireNotNull(stationTargetLatitude) { "定点保持缺少目标纬度" }
            val targetLongitude = requireNotNull(stationTargetLongitude) { "定点保持缺少目标经度" }
            val currentLatitude = requireNotNull(stationCurrentLatitude) { "定点保持缺少当前纬度" }
            val currentLongitude = requireNotNull(stationCurrentLongitude) { "定点保持缺少当前经度" }
            require(targetLatitude in -90.0..90.0) { "定点保持目标纬度超出范围" }
            require(currentLatitude in -90.0..90.0) { "定点保持当前纬度超出范围" }
            require(targetLongitude in -180.0..180.0) { "定点保持目标经度超出范围" }
            require(currentLongitude in -180.0..180.0) { "定点保持当前经度超出范围" }
        }
        if (mode == ControlCommandMode.KeepAlive) {
            require(armed) { "保活命令必须在已解锁状态下发送" }
        }
    }

    fun toWireLine(): String {
        val voiceLimitToken = if (source == CommandSource.Voice) ";VMAX=$voicePowerLimitPercent" else ""
        val phoneHeadingToken = phoneHeadingDegrees?.let {
            ";PHDG=${String.format(Locale.US, "%.1f", it)}"
        }.orEmpty()
        val headingSourceToken = ";H_SRC=${headingSource.wireValue}$phoneHeadingToken"
        return when (mode) {
            ControlCommandMode.Throttle -> {
                "SRC=${source.wireValue};ARM=${if (armed) 1 else 0};L=$leftThrottlePercent;R=$rightThrottlePercent" +
                    headingSourceToken +
                    voiceLimitToken
            }
            ControlCommandMode.TurnAngle -> {
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};DIR=${turnDirection!!.wireValue};" +
                    "ANGLE=$turnAngleDegrees;TID=$turnRequestId" +
                    headingSourceToken +
                    voiceLimitToken
            }
            ControlCommandMode.HeadingLock -> {
                val idToken = if (headingLockEnabled) ";HID=$headingLockRequestId" else ""
                val targetToken = headingLockTargetDegrees?.let { ";TARGET=$it" } ?: ""
                val maxReverse = headingLockNeutralPivotMaxDifferencePercent
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};" +
                    "HLOCK=${if (headingLockEnabled) 1 else 0};BASE=$headingLockBaseThrottlePercent;" +
                    "HTOL=$headingLockToleranceDegrees;HFULL=$headingLockFullCorrectionDegrees;" +
                    "HREV=$maxReverse" +
                    idToken +
                    targetToken +
                    headingSourceToken +
                    voiceLimitToken
            }
            ControlCommandMode.StationKeep -> {
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};" +
                    "TLAT=${formatLatLon(stationTargetLatitude!!)};TLON=${formatLatLon(stationTargetLongitude!!)};" +
                    "CLAT=${formatLatLon(stationCurrentLatitude!!)};CLON=${formatLatLon(stationCurrentLongitude!!)};" +
                    "SLIM=$stationOutputLimitPercent" +
                    headingSourceToken
            }
            ControlCommandMode.KeepAlive -> {
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue}" +
                    headingSourceToken +
                    voiceLimitToken
            }
        }
    }

    companion object {
        val Idle = ControlCommand()

        private fun formatLatLon(value: Double): String =
            String.format(Locale.US, "%.6f", value)
    }
}

enum class HeadingSource(val wireValue: String) {
    Imu("IMU"),
    Phone("PHONE"),
}
