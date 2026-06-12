package com.smartsup.controller.model

data class ControlCommand(
    val leftThrottlePercent: Int = 0,
    val rightThrottlePercent: Int = 0,
    val armed: Boolean = false,
) {
    init {
        require(leftThrottlePercent in -100..100)
        require(rightThrottlePercent in -100..100)
    }

    companion object {
        val Idle = ControlCommand()
    }
}
