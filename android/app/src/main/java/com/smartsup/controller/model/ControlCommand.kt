package com.smartsup.controller.model

data class ControlCommand(
    val leftThrottlePercent: Int = 0,
    val rightThrottlePercent: Int = 0,
    val armed: Boolean = false,
    val source: CommandSource = CommandSource.App,
    val mode: ControlCommandMode = ControlCommandMode.Throttle,
    val turnDirection: TurnDirection? = null,
    val turnAngleDegrees: Int? = null,
    val turnRequestId: Int? = null,
    val headingLockEnabled: Boolean = false,
    val headingLockRequestId: Int? = null,
    val headingLockBaseThrottlePercent: Int = 0,
    val headingLockToleranceDegrees: Int = 4,
    val headingLockFullCorrectionDegrees: Int = 6,
    val headingLockNeutralPivotForwardPercent: Int = 28,
    val headingLockNeutralPivotReversePercent: Int = 31,
    val headingLockTargetOffsetDegrees: Int? = null,
    val voicePowerLimitPercent: Int = 70,
    val leftEscReversed: Boolean = false,
    val rightEscReversed: Boolean = false,
    val usePhoneHeading: Boolean = false,
    val phoneHeadingDegrees: Float? = null,
) {
    init {
        require(leftThrottlePercent in -100..100)
        require(rightThrottlePercent in -100..100)
        require(voicePowerLimitPercent in 5..100) { "声控功率限制只允许 5..100%" }
        if (phoneHeadingDegrees != null) {
            require(phoneHeadingDegrees in 0f..360f) { "手机航向只允许 0..360 度" }
        }
        if (mode == ControlCommandMode.TurnAngle) {
            require(armed) { "角度转向命令必须在已解锁状态下发送" }
            require(source == CommandSource.Voice) { "角度转向当前只开放语音来源" }
            requireNotNull(turnDirection) { "角度转向命令缺少方向" }
            requireNotNull(turnAngleDegrees) { "角度转向命令缺少角度" }
            require(turnAngleDegrees in 1..90) { "角度转向命令只允许 1..90 度" }
            requireNotNull(turnRequestId) { "角度转向命令缺少请求 ID" }
            require(turnRequestId in 1..65535) { "角度转向请求 ID 超出范围" }
        }
        if (mode == ControlCommandMode.HeadingLock) {
            require(armed) { "航向锁定命令必须在已解锁状态下发送" }
            require(headingLockBaseThrottlePercent in -100..100)
            require(headingLockToleranceDegrees in 1..20) { "航向锁定容差只允许 1..20 度" }
            require(headingLockFullCorrectionDegrees in 5..180) { "航向锁定最大转向角只允许 5..180 度" }
            require(headingLockNeutralPivotForwardPercent in 0..100) { "空档原地转向正推只允许 0..100%" }
            require(headingLockNeutralPivotReversePercent in 0..100) { "空档原地转向反推只允许 0..100%" }
            require(headingLockFullCorrectionDegrees > headingLockToleranceDegrees) {
                "航向锁定最大转向角必须大于容差"
            }
            if (headingLockEnabled) {
                requireNotNull(headingLockRequestId) { "航向锁定命令缺少请求 ID" }
                require(headingLockRequestId in 1..65535) { "航向锁定请求 ID 超出范围" }
            }
            headingLockTargetOffsetDegrees?.let {
                require(it in -90..90) { "航向锁定目标偏移只允许 -90..90 度" }
            }
        }
    }

    fun toWireLine(): String {
        val voiceLimitToken = if (source == CommandSource.Voice) ";VMAX=$voicePowerLimitPercent" else ""
        return when (mode) {
            ControlCommandMode.Throttle -> {
                "SRC=${source.wireValue};ARM=${if (armed) 1 else 0};L=$leftThrottlePercent;R=$rightThrottlePercent" +
                    voiceLimitToken
            }
            ControlCommandMode.TurnAngle -> {
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};DIR=${turnDirection!!.wireValue};" +
                    "ANGLE=$turnAngleDegrees;TID=$turnRequestId;" +
                    "LREV=${if (leftEscReversed) 1 else 0};RREV=${if (rightEscReversed) 1 else 0}" +
                    voiceLimitToken
            }
            ControlCommandMode.HeadingLock -> {
                val idToken = if (headingLockEnabled) ";HID=$headingLockRequestId" else ""
                val offsetToken = headingLockTargetOffsetDegrees?.let { ";HOFF=$it" } ?: ""
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};" +
                    "HLOCK=${if (headingLockEnabled) 1 else 0};BASE=$headingLockBaseThrottlePercent;" +
                    "HTOL=$headingLockToleranceDegrees;HFULL=$headingLockFullCorrectionDegrees;" +
                    "HREV=$headingLockNeutralPivotReversePercent;" +
                    "HPF=$headingLockNeutralPivotForwardPercent;HPR=$headingLockNeutralPivotReversePercent" +
                    idToken +
                    offsetToken +
                    ";LREV=${if (leftEscReversed) 1 else 0};RREV=${if (rightEscReversed) 1 else 0}" +
                    voiceLimitToken
            }
        }
    }

    companion object {
        val Idle = ControlCommand()
    }
}
