package com.smartsup.controller.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.GpsTrackPoint
import com.smartsup.controller.model.Telemetry
import com.smartsup.controller.model.TrackLogEvent
import com.smartsup.controller.model.TrackLogInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BluetoothClassicTransport(
    private val context: Context,
    private val deviceInfo: BluetoothDeviceInfo,
) : ControlTransport {
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var lastVisibleCommandLine: String? = null
    private var otaReadySignal: CompletableDeferred<Unit>? = null
    private var otaDoneSignal: CompletableDeferred<Unit>? = null
    private var otaChunkAckSignal: CompletableDeferred<OtaChunkAck>? = null
    @Volatile private var otaRemoteWrittenBytes = 0
    @Volatile private var otaRemoteExpectedBytes = 0
    @Volatile private var otaRemoteChunkSize = 0
    @Volatile private var otaProtocol2Accepted = false
    @Volatile private var otaUploadInProgress = false
    @Volatile private var otaAcceptedByDevice = false
    @Volatile private var otaRebootPending = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private val telemetryState = MutableStateFlow(
        Telemetry(controllerMessage = "准备连接 ${deviceInfo.name}"),
    )
    private val trackLogEventFlow = MutableSharedFlow<TrackLogEvent>(
        extraBufferCapacity = TRACK_EVENT_BUFFER_CAPACITY,
    )

    override val telemetry: StateFlow<Telemetry> = telemetryState.asStateFlow()
    override val trackLogEvents: SharedFlow<TrackLogEvent> = trackLogEventFlow.asSharedFlow()

    private data class OtaChunkAck(
        val offset: Int,
        val accepted: Boolean,
        val error: String? = null,
    )

    override suspend fun connect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect start name=${deviceInfo.name} address=${deviceInfo.address}")
        requireBluetoothConnectPermission(context)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("当前手机不支持蓝牙")
        check(adapter.isEnabled) { "蓝牙未开启" }

        @SuppressLint("MissingPermission")
        val device = adapter.getRemoteDevice(deviceInfo.address)

        ensureBonded(device)

        val nextSocket = connectBluetoothSocket(adapter = adapter, device = device)

        socket = nextSocket
        outputStream = nextSocket.outputStream
        startReader(nextSocket)
        telemetryState.value = Telemetry(
            batteryVoltage = 0f,
            leftCurrent = 0f,
            rightCurrent = 0f,
            escTemperature = 0f,
            controllerMessage = "经典蓝牙已连接：${device.safeName()}",
        )
        Log.i(TAG, "connect success name=${device.safeName()}")
        send(ControlCommand.Idle)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectBluetoothSocket(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
    ): BluetoothSocket {
        val standardSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        return runCatching {
            standardSocket.connectWithTimeout()
            standardSocket
        }.getOrElse { standardError ->
            Log.w(TAG, "standard SPP connect failed; retrying RFCOMM channel 1", standardError)
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "标准 SPP 连接失败，尝试 RFCOMM channel 1",
            )
            runCatching { standardSocket.close() }
            delay(350)

            val channelSocket = device.createRfcommSocketOnChannel(SPP_CHANNEL)
            adapter.cancelDiscovery()
            runCatching {
                channelSocket.connectWithTimeout()
                channelSocket
            }.getOrElse { channelError ->
                runCatching { channelSocket.close() }
                channelError.addSuppressed(standardError)
                throw channelError
            }
        }
    }

    private suspend fun BluetoothSocket.connectWithTimeout(): Unit = coroutineScope {
        val connectJob = async(Dispatchers.IO) {
            connect()
        }
        try {
            withTimeout(SOCKET_CONNECT_TIMEOUT_MS) {
                connectJob.await()
            }
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    private fun BluetoothDevice.createRfcommSocketOnChannel(channel: Int): BluetoothSocket {
        val method = javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(this, channel) as BluetoothSocket
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "disconnect")
        if (!otaUploadInProgress) {
            runCatching { send(ControlCommand.Idle) }
        }
        closeSocketResources("经典蓝牙已断开")
    }

    override suspend fun closeSilently() = withContext(Dispatchers.IO) {
        Log.i(TAG, "close silently")
        closeSocketResources("经典蓝牙已断开")
    }

    private fun closeSocketResources(message: String) {
        readJob?.cancel()
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        outputStream = null
        socket = null
        lastVisibleCommandLine = null
        telemetryState.value = Telemetry(controllerMessage = message)
    }

    override suspend fun send(command: ControlCommand) = withContext(Dispatchers.IO) {
        writeAsciiLine(command.toWireLine())
    }

    override suspend fun sendRawLine(line: String) = withContext(Dispatchers.IO) {
        writeAsciiLine(line)
    }

    private fun writeAsciiLine(line: String) {
        val visibleLine = line.trim()
        require(visibleLine.isNotEmpty()) { "蓝牙命令不能为空" }
        check(!otaUploadInProgress) { "主控 OTA 进行中，禁止发送普通命令" }
        val stream = outputStream ?: error("蓝牙输出流未连接")
        stream.write("$visibleLine\n".toByteArray(Charsets.US_ASCII))
        stream.flush()
        Log.d(TAG, "tx $visibleLine")
        if (!visibleLine.isInternalConfigLine() && visibleLine != lastVisibleCommandLine) {
            lastVisibleCommandLine = visibleLine
            telemetryState.value = telemetryState.value.copy(
                lastSentCommand = visibleLine,
            )
        }
    }

    override suspend fun uploadFirmware(
        firmware: ByteArray,
        md5Hex: String,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: error("蓝牙输出流未连接")
        require(md5Hex.length == 32) { "固件 MD5 格式错误" }

        send(ControlCommand.Idle)
        delay(300)

        val readySignal = CompletableDeferred<Unit>()
        val doneSignal = CompletableDeferred<Unit>()
        otaReadySignal = readySignal
        otaDoneSignal = doneSignal
        otaRemoteWrittenBytes = 0
        otaRemoteExpectedBytes = firmware.size
        otaRemoteChunkSize = 0
        otaProtocol2Accepted = false
        otaAcceptedByDevice = false
        otaRebootPending = false
        otaUploadInProgress = true

        try {
            val header = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex;PROTO=2;CHUNK=$OTA_CHUNK_SIZE\n"
            stream.write(header.toByteArray(Charsets.US_ASCII))
            stream.flush()
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "主控 OTA 已请求，等待设备进入接收模式",
                lastSentCommand = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex;PROTO=2;CHUNK=$OTA_CHUNK_SIZE",
            )

            withTimeout(OTA_READY_TIMEOUT_MS) { readySignal.await() }

            if (otaProtocol2Accepted && otaRemoteChunkSize > 0) {
                uploadFirmwareChunked(
                    stream = stream,
                    firmware = firmware,
                    doneSignal = doneSignal,
                    onProgress = onProgress,
                )
            } else {
                uploadFirmwareRaw(
                    stream = stream,
                    firmware = firmware,
                    doneSignal = doneSignal,
                    onProgress = onProgress,
                )
            }

            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "主控 OTA 已发送完成，等待设备写完尾部并校验",
            )
            withTimeout(OTA_DONE_TIMEOUT_MS) { doneSignal.await() }
            val confirmedBytes = otaRemoteWrittenBytes
            check(otaAcceptedByDevice || confirmedBytes >= firmware.size) {
                "主控 OTA 未完成：主控已确认 $confirmedBytes/${firmware.size} bytes"
            }
        } finally {
            otaUploadInProgress = false
            otaAcceptedByDevice = false
            otaReadySignal = null
            otaDoneSignal = null
            otaChunkAckSignal = null
            otaRemoteWrittenBytes = 0
            otaRemoteExpectedBytes = 0
            otaRemoteChunkSize = 0
            otaProtocol2Accepted = false
        }
    }

    private suspend fun uploadFirmwareRaw(
        stream: OutputStream,
        firmware: ByteArray,
        doneSignal: CompletableDeferred<Unit>,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    ) {
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "主控使用旧 OTA 接收协议，按连续字节流发送",
        )

        var sentBytes = 0
        var lastLoggedSentBytes = 0
        while (sentBytes < firmware.size) {
            val nextSize = minOf(OTA_RAW_CHUNK_SIZE, firmware.size - sentBytes)
            stream.write(firmware, sentBytes, nextSize)
            stream.flush()
            sentBytes += nextSize
            if (sentBytes - lastLoggedSentBytes >= OTA_PROGRESS_INTERVAL_BYTES || sentBytes == firmware.size) {
                lastLoggedSentBytes = sentBytes
                Log.d(TAG, "ota raw tx sent=$sentBytes remote=$otaRemoteWrittenBytes total=${firmware.size}")
            }
            val progressBytes = otaRemoteWrittenBytes.takeIf { it > 0 } ?: sentBytes
            onProgress(progressBytes.coerceAtMost(firmware.size), firmware.size)
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "主控 OTA 发送中 ${sentBytes * 100 / firmware.size}%；主控已写入 ${progressBytes * 100 / firmware.size}%",
            )
            waitForOtaReceiveWindow(
                sentBytes = sentBytes,
                totalBytes = firmware.size,
                doneSignal = doneSignal,
                onProgress = onProgress,
            )
            delay(OTA_RAW_CHUNK_DELAY_MS)
        }
    }

    private suspend fun uploadFirmwareChunked(
        stream: OutputStream,
        firmware: ByteArray,
        doneSignal: CompletableDeferred<Unit>,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    ) {
        val chunkSize = otaRemoteChunkSize.coerceIn(128, OTA_CHUNK_SIZE)
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "主控使用分块 OTA 协议，每块 $chunkSize bytes",
        )

        var offset = 0
        while (offset < firmware.size) {
            if (doneSignal.isCompleted) {
                doneSignal.await()
                return
            }

            val nextSize = minOf(chunkSize, firmware.size - offset)
            var attempt = 0
            while (true) {
                attempt += 1
                val ackSignal = CompletableDeferred<OtaChunkAck>()
                otaChunkAckSignal = ackSignal
                val crc = crc32Value(firmware, offset, nextSize)
                val header = "OTA_CHUNK;OFFSET=$offset;LEN=$nextSize;CRC=$crc\n"
                stream.write(header.toByteArray(Charsets.US_ASCII))
                stream.write(firmware, offset, nextSize)
                stream.flush()

                val ack = withTimeout(OTA_CHUNK_ACK_TIMEOUT_MS) { ackSignal.await() }
                otaChunkAckSignal = null
                if (ack.accepted && ack.offset == offset + nextSize) {
                    offset = ack.offset
                    otaRemoteWrittenBytes = offset
                    if (offset % OTA_PROGRESS_INTERVAL_BYTES == 0 || offset == firmware.size) {
                        Log.d(TAG, "ota chunk ack offset=$offset total=${firmware.size}")
                    }
                    onProgress(offset, firmware.size)
                    telemetryState.value = telemetryState.value.copy(
                        controllerMessage = "主控 OTA 分块写入 ${offset * 100 / firmware.size}%",
                    )
                    break
                }

                val reason = ack.error ?: "ACK_OFFSET_${ack.offset}"
                if (attempt >= OTA_CHUNK_MAX_RETRIES) {
                    error("主控 OTA 分块确认失败：offset=$offset len=$nextSize reason=$reason")
                }
                telemetryState.value = telemetryState.value.copy(
                    controllerMessage = "主控 OTA 分块重发：offset=$offset reason=$reason",
                )
                delay(OTA_CHUNK_RETRY_DELAY_MS)
            }
        }
    }

    private fun crc32Value(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    private suspend fun waitForOtaReceiveWindow(
        sentBytes: Int,
        totalBytes: Int,
        doneSignal: CompletableDeferred<Unit>,
        onProgress: (sentBytes: Int, totalBytes: Int) -> Unit,
    ) {
        var lastRemoteBytes = otaRemoteWrittenBytes
        var lastRemoteChangeAt = System.currentTimeMillis()
        while (sentBytes - otaRemoteWrittenBytes > OTA_MAX_IN_FLIGHT_BYTES &&
            otaRemoteWrittenBytes < totalBytes
        ) {
            if (doneSignal.isCompleted) {
                doneSignal.await()
                return
            }

            val remoteBytes = otaRemoteWrittenBytes
            if (remoteBytes != lastRemoteBytes) {
                lastRemoteBytes = remoteBytes
                lastRemoteChangeAt = System.currentTimeMillis()
                onProgress(remoteBytes, totalBytes)
            }

            val inFlightBytes = sentBytes - remoteBytes
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "主控 OTA 等待写入反馈：已写入 ${remoteBytes * 100 / totalBytes}%，在途 ${inFlightBytes} bytes",
            )

            check(System.currentTimeMillis() - lastRemoteChangeAt <= OTA_PROGRESS_STALL_TIMEOUT_MS) {
                "主控 OTA 写入进度超时：已发送 $sentBytes bytes，主控已确认 $remoteBytes bytes"
            }
            delay(OTA_WINDOW_WAIT_MS)
        }
    }

    private fun startReader(nextSocket: BluetoothSocket) {
        readJob?.cancel()
        readJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(nextSocket.inputStream, Charsets.US_ASCII)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, "rx $line")
                        handleReceivedLine(line)
                    }
                }
                error("蓝牙读取结束")
            }.onFailure { error ->
                Log.w(TAG, "reader stopped", error)
                if ((otaUploadInProgress && otaLooksAcceptedOrRebooting()) || otaRebootPending) {
                    otaReadySignal?.complete(Unit)
                    otaDoneSignal?.complete(Unit)
                    otaRebootPending = false
                    Log.i(TAG, "ota rebooting disconnect detected")
                    telemetryState.value = telemetryState.value.copy(
                        controllerMessage = "主控 OTA 已写满，蓝牙断开，等待重连验证固件版本",
                        lastReceivedStatus = "OTA;REBOOTING",
                    )
                    return@onFailure
                }
                otaReadySignal?.completeExceptionally(error)
                otaDoneSignal?.completeExceptionally(error)
                otaChunkAckSignal?.completeExceptionally(error)
                telemetryState.value = telemetryState.value.copy(
                    controllerMessage = "蓝牙读取停止：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    private fun otaLooksAcceptedOrRebooting(): Boolean {
        return otaAcceptedByDevice ||
            (otaRemoteExpectedBytes > 0 && otaRemoteWrittenBytes >= otaRemoteExpectedBytes)
    }

    private fun handleReceivedLine(line: String) {
        handleOtaSignal(line)
        parseTrackLogEvent(line)?.let { event ->
            trackLogEventFlow.tryEmit(event)
            telemetryState.value = telemetryState.value.copy(lastReceivedStatus = line)
            return
        }

        telemetryState.value = telemetryState.value.withStatusLine(line)
    }

    private fun handleOtaSignal(line: String) {
        if (!line.startsWith("OTA;")) {
            return
        }
        when {
            line.startsWith("OTA;READY") -> {
                val fields = line.parseSemicolonFields()
                fields["SIZE"]?.toIntOrNull()?.let { otaRemoteExpectedBytes = it }
                val protocol = fields["PROTO"]?.toIntOrNull()
                val chunkSize = fields["CHUNK"]?.toIntOrNull()
                otaProtocol2Accepted = protocol == 2 && chunkSize != null && chunkSize > 0
                otaRemoteChunkSize = chunkSize ?: 0
                otaReadySignal?.complete(Unit)
            }
            line.startsWith("OTA;ACK") -> {
                val offset = line.parseSemicolonFields()["OFFSET"]?.toIntOrNull()
                if (offset != null) {
                    otaRemoteWrittenBytes = offset
                    otaChunkAckSignal?.complete(OtaChunkAck(offset = offset, accepted = true))
                }
            }
            line.startsWith("OTA;NACK") -> {
                val fields = line.parseSemicolonFields()
                val offset = fields["OFFSET"]?.toIntOrNull() ?: otaRemoteWrittenBytes
                val error = fields["ERR"] ?: "NACK"
                otaChunkAckSignal?.complete(
                    OtaChunkAck(offset = offset, accepted = false, error = error),
                )
            }
            line.startsWith("OTA;PROGRESS=") -> {
                parseOtaProgress(line)?.let { (writtenBytes, expectedBytes) ->
                    otaRemoteWrittenBytes = writtenBytes
                    otaRemoteExpectedBytes = expectedBytes
                }
            }
            line.startsWith("OTA;OK") -> {
                line.parseSemicolonFields()["BYTES"]?.toIntOrNull()?.let { otaRemoteWrittenBytes = it }
                otaAcceptedByDevice = true
                otaRebootPending = true
                otaReadySignal?.complete(Unit)
                otaDoneSignal?.complete(Unit)
            }
            line.startsWith("OTA;ERR=") -> {
                val error = IllegalStateException("主控 OTA 失败：${line.removePrefix("OTA;ERR=")}")
                otaReadySignal?.completeExceptionally(error)
                otaDoneSignal?.completeExceptionally(error)
                otaChunkAckSignal?.completeExceptionally(error)
            }
        }
    }

    private fun parseOtaProgress(line: String): Pair<Int, Int>? {
        val parts = line.removePrefix("OTA;PROGRESS=").split('/', limit = 2)
        if (parts.size != 2) {
            return null
        }
        val writtenBytes = parts[0].toIntOrNull() ?: return null
        val expectedBytes = parts[1].toIntOrNull() ?: return null
        return writtenBytes to expectedBytes
    }

    private fun Telemetry.withStatusLine(line: String): Telemetry {
        if (line.startsWith("ESC_CFG;")) {
            return this
        }

        if (line.startsWith("OTA;")) {
            return copy(
                controllerMessage = otaStatusMessage(line),
                lastReceivedStatus = line,
            )
        }

        if (line.startsWith("ERR;")) {
            return copy(
                controllerMessage = "主控错误：${line.removePrefix("ERR;")}",
                lastReceivedStatus = line,
            )
        }

        val fields = line.parseControllerFields()
        val isFullStatus = fields.containsKey("L") || fields.containsKey("R") || fields.containsKey("LPWM")
        val nextStatusFields = when {
            fields.isEmpty() -> statusFields
            isFullStatus -> fields
            else -> statusFields + fields
        }
        return copy(
            controllerMessage = fields["FAULT"]?.let { "主控故障：$it" } ?: "主控在线",
            imuAvailable = fields["IMU"]?.let { it == "1" } ?: imuAvailable,
            ybImuAvailable = fields["YBIMU"]?.let { it == "1" } ?: ybImuAvailable,
            headingDegrees = fields["HDG"]?.toFloatOrNull() ?: headingDegrees,
            ybHeadingDegrees = fields["YBHDG"]?.toFloatOrNull() ?: ybHeadingDegrees,
            targetHeadingDegrees = when {
                fields.containsKey("TARGET") -> fields["TARGET"]?.toFloatOrNull()
                isFullStatus && fields["TURN"] != "ACTIVE" && fields["HLOCK"] != "ACTIVE" -> null
                else -> targetHeadingDegrees
            },
            ybAccelXG = fields["YBAX"]?.toFloatOrNull() ?: ybAccelXG,
            ybAccelYG = fields["YBAY"]?.toFloatOrNull() ?: ybAccelYG,
            ybAccelZG = fields["YBAZ"]?.toFloatOrNull() ?: ybAccelZG,
            ybGyroZRadS = fields["YBGZ"]?.toFloatOrNull() ?: ybGyroZRadS,
            ybMagXUt = fields["YBMX"]?.toFloatOrNull() ?: ybMagXUt,
            ybMagYUt = fields["YBMY"]?.toFloatOrNull() ?: ybMagYUt,
            ybMagZUt = fields["YBMZ"]?.toFloatOrNull() ?: ybMagZUt,
            ybImuCalibrationState = fields["YBCIMU"]?.toIntOrNull() ?: ybImuCalibrationState,
            ybMagCalibrationState = fields["YBCMAG"]?.toIntOrNull() ?: ybMagCalibrationState,
            ybQuatW = fields["YBQW"]?.toFloatOrNull() ?: ybQuatW,
            ybQuatX = fields["YBQX"]?.toFloatOrNull() ?: ybQuatX,
            ybQuatY = fields["YBQY"]?.toFloatOrNull() ?: ybQuatY,
            ybQuatZ = fields["YBQZ"]?.toFloatOrNull() ?: ybQuatZ,
            ybRollDegrees = fields["YBR"]?.toFloatOrNull() ?: ybRollDegrees,
            ybPitchDegrees = fields["YBP"]?.toFloatOrNull() ?: ybPitchDegrees,
            ybYawDegrees = fields["YBY"]?.toFloatOrNull() ?: ybYawDegrees,
            leftOutputPercent = fields["L"]?.toIntOrNull() ?: leftOutputPercent,
            rightOutputPercent = fields["R"]?.toIntOrNull() ?: rightOutputPercent,
            statusFields = nextStatusFields,
            lastReceivedStatus = line,
        )
    }

    private fun otaStatusMessage(line: String): String {
        return when {
            line.startsWith("OTA;READY") -> "主控 OTA 准备接收"
            line.startsWith("OTA;ACK") -> "主控 OTA 分块确认：${line.removePrefix("OTA;ACK;")}"
            line.startsWith("OTA;NACK") -> "主控 OTA 分块拒收：${line.removePrefix("OTA;NACK;")}"
            line.startsWith("OTA;PROGRESS=") -> "主控 OTA 写入中 ${line.removePrefix("OTA;PROGRESS=")}"
            line.startsWith("OTA;OK") -> "主控 OTA 校验通过，设备正在重启"
            line.startsWith("OTA;ERR=") -> "主控 OTA 失败：${line.removePrefix("OTA;ERR=")}"
            else -> "主控 OTA：$line"
        }
    }

    private fun String.parseControllerFields(): Map<String, String> {
        if (!startsWith("STATUS;") && !startsWith("INFO;")) {
            return emptyMap()
        }
        return parseSemicolonFields()
    }

    private fun String.isInternalConfigLine(): Boolean {
        return startsWith("ESC_CFG;") || this == "ESC_CFG"
    }

    private fun parseTrackLogEvent(line: String): TrackLogEvent? {
        val fields = line.parseSemicolonFields()
        return when {
            line.startsWith("LOG_INFO;ERR=") -> TrackLogEvent.Error(fields["ERR"].orEmpty())
            line.startsWith("LOG_BEGIN;ERR=") -> TrackLogEvent.Error(fields["ERR"].orEmpty())
            line.startsWith("LOG_INFO;") -> TrackLogEvent.Info(
                TrackLogInfo(
                    recordSize = fields["REC"]?.toIntOrNull() ?: 0,
                    capacity = fields["CAP"]?.toIntOrNull() ?: 0,
                    count = fields["COUNT"]?.toIntOrNull() ?: 0,
                    oldestSequence = fields["OLDEST"]?.toIntOrNull() ?: 0,
                    newestSequence = fields["NEWEST"]?.toIntOrNull() ?: 0,
                    sessionId = fields["SESSION"]?.toIntOrNull() ?: 0,
                    droppedInvalid = fields["DROP_INVALID"]?.toIntOrNull() ?: 0,
                    droppedDrift = fields["DROP_DRIFT"]?.toIntOrNull() ?: 0,
                    writeErrors = fields["WRITE_ERR"]?.toIntOrNull() ?: 0,
                ),
            )
            line.startsWith("LOG_BEGIN;") -> TrackLogEvent.Begin(
                fromSequence = fields["FROM"]?.toIntOrNull() ?: 0,
                count = fields["COUNT"]?.toIntOrNull() ?: 0,
            )
            line.startsWith("LOG_POINT;") -> TrackLogEvent.Point(
                GpsTrackPoint(
                    sequence = fields["SEQ"]?.toIntOrNull() ?: return TrackLogEvent.Error("BAD_POINT"),
                    sessionId = fields["SID"]?.toIntOrNull() ?: 0,
                    utcSeconds = fields["T"]?.toLongOrNull() ?: return TrackLogEvent.Error("BAD_POINT"),
                    latitudeE7 = fields["LAT"]?.toIntOrNull() ?: return TrackLogEvent.Error("BAD_POINT"),
                    longitudeE7 = fields["LON"]?.toIntOrNull() ?: return TrackLogEvent.Error("BAD_POINT"),
                ),
            )
            line.startsWith("LOG_END;") -> TrackLogEvent.End(
                nextSequence = fields["NEXT"]?.toIntOrNull() ?: 0,
            )
            else -> null
        }
    }

    private fun String.parseSemicolonFields(): Map<String, String> {
        return split(';')
            .drop(1)
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }
            .toMap()
    }

    private suspend fun ensureBonded(device: BluetoothDevice) {
        @SuppressLint("MissingPermission")
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return
        }

        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "等待系统蓝牙配对：${device.safeName()}",
        )
        Log.i(TAG, "create bond name=${device.safeName()} address=${device.address}")

        @SuppressLint("MissingPermission")
        check(device.createBond()) { "无法发起蓝牙配对" }

        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < BOND_TIMEOUT_MS) {
            @SuppressLint("MissingPermission")
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "bond success name=${device.safeName()}")
                return
            }
            delay(500)
        }
        error("蓝牙配对超时")
    }

    private fun BluetoothDevice.safeName(): String {
        return runCatching {
            @SuppressLint("MissingPermission")
            name
        }.getOrNull().orEmpty().ifBlank { deviceInfo.name }
    }

    companion object {
        private const val TAG = "SmartSupBt"
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 2_500L
        private const val OTA_CHUNK_SIZE = 1024
        private const val OTA_RAW_CHUNK_SIZE = 256
        private const val OTA_RAW_CHUNK_DELAY_MS = 20L
        private const val OTA_MAX_IN_FLIGHT_BYTES = 4 * 1024 * 1024
        private const val OTA_PROGRESS_INTERVAL_BYTES = 16 * 1024
        private const val OTA_WINDOW_WAIT_MS = 80L
        private const val OTA_PROGRESS_STALL_TIMEOUT_MS = 45_000L
        private const val OTA_CHUNK_ACK_TIMEOUT_MS = 8_000L
        private const val OTA_CHUNK_MAX_RETRIES = 3
        private const val OTA_CHUNK_RETRY_DELAY_MS = 120L
        private const val OTA_READY_TIMEOUT_MS = 5_000L
        private const val OTA_DONE_TIMEOUT_MS = 120_000L
        private const val TRACK_EVENT_BUFFER_CAPACITY = 256
        private const val SPP_CHANNEL = 1
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun hasBluetoothConnectPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasBluetoothScanPermission(context: Context): Boolean {
            val hasScanPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN,
                ) == PackageManager.PERMISSION_GRANTED
            val hasLocationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            return hasScanPermission && hasLocationPermission
        }

        fun requireBluetoothConnectPermission(context: Context) {
            check(hasBluetoothConnectPermission(context)) { "缺少蓝牙连接权限" }
        }

        fun pairedDevices(context: Context): List<BluetoothDeviceInfo> {
            if (!hasBluetoothConnectPermission(context)) {
                return emptyList()
            }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return runCatching {
                @SuppressLint("MissingPermission")
                adapter.bondedDevices
                    .orEmpty()
                    .map { device ->
                        BluetoothDeviceInfo(
                            name = device.name.orEmpty().ifBlank { "未命名设备" },
                            address = device.address,
                        )
                    }
                    .sortedWith(compareBy<BluetoothDeviceInfo> { it.name }.thenBy { it.address })
            }.getOrDefault(emptyList())
        }

        fun isBluetoothAvailable(): Boolean = BluetoothAdapter.getDefaultAdapter() != null

        fun isBluetoothEnabled(): Boolean = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    }
}
