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
import com.smartsup.controller.model.Telemetry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class BluetoothClassicTransport(
    private val context: Context,
    private val deviceInfo: BluetoothDeviceInfo,
) : ControlTransport {
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var lastVisibleCommandLine: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private val telemetryState = MutableStateFlow(
        Telemetry(controllerMessage = "准备连接 ${deviceInfo.name}"),
    )

    override val telemetry: StateFlow<Telemetry> = telemetryState.asStateFlow()

    override suspend fun connect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect start name=${deviceInfo.name} address=${deviceInfo.address}")
        requireBluetoothConnectPermission(context)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("当前手机不支持蓝牙")
        check(adapter.isEnabled) { "蓝牙未开启" }

        @SuppressLint("MissingPermission")
        val device = adapter.getRemoteDevice(deviceInfo.address)

        ensureBonded(device)

        @SuppressLint("MissingPermission")
        val nextSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

        @SuppressLint("MissingPermission")
        adapter.cancelDiscovery()
        nextSocket.connect()

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

    private fun writeAsciiLine(line: String) {
        val visibleLine = line.trim()
        require(visibleLine.isNotEmpty()) { "蓝牙命令不能为空" }
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

        val header = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex\n"
        stream.write(header.toByteArray(Charsets.US_ASCII))
        stream.flush()
        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "ESP32 OTA 已开始",
            lastSentCommand = "OTA_BEGIN;SIZE=${firmware.size};MD5=$md5Hex",
        )
        delay(700)

        var sentBytes = 0
        while (sentBytes < firmware.size) {
            val nextSize = minOf(OTA_CHUNK_SIZE, firmware.size - sentBytes)
            stream.write(firmware, sentBytes, nextSize)
            stream.flush()
            sentBytes += nextSize
            onProgress(sentBytes, firmware.size)
            telemetryState.value = telemetryState.value.copy(
                controllerMessage = "ESP32 OTA 发送中 ${sentBytes * 100 / firmware.size}%",
            )
            delay(OTA_CHUNK_DELAY_MS)
        }

        telemetryState.value = telemetryState.value.copy(
            controllerMessage = "ESP32 OTA 已发送完成，等待设备校验并重启",
        )
    }

    private fun startReader(nextSocket: BluetoothSocket) {
        readJob?.cancel()
        readJob = scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(nextSocket.inputStream, Charsets.US_ASCII)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, "rx $line")
                        telemetryState.value = telemetryState.value.withStatusLine(line)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "reader stopped", error)
                telemetryState.value = telemetryState.value.copy(
                    controllerMessage = "蓝牙读取停止：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    private fun Telemetry.withStatusLine(line: String): Telemetry {
        val fields = line.parseStatusFields()
        val isFullStatus = fields.containsKey("L") || fields.containsKey("R") || fields.containsKey("LPWM")
        val nextStatusFields = when {
            fields.isEmpty() -> statusFields
            isFullStatus -> fields
            else -> statusFields + fields
        }
        return copy(
            controllerMessage = fields["FAULT"]?.let { "ESP32 故障：$it" } ?: "ESP32 在线",
            imuAvailable = fields["IMU"]?.let { it == "1" } ?: imuAvailable,
            headingDegrees = fields["HDG"]?.toFloatOrNull() ?: headingDegrees,
            targetHeadingDegrees = when {
                fields.containsKey("TARGET") -> fields["TARGET"]?.toFloatOrNull()
                isFullStatus && fields["TURN"] != "ACTIVE" && fields["HLOCK"] != "ACTIVE" -> null
                else -> targetHeadingDegrees
            },
            statusFields = nextStatusFields,
            lastReceivedStatus = line,
        )
    }

    private fun String.parseStatusFields(): Map<String, String> {
        if (!startsWith("STATUS;")) {
            return emptyMap()
        }
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
        private const val OTA_CHUNK_SIZE = 512
        private const val OTA_CHUNK_DELAY_MS = 8L
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
