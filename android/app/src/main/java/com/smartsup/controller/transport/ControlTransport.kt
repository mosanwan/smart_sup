package com.smartsup.controller.transport

import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.Telemetry
import kotlinx.coroutines.flow.Flow

interface ControlTransport {
    val telemetry: Flow<Telemetry>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(command: ControlCommand)
    suspend fun uploadFirmware(
        firmware: ByteArray,
        md5Hex: String,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    )
}
