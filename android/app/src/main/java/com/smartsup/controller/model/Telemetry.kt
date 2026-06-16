package com.smartsup.controller.model

data class Telemetry(
    val batteryVoltage: Float? = null,
    val leftCurrent: Float? = null,
    val rightCurrent: Float? = null,
    val escTemperature: Float? = null,
    val imuAvailable: Boolean? = null,
    val headingDegrees: Float? = null,
    val targetHeadingDegrees: Float? = null,
    val leftOutputPercent: Int? = null,
    val rightOutputPercent: Int? = null,
    val statusFields: Map<String, String> = emptyMap(),
    val controllerMessage: String = "等待连接",
    val lastSentCommand: String = "--",
    val lastReceivedStatus: String = "--",
)
