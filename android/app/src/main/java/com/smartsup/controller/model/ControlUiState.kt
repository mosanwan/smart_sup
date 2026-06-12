package com.smartsup.controller.model

data class ControlUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val armed: Boolean = false,
    val leftThrottlePercent: Int = 0,
    val rightThrottlePercent: Int = 0,
    val selectedGear: ThrottleGear = ThrottleGear.Default,
    val telemetry: Telemetry = Telemetry(),
    val statusMessage: String = "未连接，推进输出保持空闲",
) {
    val canSendThrottle: Boolean
        get() = connectionState == ConnectionState.Connected && armed
}
