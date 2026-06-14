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
    val leftEscReversed: Boolean = false,
    val rightEscReversed: Boolean = false,
) {
    init {
        require(leftThrottlePercent in -100..100)
        require(rightThrottlePercent in -100..100)
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
            if (headingLockEnabled) {
                requireNotNull(headingLockRequestId) { "航向锁定命令缺少请求 ID" }
                require(headingLockRequestId in 1..65535) { "航向锁定请求 ID 超出范围" }
            }
        }
    }

    fun toWireLine(): String {
        return when (mode) {
            ControlCommandMode.Throttle -> {
                "SRC=${source.wireValue};ARM=${if (armed) 1 else 0};L=$leftThrottlePercent;R=$rightThrottlePercent"
            }
            ControlCommandMode.TurnAngle -> {
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};DIR=${turnDirection!!.wireValue};" +
                    "ANGLE=$turnAngleDegrees;TID=$turnRequestId;" +
                    "LREV=${if (leftEscReversed) 1 else 0};RREV=${if (rightEscReversed) 1 else 0}"
            }
            ControlCommandMode.HeadingLock -> {
                val idToken = if (headingLockEnabled) ";HID=$headingLockRequestId" else ""
                "SRC=${source.wireValue};ARM=1;MODE=${mode.wireValue};" +
                    "HLOCK=${if (headingLockEnabled) 1 else 0};BASE=$headingLockBaseThrottlePercent" +
                    idToken +
                    ";LREV=${if (leftEscReversed) 1 else 0};RREV=${if (rightEscReversed) 1 else 0}"
            }
        }
    }

    companion object {
        val Idle = ControlCommand()
    }
}
