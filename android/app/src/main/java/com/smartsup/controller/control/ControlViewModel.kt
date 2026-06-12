package com.smartsup.controller.control

import android.app.Application
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.ReleaseInfo
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.UpdateUiState
import com.smartsup.controller.transport.BluetoothClassicTransport
import com.smartsup.controller.transport.ControlTransport
import com.smartsup.controller.update.AppUpdateInstaller
import com.smartsup.controller.update.GitHubReleaseClient
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ControlViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var transport: ControlTransport? = null
    private var telemetryJob: Job? = null
    private var commandHeartbeatJob: Job? = null
    private var autoReconnectAttempted = false
    private var discoveryReceiverRegistered = false
    private val releaseClient = GitHubReleaseClient(BuildConfig.GITHUB_REPOSITORY) {
        preferences.getString(KEY_GITHUB_TOKEN, null)
    }
    private val appUpdateInstaller = AppUpdateInstaller(application)
    private var latestRelease: ReleaseInfo? = null
    private var downloadedApk: File? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> handleDiscoveredDevice(intent)
                BluetoothDevice.ACTION_NAME_CHANGED -> handleDiscoveredDevice(intent)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    mutableSettingsState.update {
                        it.copy(
                            discovering = false,
                            message = if (it.discoveredDevices.isEmpty()) {
                                "未发现 ${it.deviceNamePrefix} 设备，请确认 ESP32 已上电且蓝牙可见"
                            } else {
                                "扫描完成，发现 ${it.discoveredDevices.size} 个 SmartSUP 设备"
                            },
                        )
                    }
                }
            }
        }
    }

    private val mutableUiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = mutableUiState.asStateFlow()

    private val mutableSettingsState = MutableStateFlow(loadSettingsState())
    val settingsState: StateFlow<SettingsUiState> = mutableSettingsState.asStateFlow()

    private val mutableUpdateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdateState.asStateFlow()

    init {
        refreshBluetoothDevices()
    }

    fun connect() {
        connectSavedBluetooth()
    }

    fun connectSavedBluetooth() {
        val savedDevice = mutableSettingsState.value.savedDevice
        if (savedDevice == null) {
            mutableSettingsState.update {
                it.copy(message = "还没有保存 ESP32 蓝牙设备，请先从已配对设备中选择")
            }
            return
        }
        connectBluetooth(savedDevice)
    }

    fun connectBluetooth(deviceInfo: BluetoothDeviceInfo) {
        saveBluetoothDevice(deviceInfo)
        viewModelScope.launch {
            stopBluetoothDiscovery()
            mutableUiState.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    selectedGear = ThrottleGear.Neutral,
                    statusMessage = "正在连接 ${deviceInfo.name}",
                )
            }
            mutableSettingsState.update { it.copy(message = "正在连接 ${deviceInfo.name}") }

            val nextTransport = BluetoothClassicTransport(getApplication(), deviceInfo)
            runCatching {
                nextTransport.connect()
            }.onSuccess {
                transport = nextTransport
                collectTelemetry(nextTransport)
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        statusMessage = "已连接 ${deviceInfo.name}，仍处于锁定状态",
                    )
                }
                mutableSettingsState.update {
                    it.copy(message = "已保存并连接 ${deviceInfo.name}")
                }
                startCommandHeartbeat()
                sendIdle()
            }.onFailure { error ->
                transport = null
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        selectedGear = ThrottleGear.Neutral,
                        statusMessage = "连接失败：${error.message ?: "未知错误"}",
                    )
                }
                mutableSettingsState.update {
                    it.copy(message = "连接失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun disconnect() {
        disconnectBluetooth()
    }

    fun disconnectBluetooth() {
        viewModelScope.launch {
            runCatching { transport?.disconnect() }
            transport = null
            telemetryJob?.cancel()
            commandHeartbeatJob?.cancel()
            mutableUiState.value = ControlUiState(
                statusMessage = "已断开，推进输出保持空闲",
            )
            mutableSettingsState.update { it.copy(message = "已断开 ESP32 连接") }
        }
    }

    fun setArmed(armed: Boolean) {
        mutableUiState.update {
            if (armed && it.connectionState == ConnectionState.Connected) {
                it.copy(armed = true, statusMessage = "已解锁，请保持低功率测试")
            } else {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    selectedGear = ThrottleGear.Neutral,
                    statusMessage = "已锁定，油门回空挡",
                )
            }
        }
        sendCurrentCommand()
    }

    fun setLeftThrottle(percent: Int) {
        mutableUiState.update {
            it.copy(leftThrottlePercent = coerceSignedThrottle(percent))
        }
        sendCurrentCommand()
    }

    fun setRightThrottle(percent: Int) {
        mutableUiState.update {
            it.copy(rightThrottlePercent = coerceSignedThrottle(percent))
        }
        sendCurrentCommand()
    }

    fun returnLeftThrottleToGear() {
        mutableUiState.update {
            it.copy(leftThrottlePercent = coerceSignedThrottle(gearPercent(it.selectedGear)))
        }
        sendCurrentCommand()
    }

    fun returnRightThrottleToGear() {
        mutableUiState.update {
            it.copy(rightThrottlePercent = coerceSignedThrottle(gearPercent(it.selectedGear)))
        }
        sendCurrentCommand()
    }

    fun setThrottleGear(gear: ThrottleGear) {
        val gearThrottle = coerceSignedThrottle(gearPercent(gear))
        mutableUiState.update {
            it.copy(
                selectedGear = gear,
                leftThrottlePercent = gearThrottle,
                rightThrottlePercent = gearThrottle,
                statusMessage = "当前档位：${gear.label} ${gearThrottle.signedPercentText()}",
            )
        }
        sendCurrentCommand()
    }

    fun setGearThrottlePercent(gear: ThrottleGear, percent: Int) {
        if (gear == ThrottleGear.Neutral) {
            return
        }
        val constrained = coerceGearPercent(gear, percent)
        preferences.edit()
            .putInt(gearPreferenceKey(gear), constrained)
            .apply()
        mutableSettingsState.update {
            it.copy(gearPercents = it.gearPercents + (gear to constrained))
        }
        if (mutableUiState.value.selectedGear == gear) {
            val gearThrottle = coerceSignedThrottle(constrained)
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = gearThrottle,
                    rightThrottlePercent = gearThrottle,
                    statusMessage = "当前档位：${gear.label} ${gearThrottle.signedPercentText()}",
                )
            }
            sendCurrentCommand()
        }
    }

    fun emergencyStop() {
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                selectedGear = ThrottleGear.Neutral,
                statusMessage = "急停已触发，油门回空挡",
            )
        }
        sendIdle()
    }

    fun setBluetoothPermissionGranted(granted: Boolean) {
        mutableSettingsState.update {
            it.copy(
                bluetoothPermissionGranted = granted,
                message = if (granted) it.message else "缺少蓝牙权限，无法读取已配对设备",
            )
        }
        refreshBluetoothDevices()

        val state = mutableSettingsState.value
        if (
            granted &&
            state.autoReconnect &&
            state.savedDevice != null &&
            !autoReconnectAttempted
        ) {
            autoReconnectAttempted = true
            connectBluetooth(state.savedDevice)
        }
    }

    fun refreshBluetoothDevices() {
        val context = getApplication<Application>()
        val bluetoothAvailable = BluetoothClassicTransport.isBluetoothAvailable()
        val bluetoothEnabled = BluetoothClassicTransport.isBluetoothEnabled()
        val permissionGranted = BluetoothClassicTransport.hasBluetoothConnectPermission(context) &&
            BluetoothClassicTransport.hasBluetoothScanPermission(context)
        val pairedDevices = if (permissionGranted) {
            BluetoothClassicTransport.pairedDevices(context)
                .filter { it.name.startsWith(SMART_SUP_DEVICE_PREFIX) }
        } else {
            emptyList()
        }

        mutableSettingsState.update {
            it.copy(
                bluetoothAvailable = bluetoothAvailable,
                bluetoothEnabled = bluetoothEnabled,
                bluetoothPermissionGranted = permissionGranted,
                pairedDevices = pairedDevices,
                message = when {
                    !bluetoothAvailable -> "当前手机不支持蓝牙"
                    !bluetoothEnabled -> "请先打开手机蓝牙"
                    !permissionGranted -> "请授予蓝牙扫描和连接权限"
                    it.savedDevice != null -> "已保存 ${it.savedDevice.name}，可直接连接"
                    else -> "点击扫描，发现名称以 $SMART_SUP_DEVICE_PREFIX 开头的 ESP32"
                },
            )
        }
    }

    fun startBluetoothDiscovery() {
        val context = getApplication<Application>()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        when {
            adapter == null -> {
                mutableSettingsState.update { it.copy(message = "当前手机不支持蓝牙") }
                return
            }
            !BluetoothClassicTransport.isBluetoothEnabled() -> {
                mutableSettingsState.update { it.copy(message = "请先打开手机蓝牙") }
                return
            }
            !mutableSettingsState.value.bluetoothPermissionGranted -> {
                mutableSettingsState.update { it.copy(message = "缺少蓝牙扫描/连接权限") }
                return
            }
        }

        registerDiscoveryReceiver(context)
        runCatching {
            @SuppressLint("MissingPermission")
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            mutableSettingsState.update {
                it.copy(
                    discovering = true,
                    discoveredDevices = emptyList(),
                    message = "正在扫描 $SMART_SUP_DEVICE_PREFIX 设备",
                )
            }
            @SuppressLint("MissingPermission")
            check(adapter.startDiscovery()) { "蓝牙扫描启动失败" }
        }.onFailure { error ->
            mutableSettingsState.update {
                it.copy(
                    discovering = false,
                    message = "扫描失败：${error.message ?: "未知错误"}",
                )
            }
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        mutableSettingsState.update { it.copy(autoReconnect = enabled) }
    }

    fun setMaxThrottlePercent(percent: Int) {
        val constrained = percent.coerceIn(5, 100)
        preferences.edit().putInt(KEY_MAX_THROTTLE, constrained).apply()
        mutableSettingsState.update { it.copy(maxThrottlePercent = constrained) }
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = it.leftThrottlePercent.coerceIn(-constrained, constrained),
                rightThrottlePercent = it.rightThrottlePercent.coerceIn(-constrained, constrained),
            )
        }
        sendCurrentCommand()
    }

    fun setRampLimitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RAMP_LIMIT, enabled).apply()
        mutableSettingsState.update { it.copy(rampLimitEnabled = enabled) }
    }

    fun setLeftEscReversed(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LEFT_ESC_REVERSED, enabled).apply()
        mutableSettingsState.update { it.copy(leftEscReversed = enabled) }
        lockForDirectionChange("左 ESC 方向${if (enabled) "已反转" else "已恢复"}")
    }

    fun setRightEscReversed(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RIGHT_ESC_REVERSED, enabled).apply()
        mutableSettingsState.update { it.copy(rightEscReversed = enabled) }
        lockForDirectionChange("右 ESC 方向${if (enabled) "已反转" else "已恢复"}")
    }

    fun setEsp32UnitIdInput(value: String) {
        val digits = value.filter { it.isDigit() }.take(3)
        mutableSettingsState.update { it.copy(esp32UnitIdInput = digits) }
    }

    fun writeEsp32UnitId() {
        val state = mutableSettingsState.value
        val unitId = state.esp32UnitIdInput.toIntOrNull()
        val nextTransport = transport
        if (unitId == null || unitId !in 0..999) {
            mutableSettingsState.update { it.copy(message = "ESP32 编号必须在 000-999 之间") }
            return
        }
        if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
            mutableSettingsState.update { it.copy(message = "请先连接 ESP32，再写入编号") }
            return
        }

        val paddedUnitId = String.format(Locale.US, "%03d", unitId)
        val nextName = "$SMART_SUP_DEVICE_PREFIX$paddedUnitId"
        viewModelScope.launch {
            commandHeartbeatJob?.cancel()
            mutableUiState.update {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    selectedGear = ThrottleGear.Neutral,
                    statusMessage = "正在写入 ESP32 编号，推进输出回空挡",
                )
            }

            runCatching {
                nextTransport.send(ControlCommand.Idle)
                nextTransport.sendRawLine("ID_SET;VALUE=$paddedUnitId")
            }.onSuccess {
                preferences.edit().putString(KEY_DEVICE_NAME, nextName).apply()
                mutableSettingsState.update {
                    it.copy(
                        savedDevice = it.savedDevice?.copy(name = nextName),
                        message = "已写入 ESP32 编号 $paddedUnitId，设备将重启为 $nextName",
                    )
                }
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        statusMessage = "ESP32 正在重启，请稍后重新扫描 $nextName",
                    )
                }
                delay(1200)
                runCatching { nextTransport.disconnect() }
                transport = null
                telemetryJob?.cancel()
                refreshBluetoothDevices()
            }.onFailure { error ->
                mutableSettingsState.update {
                    it.copy(message = "写入 ESP32 编号失败：${error.message ?: "未知错误"}")
                }
                if (mutableUiState.value.connectionState == ConnectionState.Connected) {
                    startCommandHeartbeat()
                }
            }
        }
    }

    fun setGitHubToken(token: String) {
        preferences.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
        mutableSettingsState.update { it.copy(githubToken = token.trim()) }
        mutableUpdateState.update {
            it.copy(message = if (token.isBlank()) "GitHub Token 已清空" else "GitHub Token 已保存")
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            mutableUpdateState.update {
                it.copy(
                    checking = true,
                    message = "正在检查 GitHub Release",
                    progressText = "",
                )
            }

            runCatching {
                withContext(Dispatchers.IO) { releaseClient.fetchLatestRelease() }
            }.onSuccess { release ->
                latestRelease = release
                val apkAsset = release.apkAsset
                val firmwareAsset = release.firmwareAsset
                val updateAvailable = isReleaseNewerThanInstalled(release.tagName)
                mutableUpdateState.update {
                    it.copy(
                        checking = false,
                        latestVersionName = release.tagName,
                        appUpdateAvailable = updateAvailable && apkAsset != null,
                        appAssetName = apkAsset?.name,
                        firmwareAssetName = firmwareAsset?.name,
                        appDownloadUrl = apkAsset?.downloadUrl,
                        firmwareDownloadUrl = firmwareAsset?.downloadUrl,
                        appAssetApiUrl = apkAsset?.apiUrl,
                        firmwareAssetApiUrl = firmwareAsset?.apiUrl,
                        message = when {
                            apkAsset == null && firmwareAsset == null -> "Release ${release.tagName} 没有 APK 或 ESP32 固件资产"
                            updateAvailable -> "发现新版本 ${release.tagName}"
                            else -> "当前 App 已是最新版本，Release ${release.tagName} 可用于 ESP32 固件"
                        },
                    )
                }
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(
                        checking = false,
                        message = "检查更新失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun installLatestAppUpdate() {
        viewModelScope.launch {
            val state = mutableUpdateState.value
            val url = state.appAssetApiUrl ?: state.appDownloadUrl
            val assetName = state.appAssetName
            if (url == null || assetName == null) {
                mutableUpdateState.update { it.copy(message = "没有可安装的 APK 更新") }
                return@launch
            }

            mutableUpdateState.update {
                it.copy(
                    downloading = true,
                    message = "正在下载 App 更新：$assetName",
                    progressText = "",
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    val apkFile = updateCacheFile(assetName)
                    releaseClient.download(
                        url = url,
                        destination = apkFile,
                        apiAssetDownload = state.appAssetApiUrl != null,
                        onProgress = { readBytes, totalBytes ->
                            mutableUpdateState.update {
                                it.copy(progressText = formatProgress(readBytes, totalBytes))
                            }
                        },
                    )
                    apkFile
                }
            }.onSuccess { apkFile ->
                downloadedApk = apkFile
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        message = "App 更新已下载，正在打开系统安装器",
                        progressText = "",
                    )
                }
                appUpdateInstaller.install(apkFile)
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        message = "下载 App 更新失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun downloadAndUploadLatestEsp32Firmware() {
        viewModelScope.launch {
            val state = mutableUpdateState.value
            val url = state.firmwareAssetApiUrl ?: state.firmwareDownloadUrl
            val assetName = state.firmwareAssetName
            if (url == null || assetName == null) {
                mutableUpdateState.update { it.copy(message = "没有可用的 ESP32 固件资产") }
                return@launch
            }

            mutableUpdateState.update {
                it.copy(
                    downloading = true,
                    message = "正在下载 ESP32 固件：$assetName",
                    progressText = "",
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    val firmwareFile = updateCacheFile(assetName)
                    releaseClient.download(
                        url = url,
                        destination = firmwareFile,
                        apiAssetDownload = state.firmwareAssetApiUrl != null,
                        onProgress = { readBytes, totalBytes ->
                            mutableUpdateState.update {
                                it.copy(progressText = formatProgress(readBytes, totalBytes))
                            }
                        },
                    )
                    firmwareFile.readBytes()
                }
            }.onSuccess { firmwareBytes ->
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        progressText = "",
                    )
                }
                uploadEsp32FirmwareBytes(firmwareBytes, "GitHub 固件")
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        message = "下载 ESP32 固件失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun uploadLocalEsp32Firmware(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: error("无法读取选择的固件文件")
                }
            }.onSuccess { firmwareBytes ->
                uploadEsp32FirmwareBytes(firmwareBytes, "本地固件")
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(message = "读取本地固件失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun sendCurrentCommand() {
        viewModelScope.launch {
            sendCommand(buildCurrentCommand())
        }
    }

    private fun sendIdle() {
        viewModelScope.launch {
            sendCommand(ControlCommand.Idle)
        }
    }

    private fun uploadEsp32FirmwareBytes(firmwareBytes: ByteArray, sourceLabel: String) {
        viewModelScope.launch {
            val nextTransport = transport
            if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
                mutableUpdateState.update { it.copy(message = "请先连接 ESP32 再更新固件") }
                return@launch
            }

            if (firmwareBytes.isEmpty()) {
                mutableUpdateState.update { it.copy(message = "固件文件为空") }
                return@launch
            }

            commandHeartbeatJob?.cancel()
            mutableUiState.update {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    selectedGear = ThrottleGear.Neutral,
                    statusMessage = "准备更新 ESP32 固件，推进输出回空挡",
                )
            }

            runCatching { nextTransport.send(ControlCommand.Idle) }

            val md5Hex = md5Hex(firmwareBytes)
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = true,
                    message = "正在通过蓝牙发送 $sourceLabel 到 ESP32",
                    progressText = "0/${firmwareBytes.size} bytes",
                )
            }

            runCatching {
                nextTransport.uploadFirmware(firmwareBytes, md5Hex) { sentBytes, totalBytes ->
                    mutableUpdateState.update {
                        it.copy(progressText = "$sentBytes/$totalBytes bytes")
                    }
                }
            }.onSuccess {
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "ESP32 固件已发送完成，等待设备校验并重启",
                        progressText = "",
                    )
                }
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "ESP32 固件更新失败：${error.message ?: "未知错误"}",
                    )
                }
            }

            startCommandHeartbeat()
        }
    }

    private fun buildCurrentCommand(): ControlCommand {
        val state = mutableUiState.value
        val settings = mutableSettingsState.value
        return if (state.canSendThrottle) {
            ControlCommand(
                leftThrottlePercent = applyEscDirection(
                    percent = coerceSignedThrottle(state.leftThrottlePercent),
                    reversed = settings.leftEscReversed,
                ),
                rightThrottlePercent = applyEscDirection(
                    percent = coerceSignedThrottle(state.rightThrottlePercent),
                    reversed = settings.rightEscReversed,
                ),
                armed = true,
            )
        } else {
            ControlCommand.Idle
        }
    }

    private fun applyEscDirection(percent: Int, reversed: Boolean): Int {
        return if (reversed) -percent else percent
    }

    private fun coerceSignedThrottle(percent: Int): Int {
        val limit = mutableSettingsState.value.maxThrottlePercent
        return percent.coerceIn(-limit, limit)
    }

    private fun Int.signedPercentText(): String {
        return if (this > 0) "+$this%" else "$this%"
    }

    private suspend fun sendCommand(command: ControlCommand): Boolean {
        return runCatching {
            transport?.send(command)
        }.onFailure { error ->
            handleTransportError(error)
        }.isSuccess
    }

    private fun startCommandHeartbeat() {
        commandHeartbeatJob?.cancel()
        commandHeartbeatJob = viewModelScope.launch {
            while (true) {
                if (!sendCommand(buildCurrentCommand())) {
                    return@launch
                }
                delay(COMMAND_HEARTBEAT_MS)
            }
        }
    }

    private fun handleTransportError(error: Throwable) {
        transport = null
        telemetryJob?.cancel()
        commandHeartbeatJob?.cancel()
        mutableUiState.update {
            it.copy(
                connectionState = ConnectionState.Disconnected,
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                selectedGear = ThrottleGear.Neutral,
                statusMessage = "蓝牙链路异常：${error.message ?: "未知错误"}",
            )
        }
        mutableSettingsState.update {
            it.copy(message = "蓝牙链路异常：${error.message ?: "未知错误"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDiscoveredDevice(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?: device.name.orEmpty()
        Log.d(TAG, "scan result name=${name.ifBlank { "<blank>" }} address=${device.address}")
        if (!name.startsWith(SMART_SUP_DEVICE_PREFIX)) {
            return
        }

        val deviceInfo = BluetoothDeviceInfo(
            name = name.ifBlank { "SmartSUP 设备" },
            address = device.address,
        )
        Log.i(TAG, "discovered ${deviceInfo.name} ${deviceInfo.address}")
        mutableSettingsState.update { state ->
            if (state.discoveredDevices.any { it.address == deviceInfo.address }) {
                state
            } else {
                state.copy(
                    discoveredDevices = (state.discoveredDevices + deviceInfo)
                        .sortedWith(compareBy<BluetoothDeviceInfo> { it.name }.thenBy { it.address }),
                    message = "发现 ${deviceInfo.name}",
                )
            }
        }
    }

    private fun registerDiscoveryReceiver(context: Context) {
        if (discoveryReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(discoveryReceiver, filter)
        }
        discoveryReceiverRegistered = true
    }

    private fun stopBluetoothDiscovery() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        runCatching {
            @SuppressLint("MissingPermission")
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        }
        mutableSettingsState.update { it.copy(discovering = false) }
    }

    private fun collectTelemetry(nextTransport: ControlTransport) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            nextTransport.telemetry.collect { telemetry ->
                mutableUiState.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    private fun saveBluetoothDevice(deviceInfo: BluetoothDeviceInfo) {
        preferences.edit()
            .putString(KEY_DEVICE_NAME, deviceInfo.name)
            .putString(KEY_DEVICE_ADDRESS, deviceInfo.address)
            .apply()
        mutableSettingsState.update { it.copy(savedDevice = deviceInfo) }
    }

    private fun loadSettingsState(): SettingsUiState {
        val savedAddress = preferences.getString(KEY_DEVICE_ADDRESS, null)
        val savedName = preferences.getString(KEY_DEVICE_NAME, null)
        return SettingsUiState(
            bluetoothAvailable = BluetoothClassicTransport.isBluetoothAvailable(),
            bluetoothEnabled = BluetoothClassicTransport.isBluetoothEnabled(),
            bluetoothPermissionGranted = BluetoothClassicTransport.hasBluetoothConnectPermission(getApplication()) &&
                BluetoothClassicTransport.hasBluetoothScanPermission(getApplication()),
            savedDevice = if (!savedAddress.isNullOrBlank()) {
                BluetoothDeviceInfo(savedName.orEmpty().ifBlank { "已保存设备" }, savedAddress)
            } else {
                null
            },
            autoReconnect = preferences.getBoolean(KEY_AUTO_RECONNECT, true),
            maxThrottlePercent = preferences.getInt(KEY_MAX_THROTTLE, 30).coerceIn(5, 100),
            gearPercents = loadGearPercents(),
            leftEscReversed = preferences.getBoolean(KEY_LEFT_ESC_REVERSED, false),
            rightEscReversed = preferences.getBoolean(KEY_RIGHT_ESC_REVERSED, false),
            rampLimitEnabled = preferences.getBoolean(KEY_RAMP_LIMIT, true),
            githubToken = preferences.getString(KEY_GITHUB_TOKEN, null).orEmpty(),
        )
    }

    private fun loadGearPercents(): Map<ThrottleGear, Int> {
        return ThrottleGear.entries.associateWith { gear ->
            coerceGearPercent(
                gear = gear,
                percent = preferences.getInt(gearPreferenceKey(gear), gear.defaultThrottlePercent),
            )
        }
    }

    override fun onCleared() {
        stopBluetoothDiscovery()
        if (discoveryReceiverRegistered) {
            runCatching {
                getApplication<Application>().unregisterReceiver(discoveryReceiver)
            }
            discoveryReceiverRegistered = false
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "SmartSupControl"
        private const val SMART_SUP_DEVICE_PREFIX = "SmartSUP-"
        private const val PREFERENCES_NAME = "smart_sup_settings"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_MAX_THROTTLE = "max_throttle"
        private const val KEY_RAMP_LIMIT = "ramp_limit"
        private const val KEY_LEFT_ESC_REVERSED = "left_esc_reversed"
        private const val KEY_RIGHT_ESC_REVERSED = "right_esc_reversed"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GEAR_PREFIX = "gear_percent_"
        private const val COMMAND_HEARTBEAT_MS = 250L
    }

    private fun lockForDirectionChange(message: String) {
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                selectedGear = ThrottleGear.Neutral,
                statusMessage = "$message，已锁定并回空挡",
            )
        }
        sendIdle()
    }

    private fun gearPercent(gear: ThrottleGear): Int {
        return mutableSettingsState.value.gearPercents[gear] ?: gear.defaultThrottlePercent
    }

    private fun coerceGearPercent(gear: ThrottleGear, percent: Int): Int {
        return when {
            gear.isReverse -> percent.coerceIn(-100, -5)
            gear.isForward -> percent.coerceIn(5, 100)
            else -> 0
        }
    }

    private fun gearPreferenceKey(gear: ThrottleGear): String {
        return KEY_GEAR_PREFIX + gear.preferenceKey
    }

    private fun updateCacheFile(assetName: String): File {
        val safeName = assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(getApplication<Application>().cacheDir, "updates/$safeName")
    }

    private fun formatProgress(readBytes: Long, totalBytes: Long): String {
        return if (totalBytes > 0) {
            "$readBytes/$totalBytes bytes (${readBytes * 100 / totalBytes}%)"
        } else {
            "$readBytes bytes"
        }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isReleaseNewerThanInstalled(tagName: String): Boolean {
        val latest = parseVersion(tagName)
        val current = parseVersion(BuildConfig.VERSION_NAME)
        if (latest.isEmpty() || current.isEmpty()) {
            return tagName != BuildConfig.VERSION_NAME
        }
        val maxSize = maxOf(latest.size, current.size)
        for (index in 0 until maxSize) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) {
                return latestPart > currentPart
            }
        }
        return false
    }

    private fun parseVersion(value: String): List<Int> {
        return value
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
    }
}
