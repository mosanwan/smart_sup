package com.smartsup.controller.transport

import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.ControlCommandMode
import com.smartsup.controller.model.Telemetry
import com.smartsup.controller.model.TrackLogEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockControlTransport : ControlTransport {
    private val telemetryState = MutableStateFlow(
        Telemetry(controllerMessage = "模拟链路：未连接真实控制器"),
    )
    private val trackLogEventFlow = MutableSharedFlow<TrackLogEvent>()

    override val telemetry: StateFlow<Telemetry> = telemetryState.asStateFlow()
    override val trackLogEvents: SharedFlow<TrackLogEvent> = trackLogEventFlow.asSharedFlow()

    override suspend fun connect() {
        telemetryState.value = Telemetry(
            batteryVoltage = 0f,
            leftCurrent = 0f,
            rightCurrent = 0f,
            escTemperature = 0f,
            imuAvailable = true,
            headingDegrees = 0f,
            targetHeadingDegrees = null,
            leftOutputPercent = 0,
            rightOutputPercent = 0,
            statusFields = mapOf(
                "ARMED" to "0",
                "L" to "0",
                "R" to "0",
                "CMD_SRC" to "APP",
                "MODE" to "THROTTLE",
                "LPWM" to "1500",
                "RPWM" to "1500",
                "IMU" to "1",
                "HDG" to "0.0",
                "GPS" to "1",
                "PPS" to "0",
                "GPS_SENT" to "0",
                "GPS_BAUD" to "115200",
                "GPS_FIX" to "0",
                "GPS_SAT" to "0",
                "GPS_ANT" to "OPEN",
                "ID" to "000",
                "BT" to "SmartSUP-MOCK",
                "ID_SRC" to "MOCK",
            ),
            controllerMessage = "模拟链路已连接，未发送真实油门",
        )
    }

    override suspend fun disconnect() {
        send(ControlCommand.Idle)
        telemetryState.value = Telemetry(controllerMessage = "已断开，推进输出保持空闲")
    }

    override suspend fun send(command: ControlCommand) {
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = when {
                command.mode == ControlCommandMode.TurnAngle -> {
                    "模拟指令：${command.source.wireValue} ${command.turnDirection?.label} ${command.turnAngleDegrees} 度"
                }
                command.mode == ControlCommandMode.HeadingLock -> {
                    if (command.headingLockEnabled) {
                        "模拟指令：${command.source.wireValue} 航向锁定 基础 ${command.headingLockBaseThrottlePercent}%"
                    } else {
                        "模拟指令：${command.source.wireValue} 取消航向锁定"
                    }
                }
                command.armed -> {
                    "模拟指令：${command.source.wireValue} 左 ${command.leftThrottlePercent}% / 右 ${command.rightThrottlePercent}%"
                }
                else -> {
                    "模拟指令：${command.source.wireValue} 锁定，油门回空挡"
                }
            },
            lastSentCommand = command.toWireLine(),
        )
    }

    override suspend fun sendRawLine(line: String) {
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "模拟原始命令：$line",
            lastSentCommand = line,
        )
    }

    override suspend fun uploadFirmware(
        firmware: ByteArray,
        md5Hex: String,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    ) {
        onProgress(firmware.size, firmware.size)
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "模拟 OTA：${firmware.size} bytes / MD5 $md5Hex",
        )
    }
}
