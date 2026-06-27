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
            ybImuAvailable = true,
            headingDegrees = 31f,
            ybHeadingDegrees = 31f,
            targetHeadingDegrees = null,
            ybAccelXG = 0.10f,
            ybAccelYG = -0.10f,
            ybAccelZG = 0.99f,
            ybGyroZRadS = 0f,
            ybMagXUt = 12.4f,
            ybMagYUt = -35.8f,
            ybMagZUt = 41.2f,
            ybImuCalibrationState = 1,
            ybMagCalibrationState = 0,
            ybQuatW = 0.96f,
            ybQuatX = -0.03f,
            ybQuatY = -0.06f,
            ybQuatZ = 0.27f,
            ybRollDegrees = -5.6f,
            ybPitchDegrees = -5.7f,
            ybYawDegrees = -149.0f,
            leftOutputPercent = 0,
            rightOutputPercent = 0,
            statusFields = mapOf(
                "ARMED" to "0",
                "FW" to "mock",
                "L" to "0",
                "R" to "0",
                "CMD_SRC" to "APP",
                "MODE" to "THROTTLE",
                "LPWM" to "1500",
                "RPWM" to "1500",
                "IMU" to "1",
                "YBIMU" to "1",
                "YBAX" to "0.100",
                "YBAY" to "-0.100",
                "YBAZ" to "0.990",
                "YBGZ" to "0.000",
                "YBMX" to "12.40",
                "YBMY" to "-35.80",
                "YBMZ" to "41.20",
                "YBCIMU" to "1",
                "YBCMAG" to "0",
                "YBQW" to "0.9600",
                "YBQX" to "-0.0300",
                "YBQY" to "-0.0600",
                "YBQZ" to "0.2700",
                "YBR" to "-5.6",
                "YBP" to "-5.7",
                "YBY" to "-149.0",
                "YBINIT" to "1",
                "YBAGE" to "0",
                "YBHDG" to "31.0",
                "IQUAL" to "YB_TELEMETRY_ONLY",
                "HDG" to "31.0",
                "HSRC" to "YBIMU",
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

    override suspend fun closeSilently() {
        telemetryState.value = Telemetry(controllerMessage = "已静默断开模拟链路")
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
        if (line.startsWith("ESC_CFG;") || line == "ESC_CFG") {
            return
        }
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
            lastReceivedStatus = "OTA;OK;BYTES=${firmware.size}",
        )
    }
}
