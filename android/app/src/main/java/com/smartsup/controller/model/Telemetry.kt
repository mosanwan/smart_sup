package com.smartsup.controller.model

data class Telemetry(
    val batteryVoltage: Float? = null,
    val leftCurrent: Float? = null,
    val rightCurrent: Float? = null,
    val escTemperature: Float? = null,
    val controllerMessage: String = "等待连接",
    val lastSentCommand: String = "--",
    val lastReceivedStatus: String = "--",
)
