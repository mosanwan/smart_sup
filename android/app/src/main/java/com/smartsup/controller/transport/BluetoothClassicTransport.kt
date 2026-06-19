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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    @Volatile private var otaRemoteWrittenBytes = 0
    @Volatile private var otaRemoteExpectedBytes = 0
    @Volatile private var otaUploadInProgress = false
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
            standardSocket.connect()
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
                channelSocket.connect()
                channelSocket
            }.getOrElse { channelError ->
                runCatching { channelSocket.close() }
                channelError.addSuppressed(standardError)
                throw channelError
            }
        }
    }

    private fun BluetoothDevice.createRfcommSocketOnChannel(channel: Int): BluetoothSocket {
        val method = javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(this, channel) as BluetoothSocket
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "disconnect")
        runCatching { send(ControlCommand.Idle) }
        readJob?.cancel()
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        outputStream = null
        socket = null
        lastVisibleCommandLine = null
        telemetryState.value = Telemetry(controllerMessage = "经典蓝牙已断开")
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
        check(!otaUploadInProgress) { "ESP32 OTA 进行中，禁止发送普通命令" }
        val stream = outputStream ?: error("蓝牙输出流未连接")
        stream.write("$visibleLine\n".toByteArray(Charsets.US_ASCII))
        stream.flush()
        Log.d(TAG, "tx $visibleLine")
        if (visibleLine != lastVisibleCommandLine) {
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
        otaUploadInProgress = true

        try {
            val header = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex\n"
            stream.write(header.toByteArray(Charsets.US_ASCII))
            stream.flush()
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "ESP32 OTA 已请求，等待设备进入接收模式",
                lastSentCommand = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex",
            )

            withTimeout(OTA_READY_TIMEOUT_MS) { readySignal.await() }

            var sentBytes = 0
            while (sentBytes < firmware.size) {
                val nextSize = minOf(OTA_CHUNK_SIZE, firmware.size - sentBytes)
                stream.write(firmware, sentBytes, nextSize)
                stream.flush()
                sentBytes += nextSize
                val progressBytes = otaRemoteWrittenBytes.takeIf { it > 0 } ?: sentBytes
                onProgress(progressBytes.coerceAtMost(firmware.size), firmware.size)
                telemetryState.value = telemetryState.value.copy(
                    controllerMessage = "ESP32 OTA 发送中 ${sentBytes * 100 / firmware.size}%；ESP 已写入 ${progressBytes * 100 / firmware.size}%",
                )
                waitForOtaReceiveWindow(
                    sentBytes = sentBytes,
                    totalBytes = firmware.size,
                    doneSignal = doneSignal,
                    onProgress = onProgress,
                )
                delay(OTA_CHUNK_DELAY_MS)
            }

            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "ESP32 OTA 已发送完成，等待设备写完尾部并校验",
            )
            withTimeout(OTA_DONE_TIMEOUT_MS) { doneSignal.await() }
        } finally {
            otaUploadInProgress = false
            otaReadySignal = null
            otaDoneSignal = null
            otaRemoteWrittenBytes = 0
            otaRemoteExpectedBytes = 0
        }
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
                controllerMessage = "ESP32 OTA 等待写入反馈：已写入 ${remoteBytes * 100 / totalBytes}%，在途 ${inFlightBytes} bytes",
            )

            check(System.currentTimeMillis() - lastRemoteChangeAt <= OTA_PROGRESS_STALL_TIMEOUT_MS) {
                "ESP32 OTA 写入进度超时"
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
                otaReadySignal?.completeExceptionally(error)
                otaDoneSignal?.completeExceptionally(error)
                telemetryState.value = telemetryState.value.copy(
                    controllerMessage = "蓝牙读取停止：${error.message ?: "未知错误"}",
                )
            }
        }
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
                line.parseSemicolonFields()["SIZE"]?.toIntOrNull()?.let { otaRemoteExpectedBytes = it }
                otaReadySignal?.complete(Unit)
            }
            line.startsWith("OTA;PROGRESS=") -> {
                parseOtaProgress(line)?.let { (writtenBytes, expectedBytes) ->
                    otaRemoteWrittenBytes = writtenBytes
                    otaRemoteExpectedBytes = expectedBytes
                }
            }
            line.startsWith("OTA;OK") -> {
                line.parseSemicolonFields()["BYTES"]?.toIntOrNull()?.let { otaRemoteWrittenBytes = it }
                otaReadySignal?.complete(Unit)
                otaDoneSignal?.complete(Unit)
            }
            line.startsWith("OTA;ERR=") -> {
                val error = IllegalStateException("ESP32 OTA 失败：${line.removePrefix("OTA;ERR=")}")
                otaReadySignal?.completeExceptionally(error)
                otaDoneSignal?.completeExceptionally(error)
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
        if (line.startsWith("OTA;")) {
            return copy(
                controllerMessage = otaStatusMessage(line),
                lastReceivedStatus = line,
            )
        }

        if (line.startsWith("ERR;")) {
            return copy(
                controllerMessage = "ESP32 错误：${line.removePrefix("ERR;")}",
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
            controllerMessage = fields["FAULT"]?.let { "ESP32 故障：$it" } ?: "ESP32 在线",
            imuAvailable = fields["IMU"]?.let { it == "1" } ?: imuAvailable,
            ybImuAvailable = fields["YBIMU"]?.let { it == "1" } ?: ybImuAvailable,
            headingDegrees = fields["HDG"]?.toFloatOrNull() ?: headingDegrees,
            targetHeadingDegrees = when {
                fields.containsKey("TARGET") -> fields["TARGET"]?.toFloatOrNull()
                isFullStatus && fields["TURN"] != "ACTIVE" && fields["HLOCK"] != "ACTIVE" -> null
                else -> targetHeadingDegrees
            },
            ybAccelXG = fields["YBAX"]?.toFloatOrNull() ?: ybAccelXG,
            ybAccelYG = fields["YBAY"]?.toFloatOrNull() ?: ybAccelYG,
            ybAccelZG = fields["YBAZ"]?.toFloatOrNull() ?: ybAccelZG,
            ybGyroZRadS = fields["YBGZ"]?.toFloatOrNull() ?: ybGyroZRadS,
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
            line.startsWith("OTA;READY") -> "ESP32 OTA 准备接收"
            line.startsWith("OTA;PROGRESS=") -> "ESP32 OTA 写入中 ${line.removePrefix("OTA;PROGRESS=")}"
            line.startsWith("OTA;OK") -> "ESP32 OTA 校验通过，设备正在重启"
            line.startsWith("OTA;ERR=") -> "ESP32 OTA 失败：${line.removePrefix("OTA;ERR=")}"
            else -> "ESP32 OTA：$line"
        }
    }

    private fun String.parseControllerFields(): Map<String, String> {
        if (!startsWith("STATUS;") && !startsWith("INFO;")) {
            return emptyMap()
        }
        return parseSemicolonFields()
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
        private const val OTA_CHUNK_SIZE = 256
        private const val OTA_CHUNK_DELAY_MS = 6L
        private const val OTA_MAX_IN_FLIGHT_BYTES = 12 * 1024
        private const val OTA_WINDOW_WAIT_MS = 80L
        private const val OTA_PROGRESS_STALL_TIMEOUT_MS = 12_000L
        private const val OTA_READY_TIMEOUT_MS = 5_000L
        private const val OTA_DONE_TIMEOUT_MS = 45_000L
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
