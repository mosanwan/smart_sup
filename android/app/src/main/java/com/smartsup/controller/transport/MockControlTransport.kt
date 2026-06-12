package com.smartsup.controller.transport

import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockControlTransport : ControlTransport {
    private val telemetryState = MutableStateFlow(
        Telemetry(controllerMessage = "模拟链路：未连接真实控制器"),
    )

    override val telemetry: StateFlow<Telemetry> = telemetryState.asStateFlow()

    override suspend fun connect() {
        telemetryState.value = Telemetry(
            batteryVoltage = 0f,
            leftCurrent = 0f,
            rightCurrent = 0f,
            escTemperature = 0f,
            controllerMessage = "模拟链路已连接，未发送真实油门",
        )
    }

    override suspend fun disconnect() {
        send(ControlCommand.Idle)
        telemetryState.value = Telemetry(controllerMessage = "已断开，推进输出保持空闲")
    }

    override suspend fun send(command: ControlCommand) {
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = if (command.armed) {
                "模拟指令：左 ${command.leftThrottlePercent}% / 右 ${command.rightThrottlePercent}%"
            } else {
                "模拟指令：锁定，油门回空挡"
            },
        )
    }

    override suspend fun sendRawLine(line: String) {
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "模拟原始命令：${line.trim()}",
            lastSentCommand = line.trim(),
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
