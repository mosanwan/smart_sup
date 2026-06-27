package com.smartsup.controller.control

import android.Manifest
import android.app.Application
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.AutoNavigationUiState
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.CommandSource
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.ControlCommandMode
import com.smartsup.controller.model.ControlLogEntry
import com.smartsup.controller.model.ControlLogLevel
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.FirmwareReleaseManifest
import com.smartsup.controller.model.GpsTrackPoint
import com.smartsup.controller.model.NavigationRoute
import com.smartsup.controller.model.NavigationRoutePoint
import com.smartsup.controller.model.NavigationGpsSource
import com.smartsup.controller.model.ReleaseInfo
import com.smartsup.controller.model.RealtimeVoiceMode
import com.smartsup.controller.model.RealtimeTtsMode
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.Telemetry
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.TrackLogEvent
import com.smartsup.controller.model.TurnDirection
import com.smartsup.controller.model.UpdateUiState
import com.smartsup.controller.model.VoiceAsrState
import com.smartsup.controller.model.ybImuHeadingDegrees
import com.smartsup.controller.model.ybImuControllerHeadingDegrees
import com.smartsup.controller.transport.BluetoothClassicTransport
import com.smartsup.controller.transport.ControlTransport
import com.smartsup.controller.update.AppUpdateInstaller
import com.smartsup.controller.update.GitHubReleaseClient
import com.smartsup.controller.voice.VoiceCommandEvaluation
import com.smartsup.controller.voice.VoiceCommandAction
import com.smartsup.controller.voice.VoiceCommandParser
import com.smartsup.controller.voice.VoiceParseResult
import com.smartsup.controller.voice.RealtimeVoiceControlEvent
import com.smartsup.controller.voice.VoiceSampleMetadata
import com.smartsup.controller.voice.VoiceSampleStore
import com.smartsup.controller.voice.VoiceSampleTarget
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ControlViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var transport: ControlTransport? = null
    private var telemetryJob: Job? = null
    private var trackLogJob: Job? = null
    private var trackLogSyncJob: Job? = null
    private val trackLogBatchPoints = mutableListOf<GpsTrackPoint>()
    private var commandHeartbeatJob: Job? = null
    private var bluetoothConnectJob: Job? = null
    private var otaReconnectJob: Job? = null
    private var realtimeVoiceActionTimeoutJob: Job? = null
    private var realtimeVoiceActionToken = 0
    private var autoReconnectAttempted = false
    private var discoveryReceiverRegistered = false
    private var lastHandledControllerErrorLine: String? = null
    private var lastHandledControllerFaultLine: String? = null
    private var nextControlLogId = 1L
    private var lastLoggedSentCommand: String? = null
    private var lastLoggedReceivedLine: String? = null
    private var lastLoggedArmedState: Boolean? = null
    private var lastLoggedHeadingLockState: String? = null
    private var lastLoggedHeadingSource: String? = null
    private var lastLoggedHeadingLockWarning: String? = null
    private var lastHeadingLockSampleLogMs = 0L
    private var lastHeadingLockSample: HeadingLockSample? = null
    private var lastUnrequestedControllerLockLogMs = 0L
    private var lastUnrequestedControllerUnlockLogMs = 0L
    private var lastUnrequestedHeadingLockOffLogMs = 0L
    private var pendingControllerArmedRequest: Boolean? = null
    private var pendingControllerArmedRequestUntilMs = 0L
    private val releaseClient = GitHubReleaseClient(BuildConfig.GITHUB_REPOSITORY) {
        preferences.getString(KEY_GITHUB_TOKEN, null)
    }
    private val gpsTrackStore = GpsTrackStore(application)
    private val controlLogFileStore by lazy { ControlLogFileStore(application) }
    private val imuTelemetryLogStore by lazy { ImuTelemetryLogStore(application) }
    private val stationKeepingLogStore by lazy { StationKeepingLogStore(application) }
    private val autoNavigationRouteStore = AutoNavigationRouteStore(application)
    private val appUpdateInstaller = AppUpdateInstaller(application)
    private val sensorManager = application.getSystemService(SensorManager::class.java)
    private val locationManager = application.getSystemService(LocationManager::class.java)
    private val phoneHeadingRotation = FloatArray(9)
    private val phoneHeadingOrientation = FloatArray(3)
    private var phoneHeadingSensorRegistered = false
    private var phoneHeadingLastUpdateMs = 0L
    private var phoneGpsRegistered = false
    private var phoneGpsLastUpdateMs = 0L
    private var ybImuHeadingLastUpdateMs = 0L
    private var lastLoggedImuStatusLine: String? = null
    private var latestRelease: ReleaseInfo? = null
    private var latestFirmwareManifest: FirmwareReleaseManifest? = null
    private var esp32OtaExclusive = false
    private var esp32OtaAccepted = false
    private var otaReconnectAttempt = 0
    private var otaReconnectSawDisconnect = false
    private var pendingEsp32FirmwareVersion: String? = null
    private var downloadedApk: File? = null
    private var lastBluetoothDisconnectMessage: String? = null
    private var activeTurnCommand: ControlCommand? = null
    private var activeHeadingLockCommand: ControlCommand? = null
    private var activeHeadingLockStartPending = false
    private var activeHeadingLockOwnedByAutoNavigation = false
    private var appHeadingControlTargetDegrees: Float? = null
    private var appHeadingControlStartedAtMs = 0L
    private var autoNavigationFilteredPoint: NavigationRoutePoint? = null
    private var autoNavigationLastRawPoint: NavigationRoutePoint? = null
    private var autoNavigationGpsInvalidSinceMs = 0L
    private var autoNavigationHeadingInvalidSinceMs = 0L
    private var stationKeepingFilteredPoint: NavigationRoutePoint? = null
    private var stationKeepingLastRawPoint: NavigationRoutePoint? = null
    private var voiceTurnRequestCounter = 0
    private var headingLockRequestCounter = 0
    private var voiceSampleTargetIndex = 0
    private var pendingVoiceSample: PendingVoiceSample? = null
    private var voiceReplyEngine: TextToSpeech? = null
    private var voiceReplyReady = false
    private var ignoreVoiceRecognitionUntilMs = 0L
    private var lastAcceptedVoiceCommandKey: String? = null
    private var lastAcceptedVoiceCommandAtMs = 0L
    private val pendingVoiceReplies = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val phoneHeadingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (
                event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            ) {
                return
            }
            SensorManager.getRotationMatrixFromVector(phoneHeadingRotation, event.values)
            SensorManager.getOrientation(phoneHeadingRotation, phoneHeadingOrientation)
            val headingDegrees = normalizeCompassDegrees(
                Math.toDegrees(phoneHeadingOrientation[0].toDouble()).toFloat(),
            )
            phoneHeadingLastUpdateMs = System.currentTimeMillis()
            mutableUiState.update {
                it.copy(
                    phoneHeadingDegrees = headingDegrees,
                    phoneHeadingAvailable = true,
                    phoneHeadingSensorName = event.sensor.name,
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val phoneGpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handlePhoneGpsLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            updatePhoneGpsRegistration()
        }

        override fun onProviderDisabled(provider: String) {
            mutableUiState.update {
                it.copy(
                    phoneGps = it.phoneGps.copy(
                        message = "手机定位源不可用：$provider 已关闭",
                    ),
                )
            }
        }

        @Deprecated("Deprecated in Android SDK")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val voiceSampleTargets = listOf(
        VoiceSampleTarget("开始声控", "开始声控", "本地状态：恢复执行语音控制命令"),
        VoiceSampleTarget("停止声控", "停止声控", "本地状态：关闭语音控制，不改变推进或锁航"),
        VoiceSampleTarget("停止", "停止", "SRC=VOICE;ARM=1;L=0;R=0"),
        VoiceSampleTarget("快点", "快点", "当前目标按微调步进加速"),
        VoiceSampleTarget("慢点", "慢点", "当前目标按微调步进减速"),
        VoiceSampleTarget("空档", "空档", "SRC=VOICE;ARM=1;L=0;R=0"),
        VoiceSampleTarget("前进一档", "前进一档", "SRC=VOICE;ARM=1;L=20;R=20"),
        VoiceSampleTarget("前进二档", "前进二档", "SRC=VOICE;ARM=1;L=30;R=30"),
        VoiceSampleTarget("前进三档", "前进三档", "SRC=VOICE;ARM=1;L=60;R=60"),
        VoiceSampleTarget("前进四档", "前进四档", "SRC=VOICE;ARM=1;L=80;R=80"),
        VoiceSampleTarget("后退一档", "后退一档", "SRC=VOICE;ARM=1;L=-15;R=-15"),
        VoiceSampleTarget("左转", "左转", "SRC=VOICE;ARM=1;MODE=TURN;DIR=LEFT;ANGLE=15"),
        VoiceSampleTarget("右转", "右转", "SRC=VOICE;ARM=1;MODE=TURN;DIR=RIGHT;ANGLE=15"),
        VoiceSampleTarget("左转 15 度", "左转十五度", "主控角度转向：MODE=TURN;DIR=LEFT;ANGLE=15"),
        VoiceSampleTarget("左转 30 度", "左转三十度", "主控角度转向：MODE=TURN;DIR=LEFT;ANGLE=30"),
        VoiceSampleTarget("左转 60 度", "左转六十度", "主控角度转向：MODE=TURN;DIR=LEFT;ANGLE=60"),
        VoiceSampleTarget("右转 15 度", "右转十五度", "主控角度转向：MODE=TURN;DIR=RIGHT;ANGLE=15"),
        VoiceSampleTarget("右转 30 度", "右转三十度", "主控角度转向：MODE=TURN;DIR=RIGHT;ANGLE=30"),
        VoiceSampleTarget("右转 60 度", "右转六十度", "主控角度转向：MODE=TURN;DIR=RIGHT;ANGLE=60"),
        VoiceSampleTarget("保持航向", "保持航向", "固件航向锁定，启动后发送 KEEPALIVE 保活"),
        VoiceSampleTarget("锁定当前航向", "锁定当前航向", "固件航向锁定，启动后发送 KEEPALIVE 保活"),
        VoiceSampleTarget("取消航向锁定", "取消航向锁定", "退出航向锁定"),
    )

    private data class PendingVoiceSample(
        val target: VoiceSampleTarget,
        val samples: FloatArray,
        val asrText: String,
        val parsedCommand: String,
        val accepted: Boolean,
    )

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
                                "未发现 ${it.deviceNamePrefix} 设备，请确认主控已上电且蓝牙可见"
                            } else {
                                "扫描完成，发现 ${it.discoveredDevices.size} 个 SmartSUP 设备"
                            },
                        )
                    }
                }
            }
        }
    }

    private val mutableUiState = MutableStateFlow(
        ControlUiState(
            realtimeWakeWordRequired = preferences.getBoolean(KEY_REALTIME_WAKE_WORD_REQUIRED, false),
        ),
    )
    val uiState: StateFlow<ControlUiState> = mutableUiState.asStateFlow()

    private val mutableSettingsState = MutableStateFlow(loadSettingsState())
    val settingsState: StateFlow<SettingsUiState> = mutableSettingsState.asStateFlow()

    private val mutableUpdateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = mutableUpdateState.asStateFlow()

    init {
        refreshGpsTrackState("已加载手机本地轨迹")
        refreshAutoNavigationRoutes("已加载本地路线")
        val savedVoiceSampleCount = VoiceSampleStore.countSavedSamples(application)
        mutableUiState.update {
            it.copy(
                voiceSampleTargetLabel = voiceSampleTargets.first().label,
                voiceSampleTargetText = voiceSampleTargets.first().spokenText,
                voiceSampleExpectedCommand = voiceSampleTargets.first().expectedCommand,
                voiceSampleDirectory = VoiceSampleStore.samplesDir(application).absolutePath,
                voiceSampleSavedCount = savedVoiceSampleCount,
                controlLogFilePath = controlLogFileStore.filePath,
            )
        }
        initializeVoiceReplyEngine()
        updatePhoneHeadingSensorRegistration()
        updatePhoneGpsRegistration()
        refreshBluetoothDevices()
        checkForUpdates()
    }

    fun connect() {
        connectSavedBluetooth()
    }

    fun clearControlLog() {
        mutableUiState.update { it.copy(controlLog = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            controlLogFileStore.clear()
        }
        lastLoggedSentCommand = null
        lastLoggedReceivedLine = null
        lastLoggedArmedState = null
        lastLoggedHeadingLockState = null
        lastLoggedHeadingSource = null
        lastLoggedHeadingLockWarning = null
        lastHeadingLockSampleLogMs = 0L
        lastHeadingLockSample = null
    }

    fun connectSavedBluetooth() {
        val savedDevice = mutableSettingsState.value.savedDevice
        if (savedDevice == null) {
            mutableSettingsState.update {
                it.copy(message = "还没有保存主控蓝牙设备，请先从已配对设备中选择")
            }
            return
        }
        connectBluetooth(savedDevice)
    }

    fun connectBluetooth(deviceInfo: BluetoothDeviceInfo) {
        saveBluetoothDevice(deviceInfo)
        bluetoothConnectJob?.cancel()
        bluetoothConnectJob = viewModelScope.launch {
            stopBluetoothDiscovery()
            clearPendingControllerArmedRequest()
            val otaReconnectPending = esp32OtaExclusive && esp32OtaAccepted
            val pendingVersionAtStart = pendingEsp32FirmwareVersion
            mutableUiState.update {
                it.copy(
                    connectionState = ConnectionState.Connecting,
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "正在连接 ${deviceInfo.name}",
                )
            }
            mutableSettingsState.update { it.copy(message = "正在连接 ${deviceInfo.name}") }
            if (otaReconnectPending) {
                mutableUpdateState.update {
                    it.copy(
                        message = pendingVersionAtStart?.let { target ->
                            "正在自动重连 ${deviceInfo.name}，验证 FW=$target"
                        } ?: "正在自动重连 ${deviceInfo.name}，确认固件版本",
                    )
                }
            }

            val nextTransport = BluetoothClassicTransport(getApplication(), deviceInfo)
            runCatching {
                nextTransport.connect()
            }.onSuccess {
                transport = nextTransport
                clearAutonomousCommands()
                collectTelemetry(nextTransport)
                collectTrackLogEvents(nextTransport)
                val pendingVersion = pendingEsp32FirmwareVersion
                mutableUiState.update {
                    val reconnectNote = lastBluetoothDisconnectMessage
                        ?.let { message -> "；上次断开：$message" }
                        .orEmpty()
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        statusMessage = if (pendingVersion == null) {
                            "已连接 ${deviceInfo.name}，仍处于锁定状态$reconnectNote"
                        } else {
                            "已连接 ${deviceInfo.name}，正在验证主控固件 $pendingVersion"
                        },
                    )
                }
                mutableSettingsState.update {
                    it.copy(
                        message = if (pendingVersion == null) {
                            "已保存并连接 ${deviceInfo.name}"
                        } else {
                            "已重连 ${deviceInfo.name}，正在验证主控固件版本"
                        },
                    )
                }
                if (otaReconnectPending) {
                    mutableUpdateState.update {
                        it.copy(
                            message = pendingVersion?.let { target ->
                                "已重连 ${deviceInfo.name}，正在读取主控固件版本以验证 FW=$target"
                            } ?: "已重连 ${deviceInfo.name}，正在读取主控固件版本",
                        )
                    }
                    mutableUpdateState.value.currentEsp32FirmwareVersion
                        ?.takeIf { otaReconnectSawDisconnect }
                        ?.let { firmwareVersion ->
                        handlePendingOtaFirmwareVerification(
                            firmwareVersion = firmwareVersion,
                            allowMismatch = false,
                        )
                    }
                }
                runCatching { nextTransport.sendRawLine("INFO?") }
                if (pendingVersion == null) {
                    esp32OtaExclusive = false
                    startCommandHeartbeat()
                    sendIdle()
                    requestTrackLogSync()
                    speakVoiceReply("主控已连接")
                }
            }.onFailure { error ->
                transport = null
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.App,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        statusMessage = if (otaReconnectPending) {
                            "主控 OTA 后重连失败，3 秒后自动重试"
                        } else {
                            "连接失败：${error.message ?: "未知错误"}"
                        },
                    )
                }
                mutableSettingsState.update {
                    it.copy(
                        message = if (otaReconnectPending) {
                            "主控 OTA 后重连失败：${error.message ?: "未知错误"}；稍后自动重试"
                        } else {
                            "连接失败：${error.message ?: "未知错误"}"
                        },
                    )
                }
                if (otaReconnectPending) {
                    mutableUpdateState.update {
                        it.copy(
                            message = "主控 OTA 后自动重连 ${deviceInfo.name} 失败：${error.message ?: "未知错误"}；3 秒后重试",
                        )
                    }
                }
            }
        }
    }

    fun disconnect() {
        disconnectBluetooth()
    }

    fun disconnectBluetooth() {
        viewModelScope.launch {
            stopOtaReconnectLoop()
            runCatching { transport?.disconnect() }
            transport = null
            bluetoothConnectJob?.cancel()
            telemetryJob?.cancel()
            trackLogJob?.cancel()
            trackLogSyncJob?.cancel()
            commandHeartbeatJob?.cancel()
            esp32OtaExclusive = false
            esp32OtaAccepted = false
            pendingEsp32FirmwareVersion = null
            clearPendingControllerArmedRequest()
            clearAutonomousCommands()
            val existingLog = mutableUiState.value.controlLog
            val controlLogFilePath = mutableUiState.value.controlLogFilePath
            mutableUiState.value = ControlUiState(
                realtimeWakeWordRequired = preferences.getBoolean(KEY_REALTIME_WAKE_WORD_REQUIRED, false),
                controlLog = existingLog,
                controlLogFilePath = controlLogFilePath,
                statusMessage = "已断开，推进输出保持空闲",
            )
            appendControlLog(
                level = ControlLogLevel.Info,
                title = "蓝牙断开",
                message = "用户断开主控连接，推进输出保持空闲",
            )
            mutableSettingsState.update { it.copy(message = "已断开主控连接") }
        }
    }

    fun setArmed(armed: Boolean) {
        val connected = mutableUiState.value.connectionState == ConnectionState.Connected
        if (armed && !connected) {
            mutableUiState.update { it.copy(statusMessage = "解锁拒绝：未连接主控") }
            speakVoiceReply(replyWithDetail("未连接，不能解锁"))
            return
        }

        clearAutonomousCommands()
        pendingControllerArmedRequest = armed
        pendingControllerArmedRequestUntilMs = System.currentTimeMillis() + CONTROLLER_ARM_CONFIRM_TIMEOUT_MS
        appendControlLog(
            level = ControlLogLevel.Info,
            title = if (armed) "请求解锁" else "请求锁定",
            message = if (armed) {
                "已发送空挡解锁请求，等待主控回传 ARMED=1"
            } else {
                "已发送锁定请求，等待主控回传 ARMED=0"
            },
        )
        mutableUiState.update {
            if (armed) {
                it.copy(
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    statusMessage = "正在请求主控解锁，等待 ARMED=1 回传",
                )
            } else {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "正在请求主控锁定，油门回空挡，等待 ARMED=0 回传",
                )
            }
        }
        sendCurrentCommand()
    }

    private fun rejectManualThrottleWhenLocked(action: String): Boolean {
        val state = mutableUiState.value
        val message = when {
            state.connectionState != ConnectionState.Connected -> "$action 拒绝：请先连接主控"
            !state.armed -> "$action 拒绝：请先手动解锁"
            else -> return false
        }
        mutableUiState.update { it.copy(statusMessage = message) }
        return true
    }

    fun setLeftThrottle(percent: Int) {
        if (rejectManualThrottleWhenLocked("左推进")) {
            return
        }
        val state = mutableUiState.value
        val constrained = coerceSignedThrottle(percent)
        val lockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = constrained,
            rightPercent = state.rightThrottlePercent,
            source = CommandSource.App,
        )
        if (!lockKept) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = constrained,
                commandSource = CommandSource.App,
                headingLockEnabled = if (lockKept) true else false,
            )
        }
        sendCurrentCommand()
    }

    fun setRightThrottle(percent: Int) {
        if (rejectManualThrottleWhenLocked("右推进")) {
            return
        }
        val state = mutableUiState.value
        val constrained = coerceSignedThrottle(percent)
        val lockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = state.leftThrottlePercent,
            rightPercent = constrained,
            source = CommandSource.App,
        )
        if (!lockKept) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                rightThrottlePercent = constrained,
                commandSource = CommandSource.App,
                headingLockEnabled = if (lockKept) true else false,
            )
        }
        sendCurrentCommand()
    }

    fun returnLeftThrottleToGear() {
        if (rejectManualThrottleWhenLocked("左推进回档")) {
            return
        }
        val state = mutableUiState.value
        val gearThrottle = coerceSignedThrottle(
            selectedGearThrottle(state.selectedGear, state.throttleTrimPercent),
        )
        val lockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = gearThrottle,
            rightPercent = state.rightThrottlePercent,
            source = CommandSource.App,
        )
        if (!lockKept) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = gearThrottle,
                commandSource = CommandSource.App,
                headingLockEnabled = if (lockKept) true else false,
            )
        }
        sendCurrentCommand()
    }

    fun returnRightThrottleToGear() {
        if (rejectManualThrottleWhenLocked("右推进回档")) {
            return
        }
        val state = mutableUiState.value
        val gearThrottle = coerceSignedThrottle(
            selectedGearThrottle(state.selectedGear, state.throttleTrimPercent),
        )
        val lockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = state.leftThrottlePercent,
            rightPercent = gearThrottle,
            source = CommandSource.App,
        )
        if (!lockKept) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                rightThrottlePercent = gearThrottle,
                commandSource = CommandSource.App,
                headingLockEnabled = if (lockKept) true else false,
            )
        }
        sendCurrentCommand()
    }

    fun setThrottleGear(gear: ThrottleGear) {
        if (rejectManualThrottleWhenLocked("档位切换")) {
            return
        }
        val gearThrottle = coerceSignedThrottle(gearPercent(gear))
        val updatedHeadingLockBase = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = gearThrottle,
            rightPercent = gearThrottle,
            source = CommandSource.App,
        )
        if (!updatedHeadingLockBase) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                selectedGear = gear,
                leftThrottlePercent = gearThrottle,
                rightThrottlePercent = gearThrottle,
                commandSource = CommandSource.App,
                headingLockEnabled = if (updatedHeadingLockBase) true else it.headingLockEnabled,
                throttleTrimPercent = 0,
                statusMessage = if (updatedHeadingLockBase) {
                    "航向锁定保持，基础档位：${gear.label} ${gearThrottle.signedPercentText()}"
                } else {
                    "当前档位：${gear.label} ${gearThrottle.signedPercentText()}"
                },
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
            val updatedHeadingLockBase = updateActiveHeadingLockBaseFromThrottle(
                leftPercent = gearThrottle,
                rightPercent = gearThrottle,
                source = CommandSource.App,
            )
            if (!updatedHeadingLockBase) {
                clearAutonomousCommands()
            }
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = gearThrottle,
                    rightThrottlePercent = gearThrottle,
                    commandSource = CommandSource.App,
                    headingLockEnabled = if (updatedHeadingLockBase) true else it.headingLockEnabled,
                    throttleTrimPercent = 0,
                    statusMessage = if (updatedHeadingLockBase) {
                        "航向锁定保持，基础档位：${gear.label} ${gearThrottle.signedPercentText()}"
                    } else {
                        "当前档位：${gear.label} ${gearThrottle.signedPercentText()}"
                    },
                )
            }
            sendCurrentCommand()
        }
    }

    fun emergencyStop() {
        clearAutonomousCommands()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        appendControlLog(
            level = ControlLogLevel.Error,
            title = "急停",
            message = "用户触发急停，App 下发空闲命令",
        )
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
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
        updatePhoneGpsRegistration()

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
                    else -> "点击扫描，发现名称以 $SMART_SUP_DEVICE_PREFIX 开头的主控"
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
        clearAutonomousCommands()
        val constrained = percent.coerceIn(5, 100)
        preferences.edit().putInt(KEY_MAX_THROTTLE, constrained).apply()
        mutableSettingsState.update { it.copy(maxThrottlePercent = constrained) }
        mutableUiState.update {
            val leftPercent = it.leftThrottlePercent.coerceIn(-constrained, constrained)
            val rightPercent = it.rightThrottlePercent.coerceIn(-constrained, constrained)
            it.copy(
                leftThrottlePercent = leftPercent,
                rightThrottlePercent = rightPercent,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                throttleTrimPercent = fineTuneTrimFor(it.selectedGear, leftPercent, rightPercent),
            )
        }
        sendCurrentCommand()
    }

    fun setFineTuneStepPercent(percent: Int) {
        val constrained = coerceFineTuneStepPercent(percent)
        preferences.edit().putInt(KEY_FINE_TUNE_STEP_PERCENT, constrained).apply()
        mutableSettingsState.update { it.copy(fineTuneStepPercent = constrained) }
        mutableUiState.update {
            it.copy(statusMessage = "微调步进已设置为 ${constrained}%")
        }
    }

    fun increaseThrottleFineTune() {
        applyThrottleFineTune(increase = true, source = CommandSource.App)
    }

    fun decreaseThrottleFineTune() {
        applyThrottleFineTune(increase = false, source = CommandSource.App)
    }

    fun setVoicePowerLimitPercent(percent: Int) {
        clearAutonomousCommands()
        val constrained = coerceVoicePowerLimitPercent(percent)
        preferences.edit().putInt(KEY_VOICE_POWER_LIMIT, constrained).apply()
        mutableSettingsState.update { it.copy(voicePowerLimitPercent = constrained) }
        mutableUiState.update {
            if (it.commandSource == CommandSource.Voice) {
                it.copy(
                    leftThrottlePercent = it.leftThrottlePercent.coerceIn(-constrained, constrained),
                    rightThrottlePercent = it.rightThrottlePercent.coerceIn(-constrained, constrained),
                    headingLockEnabled = false,
                    statusMessage = "声控功率限制已设置为 ${constrained}%，已取消当前语音自主动作",
                )
            } else {
                it.copy(
                    headingLockEnabled = false,
                    statusMessage = "声控功率限制已设置为 ${constrained}%",
                )
            }
        }
        sendCurrentCommand()
    }

    fun setHeadingLockToleranceDegrees(degrees: Int) {
        val currentFull = mutableSettingsState.value.headingLockFullCorrectionDegrees
        val constrained = coerceHeadingLockToleranceDegrees(degrees, currentFull)
        preferences.edit()
            .putInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, constrained)
            .putBoolean(KEY_HEADING_LOCK_TOLERANCE_2_DEGREES_MIGRATED, true)
            .apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(headingLockToleranceDegrees = constrained) }
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "航向锁定容差已设置为 ${constrained}°，已取消当前航向锁定",
            )
        }
        sendCurrentCommand()
    }

    fun setHeadingLockFullCorrectionDegrees(degrees: Int) {
        val tolerance = mutableSettingsState.value.headingLockToleranceDegrees
        val constrained = coerceHeadingLockFullCorrectionDegrees(degrees, tolerance)
        preferences.edit().putInt(KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES, constrained).apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(headingLockFullCorrectionDegrees = constrained) }
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "最大转向角度已设置为 ${constrained}°，已取消当前航向锁定",
            )
        }
        sendCurrentCommand()
    }

    fun setHeadingLockNeutralPivotMinDifferencePercent(percent: Int) {
        val currentMax = mutableSettingsState.value.headingLockNeutralPivotMaxDifferencePercent
        val constrained = coerceHeadingLockNeutralPivotMinDifferencePercent(percent, currentMax)
        preferences.edit().putInt(KEY_HEADING_LOCK_NEUTRAL_PIVOT_MIN_DIFFERENCE_PERCENT, constrained).apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(headingLockNeutralPivotMinDifferencePercent = constrained) }
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "空档最小转向差已设置为 ${constrained}%，已取消当前航向锁定",
            )
        }
        sendCurrentCommand()
    }

    fun setHeadingLockNeutralPivotMaxDifferencePercent(percent: Int) {
        val currentMin = mutableSettingsState.value.headingLockNeutralPivotMinDifferencePercent
        val constrained = coerceHeadingLockNeutralPivotMaxDifferencePercent(percent, currentMin)
        preferences.edit().putInt(KEY_HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_PERCENT, constrained).apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(headingLockNeutralPivotMaxDifferencePercent = constrained) }
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "空档最大反推已设置为 ${constrained}%，已取消当前航向锁定",
            )
        }
        sendCurrentCommand()
    }

    fun setAutoNavigationGpsJumpResetMeters(meters: Int) {
        val constrained = coerceAutoNavigationGpsJumpResetMeters(meters)
        preferences.edit().putInt(KEY_AUTO_NAVIGATION_GPS_JUMP_RESET_METERS, constrained).apply()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        mutableSettingsState.update { it.copy(autoNavigationGpsJumpResetMeters = constrained) }
        mutableUiState.update {
            it.copy(statusMessage = "自动导航 GPS 跳变重置阈值已设置为 ${constrained}m")
        }
    }

    fun adjustPhoneHeadingOffsetDegrees(deltaDegrees: Float) {
        setPhoneHeadingOffsetDegrees(mutableSettingsState.value.phoneHeadingOffsetDegrees + deltaDegrees)
    }

    fun resetPhoneHeadingOffsetDegrees() {
        setPhoneHeadingOffsetDegrees(0f)
    }

    fun setPhoneHeadingOffsetDegrees(offsetDegrees: Float) {
        val normalized = normalizeSignedCompassOffset(offsetDegrees)
        preferences.edit().putFloat(KEY_PHONE_HEADING_OFFSET_DEGREES, normalized).apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(phoneHeadingOffsetDegrees = normalized) }
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "手机船头偏置已设置为 ${normalized.roundToInt()}°，已取消当前航向/自动控制",
            )
        }
        sendCurrentCommand()
    }

    fun setNavigationGpsSource(source: NavigationGpsSource) {
        preferences.edit().putString(KEY_NAVIGATION_GPS_SOURCE, source.name).apply()
        clearAutonomousCommands()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        mutableSettingsState.update { it.copy(navigationGpsSource = source) }
        updatePhoneGpsRegistration()
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                magneticDeclinationDegrees = magneticDeclinationDegreesForSource(source),
                statusMessage = "导航 GPS 来源已切换为 ${navigationGpsSourceLabel(source)}，已取消当前自动导航",
            )
        }
        sendCurrentCommand()
    }

    fun setUsePhoneHeading(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USE_PHONE_HEADING, false).apply()
        mutableSettingsState.update { it.copy(usePhoneHeading = false) }
        updatePhoneHeadingSensorRegistration()
        mutableUiState.update {
            it.copy(
                statusMessage = if (enabled) {
                    "控制航向来源已统一为主控 IMU；手机指南针只用于显示和调试"
                } else {
                    "控制航向来源保持主控 IMU"
                },
            )
        }
    }

    fun setRealtimeVoiceEndpoint(value: String) {
        val trimmed = value.trim().ifBlank { REALTIME_VOICE_ENDPOINT_DEFAULT }
        preferences.edit().putString(KEY_REALTIME_VOICE_ENDPOINT, trimmed).apply()
        mutableSettingsState.update { it.copy(realtimeVoiceEndpoint = trimmed) }
    }

    fun setRealtimeVoiceAppId(value: String) {
        val trimmed = value.trim()
        preferences.edit().putString(KEY_REALTIME_VOICE_APP_ID, trimmed).apply()
        mutableSettingsState.update { it.copy(realtimeVoiceAppId = trimmed) }
    }

    fun setRealtimeVoiceApiKey(value: String) {
        val trimmed = value.trim()
        preferences.edit().putString(KEY_REALTIME_VOICE_API_KEY, trimmed).apply()
        mutableSettingsState.update {
            it.copy(
                realtimeVoiceApiKey = trimmed,
                cloudTtsConfigured = trimmed.isNotBlank() || BuildConfig.DOUBAO_API_KEY.isNotBlank(),
            )
        }
    }

    fun setRealtimeVoiceModel(value: String) {
        val trimmed = value.trim().ifBlank { REALTIME_VOICE_MODEL_DEFAULT }
        preferences.edit().putString(KEY_REALTIME_VOICE_MODEL, trimmed).apply()
        mutableSettingsState.update { it.copy(realtimeVoiceModel = trimmed) }
    }

    fun setRealtimeVoiceVoice(value: String) {
        val trimmed = normalizeRealtimeVoiceVoice(value.trim().ifBlank { REALTIME_VOICE_VOICE_DEFAULT })
        preferences.edit().putString(KEY_REALTIME_VOICE_VOICE, trimmed).apply()
        mutableSettingsState.update { it.copy(realtimeVoiceVoice = trimmed) }
    }

    fun setRealtimeTtsMode(mode: RealtimeTtsMode) {
        preferences.edit().putString(KEY_REALTIME_TTS_MODE, mode.name).apply()
        mutableSettingsState.update { it.copy(realtimeTtsMode = mode) }
        val settings = mutableSettingsState.value
        mutableUiState.update {
            it.copy(
                statusMessage = if (mode == RealtimeTtsMode.Cloud && !settings.cloudTtsConfigured) {
                    "云端 TTS 需要配置火山引擎 API Key"
                } else {
                    "TTS 已切换为${if (mode == RealtimeTtsMode.Cloud) "云端" else "本地"}"
                },
            )
        }
    }

    fun enableHeadingLock() {
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：未连接主控") }
            return
        }
        if (!state.armed) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：请先手动解锁") }
            return
        }
        val firmwareHeading = currentFirmwareHeadingForLockOrNull()
        if (firmwareHeading == null) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：主控 IMU 航向暂无有效读数，导航页手机航向不能用于固件锁航") }
            return
        }

        clearActiveTurnCommand()
        val basePercent = currentAverageThrottlePercent()
        val command = prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.App,
                mode = ControlCommandMode.HeadingLock,
                headingLockEnabled = true,
                headingLockRequestId = 1,
                headingLockBaseThrottlePercent = basePercent,
            ),
            allocateHeadingLockRequestId = true,
        )
        startAppHeadingLock(command, firmwareHeading)
        appendControlLog(
            level = ControlLogLevel.Info,
            title = "用户操作",
            message = "点击锁航；目标为主控当前航向 ${firmwareHeading.roundToInt()}°，基础油门 ${basePercent.signedPercentText()}",
        )
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                statusMessage = "航向锁定已发送，目标 ${firmwareHeading.roundToInt()}°，基础油门 ${basePercent.signedPercentText()}",
            )
        }
        sendCurrentCommand()
    }

    fun cancelHeadingLock() {
        clearActiveHeadingLockCommand()
        appendControlLog(
            level = ControlLogLevel.Warning,
            title = "用户操作",
            message = "取消航向锁定，回到手动控制",
        )
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "航向锁定已取消，回到手动控制",
            )
        }
        sendCurrentCommand()
    }

    fun setHeadingLockTargetHeading(targetHeadingDegrees: Float) {
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "目标航向拒绝：未连接主控") }
            return
        }
        if (!state.armed) {
            mutableUiState.update { it.copy(statusMessage = "目标航向拒绝：请先手动解锁") }
            return
        }

        val normalizedTarget = normalizeCompassDegrees(targetHeadingDegrees)
        clearActiveTurnCommand()
        clearAutoNavigationExecution("自动导航已取消")
        val command = prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.App,
                mode = ControlCommandMode.HeadingLock,
                headingLockEnabled = true,
                headingLockRequestId = 1,
                headingLockBaseThrottlePercent = currentAverageThrottlePercent(),
                headingLockTargetDegrees = normalizedTarget,
            ),
            allocateHeadingLockRequestId = true,
        )
        startAppHeadingLock(command, normalizedTarget)
        appendControlLog(
            level = ControlLogLevel.Info,
            title = "用户操作",
            message = "设置锁航目标 ${normalizedTarget.roundToInt()}°；下发主控绝对目标航向",
        )
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                statusMessage = "目标航向已设为 ${normalizedTarget.roundToInt()}°",
            )
        }
        sendCurrentCommand()
    }

    fun requestTrackLogSync() {
        val nextTransport = transport
        if (nextTransport == null) {
            refreshGpsTrackState("未连接主控，无法同步轨迹")
            return
        }
        if (esp32OtaExclusive) {
            refreshGpsTrackState("主控 OTA 进行中，暂不同步轨迹")
            return
        }
        viewModelScope.launch {
            runCatching {
                trackLogBatchPoints.clear()
                mutableUiState.update {
                    it.copy(
                        gpsTrack = it.gpsTrack.copy(
                            syncing = true,
                            syncStartSequence = null,
                            syncTargetSequence = null,
                            syncCurrentSequence = null,
                            syncMessage = "正在查询主控轨迹缓存",
                        ),
                    )
                }
                nextTransport.sendRawLine("LOG_INFO")
            }.onFailure { error ->
                refreshGpsTrackState("轨迹同步请求失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun startMagCalibration() {
        sendMagCalibrationAction(
            action = "START",
            requestedMessage = "已请求磁力计校准，请缓慢转动板体覆盖各方向",
            failurePrefix = "磁力计校准启动失败",
            forceLockedNeutral = true,
        )
    }

    fun saveMagCalibration() {
        sendMagCalibrationAction(
            action = "SAVE",
            requestedMessage = "已请求保存磁力计校准",
            failurePrefix = "磁力计校准保存失败",
            forceLockedNeutral = true,
        )
    }

    fun clearMagCalibration() {
        sendMagCalibrationAction(
            action = "CLEAR",
            requestedMessage = "已请求清除磁力计校准",
            failurePrefix = "磁力计校准清除失败",
            forceLockedNeutral = true,
        )
    }

    fun refreshMagCalibrationStatus() {
        sendMagCalibrationAction(
            action = "STATUS",
            requestedMessage = "已请求刷新磁力计校准状态",
            failurePrefix = "磁力计校准状态刷新失败",
            forceLockedNeutral = false,
        )
    }

    fun startYbImuCalibration() {
        sendYbCalibrationAction(
            target = "IMU",
            action = "START",
            requestedMessage = "已请求 YB IMU 校准，请保持主控 IMU 静止",
            failurePrefix = "YB IMU 校准启动失败",
        )
    }

    fun startYbMagCalibration() {
        sendYbCalibrationAction(
            target = "MAG",
            action = "START",
            requestedMessage = "已请求 YB 磁力计校准，请缓慢转动板体覆盖各方向",
            failurePrefix = "YB 磁力计校准启动失败",
        )
    }

    fun setGpsPlaybackIndex(index: Int) {
        mutableUiState.update {
            val points = it.gpsTrack.recentPoints
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    playbackIndex = if (points.isEmpty()) 0 else index.coerceIn(0, points.lastIndex),
                ),
            )
        }
    }

    fun selectGpsTrack(trackId: String) {
        val points = gpsTrackStore.readTrackPoints(trackId)
        mutableUiState.update {
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    selectedTrackId = trackId,
                    recentPoints = points,
                    playbackIndex = 0,
                    syncMessage = if (points.isEmpty()) "该轨迹没有可播放点" else "已选择轨迹",
                ),
            )
        }
    }

    fun deleteGpsTrack(trackId: String) {
        val deleted = gpsTrackStore.deleteTrack(trackId)
        refreshGpsTrackState(if (deleted) "已删除轨迹" else "未找到要删除的轨迹")
    }

    fun addAutoNavigationRoute() {
        val now = System.currentTimeMillis() / 1000L
        val routeId = "route-$now-${Random.nextInt(100, 999)}"
        val routeName = "路线 ${mutableUiState.value.autoNavigation.routes.size + 1}"
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    selectedRouteId = routeId,
                    editingRouteId = routeId,
                    editingRouteName = routeName,
                    editingPoints = emptyList(),
                    editingNewRoute = true,
                    message = "正在编辑 $routeName",
                ),
                statusMessage = "路线编辑已开始",
            )
        }
    }

    fun editAutoNavigationRoute(routeId: String) {
        val route = mutableUiState.value.autoNavigation.routes.firstOrNull { it.id == routeId }
        if (route == null) {
            mutableUiState.update {
                it.copy(autoNavigation = it.autoNavigation.copy(message = "未找到要编辑的路线"))
            }
            return
        }
        clearAutonomousCommands()
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    selectedRouteId = route.id,
                    editingRouteId = route.id,
                    editingRouteName = route.name,
                    editingPoints = route.points,
                    editingNewRoute = false,
                    message = "正在编辑 ${route.name}",
                ),
                statusMessage = "路线编辑已开始",
            )
        }
        sendCurrentCommand()
    }

    fun addAutoNavigationRoutePoint(latitude: Double, longitude: Double) {
        val autoState = mutableUiState.value.autoNavigation
        if (!autoState.editing) {
            return
        }
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingPoints = it.autoNavigation.editingPoints + NavigationRoutePoint(latitude, longitude),
                    message = "已添加航点 ${it.autoNavigation.editingPoints.size + 1}",
                ),
            )
        }
    }

    fun updateAutoNavigationRoutePoint(index: Int, latitude: Double, longitude: Double) {
        val autoState = mutableUiState.value.autoNavigation
        if (!autoState.editing || index !in autoState.editingPoints.indices) {
            return
        }
        mutableUiState.update {
            val points = it.autoNavigation.editingPoints.toMutableList()
            points[index] = NavigationRoutePoint(latitude, longitude)
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingPoints = points,
                    message = "已移动航点 ${index + 1}",
                ),
            )
        }
    }

    fun deleteAutoNavigationRoutePoint(index: Int) {
        val autoState = mutableUiState.value.autoNavigation
        if (!autoState.editing || index !in autoState.editingPoints.indices) {
            return
        }
        mutableUiState.update {
            val points = it.autoNavigation.editingPoints.toMutableList()
            points.removeAt(index)
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingPoints = points,
                    message = "已删除航点 ${index + 1}",
                ),
            )
        }
    }

    fun moveAutoNavigationRoutePoint(index: Int, delta: Int) {
        val autoState = mutableUiState.value.autoNavigation
        val targetIndex = index + delta
        if (
            !autoState.editing ||
            index !in autoState.editingPoints.indices ||
            targetIndex !in autoState.editingPoints.indices
        ) {
            return
        }
        mutableUiState.update {
            val points = it.autoNavigation.editingPoints.toMutableList()
            val point = points.removeAt(index)
            points.add(targetIndex, point)
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingPoints = points,
                    message = "已调整航点顺序：${targetIndex + 1}",
                ),
            )
        }
    }

    fun removeLastAutoNavigationRoutePoint() {
        mutableUiState.update {
            val points = it.autoNavigation.editingPoints
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingPoints = points.dropLast(1),
                    message = if (points.isEmpty()) "当前没有可撤销航点" else "已撤销最后一个航点",
                ),
            )
        }
    }

    fun saveAutoNavigationRoute() {
        val autoState = mutableUiState.value.autoNavigation
        val routeId = autoState.editingRouteId ?: return
        if (autoState.editingPoints.size < 2) {
            mutableUiState.update {
                it.copy(autoNavigation = it.autoNavigation.copy(message = "路线至少需要 2 个航点"))
            }
            return
        }

        val existing = autoState.routes.firstOrNull { it.id == routeId }
        val now = System.currentTimeMillis() / 1000L
        val route = NavigationRoute(
            id = routeId,
            name = autoState.editingRouteName.ifBlank { "未命名路线" },
            createdAtEpochSeconds = existing?.createdAtEpochSeconds ?: now,
            updatedAtEpochSeconds = now,
            points = autoState.editingPoints,
        )
        autoNavigationRouteStore.upsertRoute(route)
        refreshAutoNavigationRoutes("已保存 ${route.name}", selectedRouteId = route.id)
    }

    fun cancelAutoNavigationRouteEditing() {
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    editingRouteId = null,
                    editingRouteName = "",
                    editingPoints = emptyList(),
                    editingNewRoute = false,
                    message = "已退出路线编辑",
                ),
            )
        }
    }

    fun deleteAutoNavigationRoute(routeId: String) {
        if (mutableUiState.value.autoNavigation.executingRouteId == routeId) {
            stopAutoNavigation()
        }
        val deleted = autoNavigationRouteStore.deleteRoute(routeId)
        refreshAutoNavigationRoutes(if (deleted) "已删除路线" else "未找到要删除的路线")
    }

    fun renameAutoNavigationRoute(routeId: String, name: String) {
        val newName = name.trim().takeIf { it.isNotBlank() } ?: "未命名路线"
        val route = mutableUiState.value.autoNavigation.routes.firstOrNull { it.id == routeId }
        if (route == null) {
            mutableUiState.update {
                it.copy(autoNavigation = it.autoNavigation.copy(message = "未找到要改名的路线"))
            }
            return
        }
        val updated = route.copy(
            name = newName,
            updatedAtEpochSeconds = System.currentTimeMillis() / 1000L,
        )
        autoNavigationRouteStore.upsertRoute(updated)
        refreshAutoNavigationRoutes("已重命名为 $newName", selectedRouteId = routeId)
    }

    fun selectAutoNavigationRoute(routeId: String) {
        val route = mutableUiState.value.autoNavigation.routes.firstOrNull { it.id == routeId }
        if (route == null) {
            mutableUiState.update {
                it.copy(autoNavigation = it.autoNavigation.copy(message = "未找到要选择的路线"))
            }
            return
        }
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    selectedRouteId = route.id,
                    editingRouteId = null,
                    editingRouteName = "",
                    editingPoints = emptyList(),
                    editingNewRoute = false,
                    message = "已选择 ${route.name}",
                ),
                statusMessage = "已选择自动导航路线：${route.name}",
            )
        }
    }

    fun clearSelectedAutoNavigationRoute() {
        mutableUiState.update {
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    selectedRouteId = null,
                    message = "已退出路线模式",
                ),
                statusMessage = "已退出路线模式",
            )
        }
    }

    fun startAutoNavigation(routeId: String) {
        val state = mutableUiState.value
        val route = state.autoNavigation.routes.firstOrNull { it.id == routeId }
        val rejectReason = autoNavigationStartRejectReason(route)
        if (rejectReason != null) {
            mutableUiState.update {
                it.copy(
                    autoNavigation = it.autoNavigation.copy(
                        selectedRouteId = routeId,
                        message = rejectReason,
                    ),
                    statusMessage = rejectReason,
                )
            }
            return
        }

        clearAutonomousCommands()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        autoNavigationGpsInvalidSinceMs = 0L
        autoNavigationHeadingInvalidSinceMs = 0L
        val initialTargetIndex = nearestAutoNavigationTargetIndex(
            route = route!!,
            currentPoint = currentGpsPointOrNull(),
        )
        mutableUiState.update {
            it.copy(
                armed = true,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                autoNavigation = it.autoNavigation.copy(
                    selectedRouteId = routeId,
                    executingRouteId = routeId,
                    targetPointIndex = initialTargetIndex,
                    gearIndex = STATION_KEEPING_DEFAULT_GEAR_INDEX,
                    distanceToTargetMeters = null,
                    headingErrorDegrees = null,
                    trackLineLockEnabled = false,
                    trackLineOrigin = null,
                    trackLineBearingDegrees = null,
                    trackLineTargetHeadingDegrees = null,
                    trackLineCrossTrackErrorMeters = null,
                    trackLineAlongTrackMeters = null,
                    leftOutputPercent = 0,
                    rightOutputPercent = 0,
                    message = "自动导航已启动：目标 ${initialTargetIndex + 1}",
                ),
                statusMessage = "自动导航已启动，请保持人工接管准备",
            )
        }
        sendCurrentCommand()
        speakVoiceReply("开始自动驾驶，请注意安全")
    }

    fun startTrackLineLock() {
        val rejectReason = trackLineLockStartRejectReason()
        if (rejectReason != null) {
            mutableUiState.update {
                it.copy(
                    autoNavigation = it.autoNavigation.copy(message = rejectReason),
                    statusMessage = rejectReason,
                )
            }
            return
        }
        val origin = currentGpsPointOrNull() ?: return
        val bearing = currentHeadingForLockOrNull() ?: return

        clearAutonomousCommands()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        mutableUiState.update {
            it.copy(
                armed = true,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                autoNavigation = it.autoNavigation.copy(
                    executingRouteId = null,
                    targetPointIndex = 0,
                    gearIndex = 0,
                    distanceToTargetMeters = null,
                    headingErrorDegrees = null,
                    trackLineLockEnabled = true,
                    trackLineOrigin = origin,
                    trackLineBearingDegrees = bearing,
                    trackLineTargetHeadingDegrees = bearing,
                    trackLineCrossTrackErrorMeters = 0.0,
                    trackLineAlongTrackMeters = 0.0,
                    leftOutputPercent = 0,
                    rightOutputPercent = 0,
                    message = "航迹线锁定已启动：${bearing.roundToInt()}°",
                ),
                statusMessage = "航迹线锁定已启动，请保持人工接管准备",
            )
        }
        sendCurrentCommand()
    }

    fun startStationKeeping() {
        val rejectReason = stationKeepingStartRejectReason()
        if (rejectReason != null) {
            mutableUiState.update {
                it.copy(
                    autoNavigation = it.autoNavigation.copy(message = rejectReason),
                    statusMessage = rejectReason,
                )
            }
            return
        }
        val target = currentGpsPointOrNull() ?: return
        val targetHeading = currentPhoneHeadingDegreesOrNull() ?: return

        clearAutonomousCommands()
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        autoNavigationGpsInvalidSinceMs = 0L
        autoNavigationHeadingInvalidSinceMs = 0L
        stationKeepingFilteredPoint = null
        stationKeepingLastRawPoint = null
        val logSnapshot = stationKeepingLogStore.startSession()
        mutableUiState.update {
            it.copy(
                armed = true,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                autoNavigation = it.autoNavigation.copy(
                    executingRouteId = null,
                    targetPointIndex = 0,
                    gearIndex = 0,
                    distanceToTargetMeters = 0.0,
                    headingErrorDegrees = 0f,
                    trackLineLockEnabled = false,
                    trackLineOrigin = null,
                    trackLineBearingDegrees = null,
                    trackLineTargetHeadingDegrees = null,
                    trackLineCrossTrackErrorMeters = null,
                    trackLineAlongTrackMeters = null,
                    stationKeepingEnabled = true,
                    stationKeepingTarget = target,
                    stationKeepingTargetHeadingDegrees = targetHeading,
                    stationKeepingForwardErrorMeters = 0.0,
                    stationKeepingLateralErrorMeters = 0.0,
                    stationKeepingPositionActive = false,
                    stationKeepingMovingReverse = false,
                    leftOutputPercent = 0,
                    rightOutputPercent = 0,
                    message = "定点保持已启动：${targetHeading.roundToInt()}°",
                ),
                stationKeepingLogPath = logSnapshot.filePath,
                stationKeepingLogSampleCount = logSnapshot.sampleCount,
                statusMessage = "定点保持已启动，请保持人工接管准备",
            )
        }
        sendCurrentCommand()
    }

    fun increaseAutoNavigationGear() {
        mutableUiState.update {
            val nextGear = (it.autoNavigation.gearIndex + 1).coerceAtMost(AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
            val modeText = when {
                it.autoNavigation.stationKeepingEnabled -> "定点保持"
                it.autoNavigation.trackLineLockEnabled -> "航迹线锁定"
                else -> "自动导航"
            }
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = nextGear,
                    message = "$modeText 档位 ${nextGear + 1}（${AUTO_NAVIGATION_GEAR_PERCENTS[nextGear]}%）",
                ),
            )
        }
    }

    fun decreaseAutoNavigationGear() {
        mutableUiState.update {
            val nextGear = (it.autoNavigation.gearIndex - 1).coerceAtLeast(0)
            val modeText = when {
                it.autoNavigation.stationKeepingEnabled -> "定点保持"
                it.autoNavigation.trackLineLockEnabled -> "航迹线锁定"
                else -> "自动导航"
            }
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = nextGear,
                    message = "$modeText 档位 ${nextGear + 1}（${AUTO_NAVIGATION_GEAR_PERCENTS[nextGear]}%）",
                ),
            )
        }
    }

    fun stopTrackLineLock() {
        clearAutoNavigationExecution("航迹线锁定已停止")
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "航迹线锁定已停止，已回空挡，主控保持当前解锁状态",
            )
        }
        sendCurrentCommand()
    }

    fun stopStationKeeping() {
        clearAutoNavigationExecution("定点保持已停止")
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "定点保持已停止，已回空挡，主控保持当前解锁状态",
            )
        }
        sendCurrentCommand()
    }

    fun stopAutoNavigation() {
        clearAutoNavigationExecution("自动导航已停止")
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "自动导航已停止，已回空挡，主控保持当前解锁状态",
            )
        }
        sendCurrentCommand()
    }

    fun toggleVoiceControl() {
        setVoiceControlEnabled(!mutableUiState.value.voiceControlEnabled)
    }

    fun setRealtimeWakeWordRequired(required: Boolean) {
        preferences.edit().putBoolean(KEY_REALTIME_WAKE_WORD_REQUIRED, required).apply()
        mutableUiState.update {
            it.copy(
                realtimeWakeWordRequired = required,
                realtimeVoiceStatus = if (required) {
                    "Ark Agent：唤醒词模式，等待“豆包”"
                } else {
                    "Ark Agent：直接听指令"
                },
                realtimeVoiceControlEvent = if (required) {
                    "唤醒词模式：无“豆包”前缀不发送方舟"
                } else {
                    "直接模式：检测人声后提交方舟"
                },
                statusMessage = if (required) {
                    "已开启唤醒词模式：指令必须以“豆包”开头"
                } else {
                    "已关闭唤醒词模式：检测到人声后直接提交"
                },
            )
        }
    }

    fun setVoiceControlEnabled(enabled: Boolean) {
        if (enabled && mutableSettingsState.value.realtimeVoiceApiKey.isBlank()) {
            mutableUiState.update {
                it.copy(
                    voiceControlEnabled = false,
                    realtimeVoiceMode = RealtimeVoiceMode.Off,
                    realtimeVoiceStatus = "Ark Agent：未配置火山引擎 API Key",
                    realtimeVoiceControlEvent = "无控制事件",
                    voiceResultMessage = "请先在设置页填写火山引擎 API Key",
                    voiceCommandPreview = "缺少火山引擎 API Key：不启动",
                    statusMessage = "请先在设置页填写火山引擎 API Key",
                )
            }
            return
        }
        mutableUiState.update {
            it.copy(
                voiceControlEnabled = enabled,
                voiceSamplingEnabled = if (enabled) it.voiceSamplingEnabled else false,
                voiceAsrState = if (it.voiceSamplingEnabled && !enabled) VoiceAsrState.Starting else VoiceAsrState.Stopped,
                voiceAsrStatus = if (it.voiceSamplingEnabled && !enabled) {
                    "本地 ASR：采样模式初始化"
                } else {
                    "本地 ASR：已暂缓"
                },
                realtimeVoiceMode = if (enabled) RealtimeVoiceMode.Live else RealtimeVoiceMode.Off,
                realtimeVoiceStatus = if (enabled) {
                    if (it.realtimeWakeWordRequired) {
                        "Ark Agent：唤醒词模式，等待“豆包”"
                    } else {
                        "Ark Agent：实时监听中，检测人声后自动提交"
                    }
                } else {
                    "Ark Agent：未录音"
                },
                realtimeVoiceControlEvent = if (enabled) {
                    if (it.realtimeWakeWordRequired) {
                        "等待“豆包”开头的指令"
                    } else {
                        "等待云端控制事件"
                    }
                } else {
                    "无控制事件"
                },
                voiceResultMessage = if (enabled) {
                    if (it.realtimeWakeWordRequired) {
                        "Ark 音频 Agent 已开启：无“豆包”前缀不发送方舟"
                    } else {
                        "Ark 音频 Agent 已开启，检测人声后自动提交"
                    }
                } else {
                    "声控已关闭"
                },
                voiceCommandPreview = if (enabled) it.voiceCommandPreview else "声控关闭：不发送",
                statusMessage = if (enabled) {
                    if (it.realtimeWakeWordRequired) {
                        "Ark 音频 Agent 唤醒词模式：静音不上传，无唤醒词不发方舟"
                    } else {
                        "Ark 音频 Agent 实时监听：静音不上传"
                    }
                } else {
                    "声控已关闭"
                },
            )
        }
    }

    fun startRealtimePushToTalk() {
        if (mutableSettingsState.value.realtimeVoiceApiKey.isBlank()) {
            mutableUiState.update {
                it.copy(
                    voiceControlEnabled = false,
                    realtimeVoiceMode = RealtimeVoiceMode.Off,
                    realtimeVoiceStatus = "Ark Agent：未配置火山引擎 API Key",
                    voiceResultMessage = "请先在设置页填写火山引擎 API Key",
                    statusMessage = "请先在设置页填写火山引擎 API Key",
                )
            }
            return
        }
        mutableUiState.update {
            it.copy(
                voiceControlEnabled = true,
                realtimeVoiceMode = RealtimeVoiceMode.PushToTalk,
                realtimeVoiceStatus = "Ark Agent：按住录音中",
                realtimeVoiceControlEvent = if (it.realtimeWakeWordRequired) {
                    "松开后先检查“豆包”唤醒词"
                } else {
                    "等待方舟工具调用"
                },
                voiceResultMessage = "按住说话已开始，松开发送方舟",
                statusMessage = "Ark 音频 Agent：松开后提交音频",
            )
        }
    }

    private fun disableRealtimeVoiceControlByAgent(reason: String): String {
        clearRealtimeVoiceActionTimeout()
        val resultText = "已关闭语音控制"
        mutableUiState.update {
            it.copy(
                voiceControlEnabled = false,
                voiceSamplingEnabled = false,
                voiceAsrState = VoiceAsrState.Stopped,
                voiceAsrStatus = "本地 ASR：已暂缓",
                realtimeVoiceMode = RealtimeVoiceMode.Off,
                realtimeVoiceStatus = "Ark Agent：已关闭",
                realtimeVoiceControlEvent = "关闭语音控制：$reason",
                realtimeVoiceReply = resultText,
                voiceResultMessage = "实时语音：已关闭",
                voiceCommandPreview = "disable_voice_control：不发送推进控制",
                statusMessage = "实时语音已关闭：$reason",
                realtimeWakeWordRequired = it.realtimeWakeWordRequired,
            )
        }
        return resultText
    }

    fun stopRealtimePushToTalk() {
        mutableUiState.update {
            it.copy(
                voiceControlEnabled = false,
                realtimeVoiceMode = RealtimeVoiceMode.Off,
                realtimeVoiceStatus = "Ark Agent：正在提交或已结束",
                voiceResultMessage = "按住说话已结束，等待方舟响应",
            )
        }
    }

    fun setRealtimeVoiceTranscript(text: String) {
        mutableUiState.update {
            it.copy(
                realtimeVoiceTranscript = text,
                voiceInputText = text,
            )
        }
    }

    fun setRealtimeVoiceReply(text: String) {
        mutableUiState.update {
            it.copy(realtimeVoiceReply = text)
        }
    }

    fun setRealtimeVoiceStatus(text: String) {
        mutableUiState.update {
            it.copy(realtimeVoiceStatus = text)
        }
    }

    fun setRealtimeVoiceMetrics(text: String) {
        mutableUiState.update {
            it.copy(realtimeVoiceMetrics = text)
        }
    }

    fun acceptRealtimeVoiceControlEventJson(jsonText: String): String {
        val event = RealtimeVoiceControlEvent.parse(jsonText).getOrElse { error ->
            val resultText = "拒绝执行：${error.message ?: "控制事件解析失败"}"
            mutableUiState.update {
                it.copy(
                    realtimeVoiceControlEvent = resultText,
                    realtimeVoiceReply = resultText,
                    voiceResultMessage = "实时语音事件拒绝：${error.message ?: "解析失败"}",
                    statusMessage = "实时语音事件拒绝",
                )
            }
            return resultText
        }
        return executeRealtimeVoiceControlEvent(event, jsonText)
    }

    fun setVoiceAsrStatus(message: String) {
        mutableUiState.update {
            it.copy(
                voiceAsrStatus = message,
                voiceAsrState = voiceAsrStateFor(message, it.voiceSamplingEnabled),
            )
        }
    }

    fun markVoiceAsrStarting(message: String = "Qwen ASR：初始化模型") {
        mutableUiState.update {
            it.copy(
                voiceAsrStatus = message,
                voiceAsrState = VoiceAsrState.Starting,
            )
        }
    }

    fun markVoiceAsrStopped(message: String = "Qwen ASR：已暂停") {
        mutableUiState.update {
            it.copy(
                voiceAsrStatus = message,
                voiceAsrState = VoiceAsrState.Stopped,
            )
        }
    }

    fun setVoiceInputText(text: String) {
        if (isVoiceReplySuppressingRecognition()) {
            return
        }
        val preview = previewVoiceCommand(text)
        mutableUiState.update {
            it.copy(
                voiceInputText = text,
                voiceResultMessage = preview.message,
                voiceCandidatePreview = preview.candidateLine,
                voiceCommandPreview = preview.commandLine,
            )
        }
    }

    fun executeVoiceInput(evaluationOverride: VoiceCommandEvaluation? = null) {
        val currentState = mutableUiState.value
        if (currentState.voiceSamplingEnabled) {
            mutableUiState.update {
                it.copy(
                    voiceResultMessage = "采样模式中，只记录样本，不发送控制命令",
                    voiceCommandPreview = "采样模式：不发送",
                    statusMessage = "语音采样模式：控制输出保持空闲",
                )
            }
            return
        }
        val evaluation = evaluationOverride ?: VoiceCommandParser.evaluate(currentState.voiceInputText)
        val candidateLine = formatCandidatePreview(evaluation)
        when (val result = evaluation.result) {
            is VoiceParseResult.Rejected -> {
                mutableUiState.update {
                    it.copy(
                        voiceResultMessage = result.reason,
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = "不发送",
                        statusMessage = "语音命令拒绝：${result.reason}",
                    )
                }
            }
            is VoiceParseResult.Accepted -> executeAcceptedVoiceCommand(result, candidateLine)
        }
    }

    fun acceptVoiceRecognition(text: String) {
        val recognizedText = text.trim()
        if (recognizedText.isBlank()) {
            return
        }
        if (isVoiceReplySuppressingRecognition()) {
            Log.i(TAG, "TTS 播报屏蔽窗口内忽略 ASR 最终文本：$recognizedText")
            return
        }
        if (mutableUiState.value.voiceSamplingEnabled) {
            acceptVoiceSample(recognizedText, FloatArray(0))
            return
        }
        val evaluation = VoiceCommandParser.evaluate(recognizedText)
        if (shouldSuppressRepeatedVoiceRecognition(evaluation)) {
            return
        }
        val preview = previewVoiceEvaluation(evaluation)
        mutableUiState.update {
            it.copy(
                voiceInputText = recognizedText,
                voiceResultMessage = preview.message,
                voiceCandidatePreview = preview.candidateLine,
                voiceCommandPreview = preview.commandLine,
            )
        }
        executeVoiceInput(evaluation)
    }

    fun acceptVoiceSample(text: String, samples: FloatArray) {
        val recognizedText = text.trim()
        if (recognizedText.isBlank()) {
            return
        }
        if (!mutableUiState.value.voiceSamplingEnabled) {
            acceptVoiceRecognition(recognizedText)
            return
        }

        val preview = previewVoiceCommand(recognizedText)
        val accepted = VoiceCommandParser.evaluate(recognizedText).result is VoiceParseResult.Accepted
        val target = currentVoiceSampleTarget()
        pendingVoiceSample = PendingVoiceSample(
            target = target,
            samples = samples,
            asrText = recognizedText,
            parsedCommand = preview.commandLine,
            accepted = accepted,
        )
        mutableUiState.update {
            it.copy(
                voiceInputText = recognizedText,
                voiceResultMessage = "采样完成，等待标记正确或失败",
                voiceCandidatePreview = preview.candidateLine,
                voiceCommandPreview = "采样模式：不发送",
                voiceSamplePendingText = recognizedText,
                voiceSamplePendingCommand = preview.commandLine,
                voiceSampleLastMessage = "已录到待保存样本：${target.label}",
                statusMessage = "语音采样模式：控制输出保持空闲",
            )
        }
    }

    fun setVoiceSamplingEnabled(enabled: Boolean) {
        pendingVoiceSample = null
        clearAutonomousCommands()
        val savedVoiceSampleCount = VoiceSampleStore.countSavedSamples(getApplication())
        mutableUiState.update {
            it.copy(
                voiceSamplingEnabled = enabled,
                armed = if (enabled) false else it.armed,
                leftThrottlePercent = if (enabled) 0 else it.leftThrottlePercent,
                rightThrottlePercent = if (enabled) 0 else it.rightThrottlePercent,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = if (enabled) ThrottleGear.Neutral else it.selectedGear,
                throttleTrimPercent = if (enabled) 0 else it.throttleTrimPercent,
                voiceSamplePendingText = "",
                voiceSamplePendingCommand = "无待保存样本",
                voiceSampleSavedCount = savedVoiceSampleCount,
                voiceSampleLastMessage = if (enabled) {
                    "采样模式已开启：只录音，不发送控制命令"
                } else {
                    "采样模式已关闭"
                },
                voiceResultMessage = if (enabled) {
                    "采样模式中，只记录样本，不发送控制命令"
                } else {
                    "语音控制待输入"
                },
                voiceCommandPreview = if (enabled) "采样模式：不发送" else "不发送",
                statusMessage = if (enabled) {
                    "语音采样模式：控制输出保持空闲"
                } else {
                    "语音采样模式已关闭"
                },
            )
        }
        if (enabled) {
            sendIdle()
        }
    }

    fun nextVoiceSampleTarget() {
        pendingVoiceSample = null
        voiceSampleTargetIndex = (voiceSampleTargetIndex + 1) % voiceSampleTargets.size
        applyVoiceSampleTarget("已切换到下一条")
    }

    fun discardPendingVoiceSample() {
        pendingVoiceSample = null
        mutableUiState.update {
            it.copy(
                voiceSamplePendingText = "",
                voiceSamplePendingCommand = "无待保存样本",
                voiceSampleLastMessage = "已丢弃，重新说当前目标指令",
            )
        }
    }

    fun savePendingVoiceSample(correct: Boolean) {
        val pending = pendingVoiceSample
        if (pending == null) {
            mutableUiState.update {
                it.copy(voiceSampleLastMessage = "没有待保存样本")
            }
            return
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val jsonFile = VoiceSampleStore.save(
                        context = getApplication(),
                        samples = pending.samples,
                        metadata = VoiceSampleMetadata(
                            target = pending.target,
                            asrText = pending.asrText,
                            parsedCommand = pending.parsedCommand,
                            accepted = pending.accepted,
                            userJudgement = if (correct) "correct" else "failure",
                            sampleRate = VoiceSampleStore.SAMPLE_RATE,
                            sampleCount = pending.samples.size,
                        ),
                    )
                    jsonFile to VoiceSampleStore.countSavedSamples(getApplication())
                }
            }.onSuccess { (jsonFile, savedCount) ->
                pendingVoiceSample = null
                voiceSampleTargetIndex = (voiceSampleTargetIndex + 1) % voiceSampleTargets.size
                val nextTarget = currentVoiceSampleTarget()
                mutableUiState.update {
                    it.copy(
                        voiceSampleSavedCount = savedCount,
                        voiceSampleTargetLabel = nextTarget.label,
                        voiceSampleTargetText = nextTarget.spokenText,
                        voiceSampleExpectedCommand = nextTarget.expectedCommand,
                        voiceSamplePendingText = "",
                        voiceSamplePendingCommand = "无待保存样本",
                        voiceSampleLastMessage = "已保存：${jsonFile.name}",
                        voiceSampleDirectory = jsonFile.parentFile?.absolutePath.orEmpty(),
                    )
                }
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(voiceSampleLastMessage = "保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun setRampLimitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RAMP_LIMIT, enabled).apply()
        mutableSettingsState.update { it.copy(rampLimitEnabled = enabled) }
    }

    fun setLeftEscReversed(enabled: Boolean) {
        updateEscDirectionConfig(
            leftReversed = enabled,
            neutralMessage = "左 ESC 方向${if (enabled) "已反转" else "已恢复"}",
        )
    }

    fun setRightEscReversed(enabled: Boolean) {
        updateEscDirectionConfig(
            rightReversed = enabled,
            neutralMessage = "右 ESC 方向${if (enabled) "已反转" else "已恢复"}",
        )
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
                    message = "正在检查更新",
                    progressText = "",
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    val release = releaseClient.fetchLatestRelease()
                    val manifestAsset = release.firmwareManifestAsset
                    val manifest = manifestAsset?.let { asset ->
                        parseFirmwareManifest(
                            releaseClient.fetchText(
                                url = asset.apiUrl,
                                apiAssetDownload = true,
                            ),
                        )
                    }
                    ReleaseCheckResult(release = release, firmwareManifest = manifest)
                }
            }.onSuccess { result ->
                val release = result.release
                val manifest = result.firmwareManifest
                latestRelease = release
                latestFirmwareManifest = manifest
                val apkAsset = release.apkAsset
                val firmwareAsset = manifest?.let { release.assets.firstOrNull { asset -> asset.name == it.firmwareAssetName } }
                    ?: release.firmwareAsset
                val manifestAsset = release.firmwareManifestAsset
                val updateAvailable = isReleaseNewerThanInstalled(release.tagName)
                val esp32UpdateAvailable = isEsp32FirmwareUpdateAvailable(
                    targetVersion = manifest?.firmwareVersion,
                    currentVersion = mutableUpdateState.value.currentEsp32FirmwareVersion,
                )
                mutableUpdateState.update {
                    it.copy(
                        checking = false,
                        latestVersionName = release.tagName,
                        targetEsp32FirmwareVersion = manifest?.firmwareVersion,
                        appUpdateAvailable = updateAvailable && apkAsset != null,
                        esp32UpdateAvailable = esp32UpdateAvailable && firmwareAsset != null && manifestAsset != null && manifest != null,
                        appAssetName = apkAsset?.name,
                        firmwareAssetName = firmwareAsset?.name,
                        firmwareManifestName = manifestAsset?.name,
                        appDownloadUrl = apkAsset?.downloadUrl,
                        firmwareDownloadUrl = firmwareAsset?.downloadUrl,
                        firmwareManifestDownloadUrl = manifestAsset?.downloadUrl,
                        appAssetApiUrl = apkAsset?.apiUrl,
                        firmwareAssetApiUrl = firmwareAsset?.apiUrl,
                        firmwareManifestApiUrl = manifestAsset?.apiUrl,
                        message = when {
                            apkAsset == null && firmwareAsset == null -> "Release ${release.tagName} 没有 APK 或主控固件资产"
                            manifestAsset == null -> "Release ${release.tagName} 缺少主控发布清单，GitHub 固件刷写已禁用"
                            manifest == null -> "Release ${release.tagName} 发布清单解析失败，GitHub 固件刷写已禁用"
                            firmwareAsset?.name != manifest.firmwareAssetName -> "Release ${release.tagName} 找不到清单指定固件 ${manifest.firmwareAssetName}"
                            updateAvailable && esp32UpdateAvailable -> "发现 App 和主控固件更新"
                            updateAvailable -> "发现 App 新版本 ${release.tagName}"
                            esp32UpdateAvailable -> "发现主控固件更新 ${manifest.firmwareVersion}"
                            else -> "当前已是最新版本"
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
            val manifest = latestFirmwareManifest
            if (url == null || assetName == null) {
                mutableUpdateState.update { it.copy(message = "没有可用的主控固件资产") }
                return@launch
            }
            if (manifest == null) {
                mutableUpdateState.update { it.copy(message = "缺少主控发布清单，不能从 GitHub 自动刷写固件") }
                return@launch
            }
            if (manifest.board != ESP32_BOARD_ID) {
                mutableUpdateState.update { it.copy(message = "固件板型不匹配：${manifest.board}") }
                return@launch
            }
            if (manifest.firmwareAssetName != assetName) {
                mutableUpdateState.update { it.copy(message = "固件资产与清单不一致：$assetName") }
                return@launch
            }
            if (manifest.minAppVersion?.let { isVersionGreaterThan(it, BuildConfig.VERSION_NAME) } == true) {
                mutableUpdateState.update { it.copy(message = "请先升级 App 到 ${manifest.minAppVersion} 或更高版本") }
                return@launch
            }

            mutableUpdateState.update {
                it.copy(
                    downloading = true,
                    message = "正在下载主控固件：$assetName",
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
                val sizeMatches = manifest.sizeBytes <= 0 || firmwareBytes.size.toLong() == manifest.sizeBytes
                val sha256 = sha256Hex(firmwareBytes)
                if (!sizeMatches || !sha256.equals(manifest.sha256Hex, ignoreCase = true)) {
                    mutableUpdateState.update {
                        it.copy(
                            downloading = false,
                            message = "主控固件校验失败：大小或 SHA-256 与发布清单不一致",
                            progressText = "",
                        )
                    }
                    return@launch
                }
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        targetEsp32FirmwareVersion = manifest.firmwareVersion,
                        progressText = "",
                    )
                }
                uploadEsp32FirmwareBytes(firmwareBytes, "GitHub 固件", manifest.firmwareVersion)
            }.onFailure { error ->
                mutableUpdateState.update {
                    it.copy(
                        downloading = false,
                        message = "下载主控固件失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun uploadKnownLocalEsp32Firmware() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val searchDirs = listOfNotNull(
                context.getExternalFilesDir(null),
                File("/sdcard/Download"),
                File("/storage/emulated/0/Download"),
            )
            var lastReadError: Throwable? = null
            val selected = withContext(Dispatchers.IO) {
                val firmwareFiles = searchDirs
                    .flatMap { dir ->
                        runCatching {
                            dir.listFiles()
                                .orEmpty()
                                .filter { it.isFile && isKnownLocalFirmwareFile(it.name) }
                        }.onFailure { lastReadError = it }.getOrDefault(emptyList())
                    }
                    .distinctBy { it.absolutePath }
                val latest = firmwareFiles.maxWithOrNull(
                    compareBy<File> { firmwareVersionSortScore(it.name) }
                        .thenBy { it.lastModified() },
                )
                latest?.let { file ->
                    runCatching { file to file.readBytes() }
                        .onFailure { lastReadError = it }
                        .getOrNull()
                }
            }

            if (selected == null) {
                val appPath = searchDirs.firstOrNull()?.absolutePath ?: "App 外部文件目录"
                val reason = lastReadError?.message?.let { "，最后错误：$it" }.orEmpty()
                mutableUpdateState.update {
                    it.copy(message = "固定路径固件读取失败$reason；请放 smart-sup-esp32-firmware-*.bin 到 $appPath")
                }
                return@launch
            }

            val (file, firmwareBytes) = selected
            val targetVersion = versionFromFirmwareFileName(file.name)
            val currentVersion = mutableUpdateState.value.currentEsp32FirmwareVersion
            if (targetVersion != null && currentVersion != null && sameVersion(targetVersion, currentVersion)) {
                mutableUpdateState.update {
                    it.copy(message = "固定路径固件 ${file.name} 与当前主控版本相同，已跳过 OTA")
                }
                return@launch
            }
            uploadEsp32FirmwareBytes(
                firmwareBytes = firmwareBytes,
                sourceLabel = "固定路径固件 ${file.name}",
                targetVersion = targetVersion,
            )
        }
    }

    private fun versionFromFirmwareFileName(fileName: String): String? {
        val name = fileName.substringAfterLast('/')
        val match = Regex("""(?:\d+-)?smart-sup-esp32-firmware-(.+)\.bin""", RegexOption.IGNORE_CASE)
            .matchEntire(name)
            ?: Regex("""firmware-(.+)\.bin""", RegexOption.IGNORE_CASE).matchEntire(name)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun isKnownLocalFirmwareFile(fileName: String): Boolean {
        return versionFromFirmwareFileName(fileName) != null
    }

    private fun firmwareVersionSortScore(fileName: String): Long {
        val parts = versionFromFirmwareFileName(fileName)
            ?.split('.', '-', '_')
            .orEmpty()
            .map { it.toLongOrNull() ?: 0L }
        val major = parts.getOrElse(0) { 0L }.coerceIn(0, 999)
        val minor = parts.getOrElse(1) { 0L }.coerceIn(0, 999)
        val patch = parts.getOrElse(2) { 0L }.coerceIn(0, 999)
        val build = parts.getOrElse(3) { 0L }.coerceIn(0, 999)
        return major * 1_000_000_000L + minor * 1_000_000L + patch * 1_000L + build
    }

    private data class VoicePreview(
        val message: String,
        val candidateLine: String,
        val commandLine: String,
    )

    private data class ReleaseCheckResult(
        val release: ReleaseInfo,
        val firmwareManifest: FirmwareReleaseManifest?,
    )

    private data class FineTuneTarget(
        val leftPercent: Int,
        val rightPercent: Int,
        val trimPercent: Int,
        val averagePercent: Int,
    )

    private fun previewVoiceCommand(text: String): VoicePreview {
        val evaluation = VoiceCommandParser.evaluate(text)
        return previewVoiceEvaluation(evaluation)
    }

    private fun executeRealtimeVoiceControlEvent(
        event: RealtimeVoiceControlEvent,
        rawJson: String,
    ): String {
        mutableUiState.update {
            it.copy(realtimeVoiceControlEvent = formatRealtimeVoiceEvent(event))
        }
        return when (event) {
            is RealtimeVoiceControlEvent.Stop -> {
                executeRealtimeNeutral(event.reason)
            }
            is RealtimeVoiceControlEvent.ExplainStatus -> {
                val resultText = buildRealtimeStatusReply()
                mutableUiState.update {
                    it.copy(
                        realtimeVoiceControlEvent = "状态回复：不改变推进输出",
                        voiceResultMessage = "实时语音：已回复状态",
                        realtimeVoiceReply = resultText,
                        voiceCommandPreview = "状态回复：不发送控制",
                        statusMessage = "实时语音已回复状态，不改变推进输出",
                    )
                }
                resultText
            }
            is RealtimeVoiceControlEvent.SetGear -> executeRealtimeSetGear(event)
            is RealtimeVoiceControlEvent.PivotTurn -> executeRealtimePivotTurn(event)
            is RealtimeVoiceControlEvent.SetLimitedPower -> executeRealtimeLimitedPower(event)
            is RealtimeVoiceControlEvent.AdjustHeadingTarget -> executeRealtimeHeadingTarget(event)
            is RealtimeVoiceControlEvent.SetHeadingTarget -> executeRealtimeSetHeadingTarget(event)
            is RealtimeVoiceControlEvent.CancelHeadingLock -> executeRealtimeCancelHeadingLock(event)
            is RealtimeVoiceControlEvent.DisableVoiceControl -> disableRealtimeVoiceControlByAgent(event.reason)
        }
    }

    private fun formatRealtimeVoiceEvent(event: RealtimeVoiceControlEvent): String {
        return when (event) {
            is RealtimeVoiceControlEvent.Stop ->
                "空挡：${event.reason}"
            is RealtimeVoiceControlEvent.SetGear ->
                "档位：${event.gear}；${event.reason}"
            is RealtimeVoiceControlEvent.PivotTurn ->
                "原地掉头：${event.direction}；${event.reason}"
            is RealtimeVoiceControlEvent.SetLimitedPower ->
                if (event.durationMs != null) {
                    "限幅推进：L=${event.leftPercent}%，R=${event.rightPercent}%，${event.durationMs}ms；${event.reason}"
                } else {
                    "限幅推进：L=${event.leftPercent}%，R=${event.rightPercent}%；${event.reason}"
                }
            is RealtimeVoiceControlEvent.AdjustHeadingTarget ->
                "调整航向：${event.deltaDegrees}°，基础 ${event.basePowerPercent}%，${event.durationMs}ms；${event.reason}"
            is RealtimeVoiceControlEvent.SetHeadingTarget ->
                "目标航向：${event.targetHeadingDegrees}°，基础 ${event.basePowerPercent}%；${event.reason}"
            is RealtimeVoiceControlEvent.CancelHeadingLock ->
                "取消航向锁定：${event.reason}"
            is RealtimeVoiceControlEvent.DisableVoiceControl ->
                "关闭语音控制：${event.reason}"
            is RealtimeVoiceControlEvent.ExplainStatus ->
                "状态回复：不改变推进输出"
        }
    }

    private fun buildRealtimeStatusReply(): String {
        val state = mutableUiState.value
        val connectionText = if (state.connectionState == ConnectionState.Connected) {
            "主控已连接"
        } else {
            "主控未连接"
        }
        val armedText = if (state.armed) "已解锁" else "未解锁"
        val headingText = if (state.headingLockEnabled) {
            "航向锁定中"
        } else {
            "未锁定航向"
        }
        return "我在。$connectionText，$armedText，当前档位${state.selectedGear.label}，$headingText。"
    }

    private fun executeRealtimeSetGear(event: RealtimeVoiceControlEvent.SetGear): String {
        val gear = realtimeVoiceGear(event.gear)
            ?: return rejectRealtimeVoiceEvent("不支持的档位：${event.gear}", "set_gear：不发送")
        if (gear == ThrottleGear.Neutral) {
            return executeRealtimeNeutral(event.reason)
        }
        val state = mutableUiState.value
        val throttleSource = CommandSource.App
        val gearThrottle = coerceCommandPercentForSource(gearPercent(gear), throttleSource)
        val command = ControlCommand(
            armed = true,
            leftThrottlePercent = gearThrottle,
            rightThrottlePercent = gearThrottle,
            source = throttleSource,
        )
        val commandLine = formatCommandLine(prepareRuntimeCommand(command))
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", commandLine)
        }
        if (!state.armed) {
            return rejectRealtimeVoiceEvent("语音不能解锁，请先手动解锁", commandLine)
        }
        clearRealtimeVoiceActionTimeout()
        val headingLockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = gearThrottle,
            rightPercent = gearThrottle,
            source = throttleSource,
        )
        if (!headingLockKept) {
            clearAutonomousCommands()
        }
        val resultText = "已设置${gear.label}"
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = gearThrottle,
                rightThrottlePercent = gearThrottle,
                commandSource = throttleSource,
                headingLockEnabled = if (headingLockKept) true else false,
                selectedGear = gear,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：已设置${gear.label}",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = if (headingLockKept) {
                    "实时语音档位：${gear.label} ${gearThrottle.signedPercentText()}，按 App 普通推进下发，航向锁定保持"
                } else {
                    "实时语音档位：${gear.label} ${gearThrottle.signedPercentText()}，按 App 普通推进下发"
                },
            )
        }
        sendCurrentCommand()
        return resultText
    }

    private fun executeRealtimePivotTurn(event: RealtimeVoiceControlEvent.PivotTurn): String {
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", "pivot_turn：不发送")
        }
        if (!state.armed) {
            return rejectRealtimeVoiceEvent("语音不能解锁，请先手动解锁", "pivot_turn：不发送")
        }

        clearRealtimeVoiceActionTimeout()
        val headingLockWasActive = isHeadingLockActiveForState(state)
        val directionText = if (event.direction == "left") "左" else "右"
        val turnDirection = if (event.direction == "left") TurnDirection.Left else TurnDirection.Right
        val neutralCommandLine = formatCommandLine(
            prepareRuntimeCommand(
                ControlCommand(
                    armed = true,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    source = CommandSource.App,
                ),
            ),
        )
        val token = realtimeVoiceActionToken
        val resultText = "已进入空挡，1秒后开始${directionText}原地掉头"
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：准备${directionText}原地掉头",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = neutralCommandLine,
                statusMessage = "实时语音${directionText}原地掉头：已先切空挡，等待1秒",
            )
        }
        sendCurrentCommand()
        realtimeVoiceActionTimeoutJob?.cancel()
        realtimeVoiceActionTimeoutJob = viewModelScope.launch {
            delay(PIVOT_TURN_NEUTRAL_DELAY_MS)
            if (token != realtimeVoiceActionToken) {
                return@launch
            }
            if (mutableUiState.value.connectionState != ConnectionState.Connected || !mutableUiState.value.armed) {
                mutableUiState.update {
                    it.copy(statusMessage = "实时语音${directionText}原地掉头取消：主控未连接或未解锁")
                }
                return@launch
            }
            val runtimeCommand = prepareRuntimeCommand(
                command = ControlCommand(
                    armed = true,
                    source = CommandSource.Voice,
                    mode = ControlCommandMode.TurnAngle,
                    turnDirection = turnDirection,
                    turnAngleDegrees = 179,
                    turnRequestId = 1,
                ),
                allocateTurnRequestId = true,
            )
            if (!headingLockWasActive) {
                clearActiveHeadingLockCommand()
            }
            startAppTurn(runtimeCommand)
            val commandLine = formatCommandLine(runtimeCommand)
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = headingLockWasActive,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    realtimeVoiceReply = "已开始${directionText}原地掉头",
                    voiceCommandPreview = commandLine,
                    statusMessage = "实时语音${directionText}原地掉头：已下发主控执行 179° 转向",
                )
            }
            sendCurrentCommand()
        }
        return resultText
    }

    private fun realtimeVoiceGear(gear: String): ThrottleGear? {
        return when (gear) {
            "reverse_3" -> ThrottleGear.Reverse3
            "reverse_2" -> ThrottleGear.Reverse2
            "reverse_1" -> ThrottleGear.Reverse1
            "neutral" -> ThrottleGear.Neutral
            "forward_1" -> ThrottleGear.Forward1
            "forward_2" -> ThrottleGear.Forward2
            "forward_3" -> ThrottleGear.Forward3
            "forward_4" -> ThrottleGear.Forward4
            else -> null
        }
    }

    private fun executeRealtimeNeutral(reason: String): String {
        clearRealtimeVoiceActionTimeout()
        val state = mutableUiState.value
        val throttleSource = CommandSource.App
        val commandLine = formatCommandLine(
            prepareRuntimeCommand(
                ControlCommand(
                    armed = state.armed,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    source = throttleSource,
                ),
            ),
        )
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", commandLine)
        }
        val headingLockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = 0,
            rightPercent = 0,
            source = throttleSource,
        )
        if (!headingLockKept) {
            clearAutonomousCommands()
        }
        val resultText = "已进入空挡"
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = throttleSource,
                headingLockEnabled = headingLockKept,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：已进入空挡",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = if (headingLockKept) {
                    "实时语音空挡：$reason，航向锁定保持，基础油门 0%"
                } else {
                    "实时语音空挡：$reason，按 App 普通推进下发"
                },
            )
        }
        sendCurrentCommand()
        return resultText
    }

    private fun executeRealtimeLimitedPower(event: RealtimeVoiceControlEvent.SetLimitedPower): String {
        val state = mutableUiState.value
        val throttleSource = CommandSource.App
        val left = coerceCommandPercentForSource(event.leftPercent, throttleSource)
        val right = coerceCommandPercentForSource(event.rightPercent, throttleSource)
        val commandLine = formatCommandLine(
            prepareRuntimeCommand(
                ControlCommand(
                    armed = true,
                    leftThrottlePercent = left,
                    rightThrottlePercent = right,
                    source = throttleSource,
                ),
            ),
        )
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", commandLine)
        }
        if (!state.armed) {
            return rejectRealtimeVoiceEvent("语音不能解锁，请先手动解锁", commandLine)
        }
        clearRealtimeVoiceActionTimeout()
        val headingLockKept = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = left,
            rightPercent = right,
            source = throttleSource,
        )
        if (!headingLockKept) {
            clearAutonomousCommands()
        }
        val resultText = "已设置左右推进：左 ${left.signedPercentText()}，右 ${right.signedPercentText()}"
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = left,
                rightThrottlePercent = right,
                commandSource = throttleSource,
                headingLockEnabled = headingLockKept,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：已设置左右推进",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = if (headingLockKept) {
                    "实时语音左右推进：左 ${left.signedPercentText()}，右 ${right.signedPercentText()}，航向锁定保持"
                } else {
                    "实时语音左右推进：左 ${left.signedPercentText()}，右 ${right.signedPercentText()}，按 App 普通推进下发"
                },
            )
        }
        sendCurrentCommand()
        event.durationMs?.let {
            scheduleRealtimeVoiceNeutral(it, "实时语音限幅推进已到期，回空挡")
        }
        return resultText
    }

    private fun executeRealtimeHeadingTarget(event: RealtimeVoiceControlEvent.AdjustHeadingTarget): String {
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", "adjust_heading_target：不发送")
        }
        if (!state.armed) {
            return rejectRealtimeVoiceEvent("语音不能解锁，请先手动解锁", "adjust_heading_target：不发送")
        }
        val headingLockWasActive = isHeadingLockActiveForState(state)
        val deltaDegrees = correctedRealtimeHeadingDelta(event)
        val direction = if (deltaDegrees < 0) TurnDirection.Left else TurnDirection.Right
        val angleDegrees = kotlin.math.abs(deltaDegrees).coerceIn(1, 90)
        clearActiveTurnCommand()
        if (headingLockWasActive) {
            clearRealtimeVoiceActionTimeout()
        }
        val runtimeCommand = prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.Voice,
                mode = ControlCommandMode.TurnAngle,
                turnDirection = direction,
                turnAngleDegrees = angleDegrees,
                turnRequestId = 1,
            ),
            allocateTurnRequestId = true,
        )
        if (!headingLockWasActive) {
            clearActiveHeadingLockCommand()
        }
        startAppTurn(runtimeCommand)
        val commandLine = formatCommandLine(runtimeCommand)
        val directionText = if (direction == TurnDirection.Left) "左" else "右"
        val resultText = if (headingLockWasActive) {
            "已保持航向锁定，交给主控${directionText}转 $angleDegrees 度"
        } else {
            "已交给主控${directionText}转 $angleDegrees 度"
        }
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = if (headingLockWasActive) it.leftThrottlePercent else 0,
                rightThrottlePercent = if (headingLockWasActive) it.rightThrottlePercent else 0,
                commandSource = CommandSource.Voice,
                headingLockEnabled = headingLockWasActive,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：已调整航向目标",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = if (headingLockWasActive) {
                    "实时语音相对转向：锁航保持，主控执行${directionText}转 $angleDegrees°"
                } else {
                    "实时语音相对转向：主控执行${directionText}转 $angleDegrees°"
                },
            )
        }
        sendCurrentCommand()
        return resultText
    }

    private fun correctedRealtimeHeadingDelta(event: RealtimeVoiceControlEvent.AdjustHeadingTarget): Int {
        val reason = event.reason
        val requested = event.deltaDegrees.coerceIn(-90, 90)
        return when {
            reason.contains("左") && requested > 0 -> -requested
            reason.contains("右") && requested < 0 -> -requested
            else -> requested
        }.coerceIn(-90, 90)
    }

    private fun executeRealtimeSetHeadingTarget(event: RealtimeVoiceControlEvent.SetHeadingTarget): String {
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            return rejectRealtimeVoiceEvent("未连接主控", "set_heading_target：不发送")
        }
        if (!state.armed) {
            return rejectRealtimeVoiceEvent("语音不能解锁，请先手动解锁", "set_heading_target：不发送")
        }
        if (currentHeadingForLockOrNull() == null) {
            return rejectRealtimeVoiceEvent(headingSourceUnavailableText(), "set_heading_target：不发送")
        }

        clearRealtimeVoiceActionTimeout()
        clearActiveTurnCommand()
        clearAutoNavigationExecution("自动导航已取消")
        val basePercent = coerceCommandPercentForSource(event.basePowerPercent, CommandSource.Voice)
        val targetHeading = normalizeCompassDegrees(event.targetHeadingDegrees.toFloat())
        val runtimeCommand = prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.Voice,
                mode = ControlCommandMode.HeadingLock,
                headingLockEnabled = true,
                headingLockRequestId = 1,
                headingLockBaseThrottlePercent = basePercent,
            ),
            allocateHeadingLockRequestId = true,
        )
        startAppHeadingLock(runtimeCommand, targetHeading)
        val commandLine = formatCommandLine(runtimeCommand)
        val resultText = "已进入航向锁定，目标 ${targetHeading.roundToInt()} 度"
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = basePercent,
                rightThrottlePercent = basePercent,
                commandSource = CommandSource.Voice,
                headingLockEnabled = true,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                voiceResultMessage = "实时语音：已设置目标航向",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = "实时语音目标航向 ${targetHeading.roundToInt()}°，航向锁定保持",
            )
        }
        sendCurrentCommand()
        return resultText
    }

    private fun executeRealtimeCancelHeadingLock(event: RealtimeVoiceControlEvent.CancelHeadingLock): String {
        clearRealtimeVoiceActionTimeout()
        clearActiveHeadingLockCommand()
        val resultText = "已取消航向锁定"
        mutableUiState.update {
            it.copy(
                headingLockEnabled = false,
                voiceResultMessage = "实时语音：已取消航向锁定",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = "cancel_heading_lock：不解锁推进",
                statusMessage = "实时语音取消航向锁定：${event.reason}",
            )
        }
        sendCurrentCommand()
        return resultText
    }

    private fun rejectRealtimeVoiceEvent(reason: String, commandLine: String): String {
        val resultText = "拒绝执行：$reason"
        mutableUiState.update {
            it.copy(
                voiceResultMessage = "实时语音事件拒绝：$reason",
                realtimeVoiceReply = resultText,
                voiceCommandPreview = commandLine,
                statusMessage = "实时语音事件拒绝：$reason",
            )
        }
        return resultText
    }

    private fun scheduleRealtimeVoiceNeutral(durationMs: Int, message: String) {
        val token = ++realtimeVoiceActionToken
        realtimeVoiceActionTimeoutJob?.cancel()
        realtimeVoiceActionTimeoutJob = viewModelScope.launch {
            delay(durationMs.toLong())
            if (token != realtimeVoiceActionToken) {
                return@launch
            }
            if (mutableUiState.value.commandSource != CommandSource.Voice) {
                val currentState = mutableUiState.value
                if (!isHeadingLockActiveForState(currentState)) {
                    return@launch
                }
            }
            val headingLockKept = updateActiveHeadingLockBaseFromThrottle(
                leftPercent = 0,
                rightPercent = 0,
                source = CommandSource.App,
            )
            if (!headingLockKept) {
                clearAutonomousCommands()
            }
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = if (headingLockKept) CommandSource.App else CommandSource.Voice,
                    headingLockEnabled = headingLockKept,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = message,
                )
            }
            sendCurrentCommand()
        }
    }

    private fun clearRealtimeVoiceActionTimeout() {
        realtimeVoiceActionToken++
        realtimeVoiceActionTimeoutJob?.cancel()
        realtimeVoiceActionTimeoutJob = null
    }

    private fun previewVoiceEvaluation(evaluation: VoiceCommandEvaluation): VoicePreview {
        return when (val result = evaluation.result) {
            is VoiceParseResult.Rejected -> VoicePreview(
                message = result.reason,
                candidateLine = formatCandidatePreview(evaluation),
                commandLine = "不发送",
            )
            is VoiceParseResult.Accepted -> VoicePreview(
                message = "已识别：${result.label}",
                candidateLine = formatCandidatePreview(evaluation),
                commandLine = result.command?.let { formatCommandLine(prepareVoicePreviewCommand(it)) }
                    ?: voiceActionPreviewText(result.action),
            )
        }
    }

    private fun prepareVoicePreviewCommand(command: ControlCommand): ControlCommand {
        return when {
            shouldApplyThrottleToHeadingLock(command, mutableUiState.value) -> {
                prepareHeadingLockBaseCommandFromThrottle(command)
            }
            else -> prepareRuntimeCommand(command)
        }
    }

    private fun shouldApplyThrottleToHeadingLock(
        command: ControlCommand,
        state: ControlUiState,
    ): Boolean {
        return command.mode == ControlCommandMode.Throttle &&
            command.armed &&
            command.source == CommandSource.Voice &&
            isHeadingLockActiveForState(state)
    }

    private fun isHeadingLockActiveForState(state: ControlUiState): Boolean {
        return state.headingLockEnabled && activeHeadingLockCommand?.headingLockEnabled == true
    }

    private fun prepareHeadingLockBaseCommandFromThrottle(command: ControlCommand): ControlCommand {
        val activeCommand = activeHeadingLockCommand
            ?: return prepareRuntimeCommand(command)
        val settings = mutableSettingsState.value
        val basePercent = ((command.leftThrottlePercent + command.rightThrottlePercent) / 2)
            .coerceIn(-100, 100)
        return activeCommand.copy(
            source = CommandSource.Voice,
            headingLockBaseThrottlePercent = coerceHeadingLockBasePercent(
                percent = basePercent,
                source = CommandSource.Voice,
            ),
            headingLockToleranceDegrees = settings.headingLockToleranceDegrees,
            headingLockFullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
            headingLockNeutralPivotMinDifferencePercent = settings.headingLockNeutralPivotMinDifferencePercent,
            headingLockNeutralPivotMaxDifferencePercent = settings.headingLockNeutralPivotMaxDifferencePercent,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun formatCandidatePreview(evaluation: VoiceCommandEvaluation): String {
        if (evaluation.candidates.isEmpty()) {
            return "无候选"
        }
        return evaluation.candidates
            .take(3)
            .joinToString(separator = "\n") { candidate ->
                val commandLine = candidate.command
                    ?.let { formatCommandLine(prepareVoicePreviewCommand(it)) }
                    ?: voiceActionPreviewText(candidate.action)
                "${candidate.label} ${candidate.score}%（匹配：${candidate.matchedPhrase}）\n$commandLine"
            }
    }

    private fun voiceActionPreviewText(action: VoiceCommandAction): String {
        return when (action) {
            VoiceCommandAction.Control -> "不发送"
            VoiceCommandAction.EnableVoiceControl,
            VoiceCommandAction.DisableVoiceControl -> "本地声控状态切换"
            VoiceCommandAction.FineTuneFaster -> "当前目标 +${mutableSettingsState.value.fineTuneStepPercent}%"
            VoiceCommandAction.FineTuneSlower -> "当前目标 -${mutableSettingsState.value.fineTuneStepPercent}%"
        }
    }

    private fun shouldSuppressRepeatedVoiceRecognition(evaluation: VoiceCommandEvaluation): Boolean {
        val result = evaluation.result as? VoiceParseResult.Accepted ?: return false
        val now = System.currentTimeMillis()
        val key = "${result.label}|${result.action}"
        val repeated = key == lastAcceptedVoiceCommandKey &&
            now - lastAcceptedVoiceCommandAtMs < VOICE_COMMAND_DUPLICATE_SUPPRESSION_MS
        if (repeated) {
            Log.i(TAG, "忽略重复语音命令：${result.label}")
            return true
        }
        lastAcceptedVoiceCommandKey = key
        lastAcceptedVoiceCommandAtMs = now
        return false
    }

    private fun commandWithConfiguredVoiceGear(
        result: VoiceParseResult.Accepted,
        command: ControlCommand,
    ): ControlCommand {
        val gear = voiceGearFor(result) ?: return command
        val percent = coerceCommandPercentForSource(
            percent = gearPercent(gear),
            source = CommandSource.Voice,
        )
        return command.copy(
            leftThrottlePercent = percent,
            rightThrottlePercent = percent,
        )
    }

    private fun voiceGearFor(result: VoiceParseResult.Accepted): ThrottleGear? {
        return when (result.label) {
            "空档" -> ThrottleGear.Neutral
            "前进一档" -> ThrottleGear.Forward1
            "前进二档" -> ThrottleGear.Forward2
            "前进三档" -> ThrottleGear.Forward3
            "前进四档" -> ThrottleGear.Forward4
            "后退一档" -> ThrottleGear.Reverse1
            else -> null
        }
    }

    private fun executeAcceptedVoiceCommand(
        result: VoiceParseResult.Accepted,
        candidateLine: String,
    ) {
        when (result.action) {
            VoiceCommandAction.EnableVoiceControl -> {
                mutableUiState.update {
                    it.copy(
                        voiceControlEnabled = true,
                        voiceAsrState = VoiceAsrState.Starting,
                        voiceAsrStatus = "Qwen ASR：初始化模型",
                        voiceResultMessage = "声控已开启",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = "本地声控状态切换",
                        statusMessage = "语音控制：已开启声控",
                    )
                }
                return
            }
            VoiceCommandAction.DisableVoiceControl -> {
                mutableUiState.update {
                    it.copy(
                        voiceControlEnabled = false,
                        voiceAsrState = VoiceAsrState.Stopped,
                        voiceAsrStatus = "Qwen ASR：已暂停",
                        voiceResultMessage = "已停止声控",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = "本地声控状态切换：不改变推进或锁航",
                        statusMessage = "语音控制：已停止声控，推进和航向锁定保持不变",
                    )
                }
                return
            }
            VoiceCommandAction.Control -> {
                if (!mutableUiState.value.voiceControlEnabled) {
                    mutableUiState.update {
                        it.copy(
                            voiceResultMessage = "声控已停止，已忽略命令；请说“开始声控”恢复",
                            voiceCandidatePreview = candidateLine,
                            voiceCommandPreview = "不发送",
                            statusMessage = "语音控制已停止，忽略非开始声控指令",
                        )
                    }
                    return
                }
            }
            VoiceCommandAction.FineTuneFaster,
            VoiceCommandAction.FineTuneSlower -> {
                if (!mutableUiState.value.voiceControlEnabled) {
                    mutableUiState.update {
                        it.copy(
                            voiceResultMessage = "声控已停止，已忽略命令；请说“开始声控”恢复",
                            voiceCandidatePreview = candidateLine,
                            voiceCommandPreview = "不发送",
                            statusMessage = "语音控制已停止，忽略非开始声控指令",
                        )
                    }
                    return
                }
                executeVoiceFineTune(
                    result = result,
                    candidateLine = candidateLine,
                    increase = result.action == VoiceCommandAction.FineTuneFaster,
                )
                return
            }
        }

        val command = result.command?.let { commandWithConfiguredVoiceGear(result, it) } ?: return
        val voiceGear = voiceGearFor(result)
        val state = mutableUiState.value
        val throttleWithinHeadingLock = shouldApplyThrottleToHeadingLock(command, state)
        val runtimeCommand = if (throttleWithinHeadingLock) {
            prepareHeadingLockBaseCommandFromThrottle(command)
        } else {
            prepareRuntimeCommand(
                command = command,
                allocateTurnRequestId = command.mode == ControlCommandMode.TurnAngle,
                allocateHeadingLockRequestId = command.mode == ControlCommandMode.HeadingLock && command.headingLockEnabled,
            )
        }
        val commandLine = formatCommandLine(runtimeCommand)

        if (command.armed && state.connectionState != ConnectionState.Connected) {
            mutableUiState.update {
                it.copy(
                    voiceResultMessage = "请先连接主控",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：未连接主控",
                )
            }
            return
        }

        if (command.armed && !state.armed) {
            mutableUiState.update {
                it.copy(
                    voiceResultMessage = "语音不能解锁，请先手动解锁",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：系统未解锁",
                )
            }
            return
        }

        if (throttleWithinHeadingLock) {
            clearActiveTurnCommand()
            activeHeadingLockStartPending = true
            activeHeadingLockCommand = runtimeCommand
            val basePercent = ((command.leftThrottlePercent + command.rightThrottlePercent) / 2)
                .coerceIn(-100, 100)
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = basePercent,
                    rightThrottlePercent = basePercent,
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = true,
                    selectedGear = voiceGear ?: ThrottleGear.Neutral,
                    throttleTrimPercent = voiceGear?.let { gear ->
                        fineTuneTrimFor(gear, command.leftThrottlePercent, command.rightThrottlePercent)
                    } ?: 0,
                    voiceResultMessage = "已执行：${result.label}",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音控制：锁航保持，基础油门 ${basePercent.signedPercentText()}",
                )
            }
            sendCurrentCommand {
                speakVoiceCommandReply(result, command)
            }
            return
        }

        if (command.mode == ControlCommandMode.TurnAngle) {
            if (!isHeadingLockActiveForState(state)) {
                clearActiveHeadingLockCommand()
            }
            startAppTurn(runtimeCommand)
            mutableUiState.update {
                it.copy(
                        commandSource = CommandSource.Voice,
                        headingLockEnabled = isHeadingLockActiveForState(state),
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        voiceResultMessage = "已执行：${result.label}",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音控制：${result.label}，已下发给主控执行",
                )
            }
            sendCurrentCommand {
                speakVoiceCommandReply(result, runtimeCommand)
            }
            return
        }

        if (command.mode == ControlCommandMode.HeadingLock) {
            clearActiveTurnCommand()
            if (command.headingLockEnabled) {
                val currentHeading = currentHeadingForLockOrNull()
                if (currentHeading == null) {
                    val unavailableText = headingSourceUnavailableText()
                    mutableUiState.update {
                        it.copy(
                            voiceResultMessage = unavailableText,
                            voiceCandidatePreview = candidateLine,
                            voiceCommandPreview = commandLine,
                            statusMessage = "语音锁航拒绝：$unavailableText",
                        )
                    }
                    return
                }
                startAppHeadingLock(runtimeCommand, currentHeading)
                mutableUiState.update {
                    it.copy(
                        commandSource = CommandSource.Voice,
                        headingLockEnabled = true,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        voiceResultMessage = "已执行：${result.label}",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = commandLine,
                        statusMessage = "语音控制：${result.label}，目标 ${currentHeading.roundToInt()}°",
                    )
                }
            } else {
                clearActiveHeadingLockCommand()
                mutableUiState.update {
                    it.copy(
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = false,
                    throttleTrimPercent = 0,
                    voiceResultMessage = "已执行：${result.label}",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = commandLine,
                        statusMessage = "语音控制：航向锁定已取消",
                    )
                }
            }
            sendCurrentCommand {
                speakVoiceCommandReply(result, runtimeCommand)
            }
            return
        }

        clearAutonomousCommands()
        mutableUiState.update {
            if (command.armed) {
                it.copy(
                    leftThrottlePercent = command.leftThrottlePercent,
                    rightThrottlePercent = command.rightThrottlePercent,
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = false,
                    selectedGear = voiceGear ?: ThrottleGear.Neutral,
                    throttleTrimPercent = voiceGear?.let { gear ->
                        fineTuneTrimFor(gear, command.leftThrottlePercent, command.rightThrottlePercent)
                    } ?: 0,
                    voiceResultMessage = "已执行：${result.label}",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音控制：${result.label}",
                )
            } else {
                it.copy(
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = false,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        voiceControlEnabled = result.action != VoiceCommandAction.DisableVoiceControl,
                        voiceAsrState = if (result.action == VoiceCommandAction.DisableVoiceControl) {
                            VoiceAsrState.Stopped
                        } else {
                            it.voiceAsrState
                        },
                        voiceAsrStatus = if (result.action == VoiceCommandAction.DisableVoiceControl) {
                            "Qwen ASR：已暂停"
                        } else {
                            it.voiceAsrStatus
                        },
                        voiceResultMessage = if (result.action == VoiceCommandAction.DisableVoiceControl) {
                        "已停止声控，推进输出回空挡"
                    } else {
                        "已执行：${result.label}"
                    },
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = if (result.action == VoiceCommandAction.DisableVoiceControl) {
                        "语音控制：已停止声控并锁定"
                    } else {
                        "语音控制：停止并锁定"
                    },
                )
            }
        }
        sendCurrentCommand {
            speakVoiceCommandReply(result, runtimeCommand)
        }
    }

    private fun executeVoiceFineTune(
        result: VoiceParseResult.Accepted,
        candidateLine: String,
        increase: Boolean,
    ) {
        val commandLine = previewFineTuneCommandLine(increase, CommandSource.Voice)
        val state = mutableUiState.value
        if (state.connectionState != ConnectionState.Connected) {
            mutableUiState.update {
                it.copy(
                    voiceResultMessage = "请先连接主控",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：未连接主控",
                )
            }
            return
        }
        if (!state.armed) {
            mutableUiState.update {
                it.copy(
                    voiceResultMessage = "语音不能解锁，请先手动解锁",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：系统未解锁",
                )
            }
            return
        }
        applyThrottleFineTune(
            increase = increase,
            source = CommandSource.Voice,
            voiceResult = result,
            candidateLine = candidateLine,
            commandLine = commandLine,
        )
    }

    private fun applyThrottleFineTune(
        increase: Boolean,
        source: CommandSource,
        voiceResult: VoiceParseResult.Accepted? = null,
        candidateLine: String? = null,
        commandLine: String? = null,
    ): Boolean {
        val state = mutableUiState.value
        val step = mutableSettingsState.value.fineTuneStepPercent
        val actionText = if (increase) "加速" else "减速"
        if (state.connectionState != ConnectionState.Connected || !state.armed) {
            mutableUiState.update {
                it.copy(statusMessage = "微调${actionText}拒绝：请先连接并手动解锁")
            }
            return false
        }

        val target = nextFineTuneTarget(increase, source)
        val updatedHeadingLockBase = updateActiveHeadingLockBaseFromThrottle(
            leftPercent = target.leftPercent,
            rightPercent = target.rightPercent,
            source = source,
        )
        if (!updatedHeadingLockBase) {
            clearAutonomousCommands()
        }

        val wireLine = commandLine ?: previewFineTuneCommandLine(increase, source)
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = target.leftPercent,
                rightThrottlePercent = target.rightPercent,
                commandSource = source,
                headingLockEnabled = if (updatedHeadingLockBase) true else false,
                throttleTrimPercent = target.trimPercent,
                voiceResultMessage = voiceResult?.let { "已执行：${it.label}" } ?: it.voiceResultMessage,
                voiceCandidatePreview = candidateLine ?: it.voiceCandidatePreview,
                voiceCommandPreview = if (voiceResult != null) wireLine else it.voiceCommandPreview,
                statusMessage = if (updatedHeadingLockBase) {
                    "微调${actionText} ${step}%：航向锁定保持，基础油门 ${target.averagePercent.signedPercentText()}"
                } else {
                    "微调${actionText} ${step}%：左 ${target.leftPercent.signedPercentText()}，右 ${target.rightPercent.signedPercentText()}"
                },
            )
        }
        sendCurrentCommand {
            if (voiceResult != null) {
                speakVoiceReply(if (increase) "已快点" else "已慢点")
            }
        }
        return true
    }

    private fun sendCurrentCommand(onSent: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (sendCommand(buildCurrentCommand())) {
                onSent?.invoke()
            }
        }
    }

    private fun sendOneShotCommand(command: ControlCommand, onSent: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (sendCommand(command)) {
                onSent?.invoke()
            }
        }
    }

    private fun sendIdle() {
        viewModelScope.launch {
            sendCommand(ControlCommand.Idle)
        }
    }

    private fun escDirectionConfigLine(settings: SettingsUiState): String {
        return "ESC_CFG;LREV=${if (settings.leftEscReversed) 1 else 0};RREV=${if (settings.rightEscReversed) 1 else 0}"
    }

    private suspend fun sendEscDirectionConfig(
        nextTransport: ControlTransport,
        settings: SettingsUiState,
    ): Boolean {
        val line = escDirectionConfigLine(settings)
        return runCatching {
            nextTransport.sendRawLine(line)
        }.onSuccess {
            mutableSettingsState.update { it.copy(message = "ESC 安装方向配置已同步到主控") }
        }.onFailure { error ->
            mutableSettingsState.update {
                it.copy(message = "ESC 方向配置下发失败：${error.message ?: "未知错误"}")
            }
        }.isSuccess
    }

    private fun updateEscDirectionConfig(
        leftReversed: Boolean? = null,
        rightReversed: Boolean? = null,
        neutralMessage: String,
    ) {
        val nextTransport = transport
        if (
            nextTransport == null ||
            mutableUiState.value.connectionState != ConnectionState.Connected ||
            esp32OtaExclusive
        ) {
            mutableSettingsState.update { it.copy(message = "请先连接主控，再修改 ESC 安装方向") }
            return
        }
        val currentSettings = mutableSettingsState.value
        val nextSettings = currentSettings.copy(
            leftEscReversed = leftReversed ?: currentSettings.leftEscReversed,
            rightEscReversed = rightReversed ?: currentSettings.rightEscReversed,
        )
        if (
            nextSettings.leftEscReversed == currentSettings.leftEscReversed &&
            nextSettings.rightEscReversed == currentSettings.rightEscReversed
        ) {
            return
        }

        clearAutonomousCommands()
        lockForDirectionChange(neutralMessage) {
            viewModelScope.launch {
                if (sendEscDirectionConfig(nextTransport, nextSettings)) {
                    preferences.edit()
                        .putBoolean(KEY_LEFT_ESC_REVERSED, nextSettings.leftEscReversed)
                        .putBoolean(KEY_RIGHT_ESC_REVERSED, nextSettings.rightEscReversed)
                        .apply()
                    mutableSettingsState.update {
                        it.copy(
                            leftEscReversed = nextSettings.leftEscReversed,
                            rightEscReversed = nextSettings.rightEscReversed,
                            message = "ESC 安装方向配置已同步到主控",
                        )
                    }
                }
            }
        }
    }

    private fun sendMagCalibrationAction(
        action: String,
        requestedMessage: String,
        failurePrefix: String,
        forceLockedNeutral: Boolean,
    ) {
        val nextTransport = transport
        if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "$failurePrefix：未连接主控") }
            return
        }
        if (esp32OtaExclusive) {
            mutableUiState.update { it.copy(statusMessage = "$failurePrefix：主控 OTA 进行中") }
            return
        }

        if (forceLockedNeutral) {
            clearAutonomousCommands()
            mutableUiState.update {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = requestedMessage,
                )
            }
        } else {
            mutableUiState.update { it.copy(statusMessage = requestedMessage) }
        }

        viewModelScope.launch {
            runCatching {
                nextTransport.sendRawLine("MAG_CAL;ACTION=$action")
                if (forceLockedNeutral) {
                    nextTransport.send(ControlCommand.Idle)
                }
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(statusMessage = "$failurePrefix：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun sendYbCalibrationAction(
        target: String,
        action: String,
        requestedMessage: String,
        failurePrefix: String,
    ) {
        val nextTransport = transport
        if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "$failurePrefix：未连接主控") }
            return
        }
        if (esp32OtaExclusive) {
            mutableUiState.update { it.copy(statusMessage = "$failurePrefix：主控 OTA 进行中") }
            return
        }

        clearAutonomousCommands()
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = requestedMessage,
            )
        }

        viewModelScope.launch {
            runCatching {
                nextTransport.sendRawLine("YB_CAL;TARGET=$target;ACTION=$action")
                nextTransport.send(ControlCommand.Idle)
            }.onFailure { error ->
                mutableUiState.update {
                    it.copy(statusMessage = "$failurePrefix：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun ControlCommand.withRuntimeHeadingSource(): ControlCommand {
        return this
    }

    private fun currentPhoneHeadingDegreesOrNull(): Float? {
        val state = mutableUiState.value
        val ageMs = System.currentTimeMillis() - phoneHeadingLastUpdateMs
        return state.phoneHeadingDegrees
            ?.takeIf { state.phoneHeadingAvailable && ageMs <= PHONE_HEADING_STALE_MS }
            ?.let(::normalizeCompassDegrees)
    }

    private fun currentYbImuHeadingDegreesOrNull(): Float? {
        val telemetry = currentYbImuHeadingTelemetryOrNull() ?: return null
        return ybImuHeadingDegrees(
            telemetry = telemetry,
            magneticDeclinationDegrees = currentMagneticDeclinationDegreesOrNull() ?: 0f,
        )
    }

    private fun currentMagneticDeclinationDegreesOrNull(): Float? {
        return mutableUiState.value.magneticDeclinationDegrees
    }

    private fun magneticDeclinationDegreesForSource(source: NavigationGpsSource): Float? {
        return when (source) {
            NavigationGpsSource.Esp32 -> magneticDeclinationDegreesFromTelemetry(mutableUiState.value.telemetry)
            NavigationGpsSource.Phone -> {
                val phoneGps = mutableUiState.value.phoneGps
                val latitude = phoneGps.latitude ?: return null
                val longitude = phoneGps.longitude ?: return null
                magneticDeclinationDegrees(latitude, longitude, null)
            }
        }
    }

    private fun currentYbImuHeadingTelemetryOrNull(): com.smartsup.controller.model.Telemetry? {
        val state = mutableUiState.value
        val ageMs = System.currentTimeMillis() - ybImuHeadingLastUpdateMs
        return state.telemetry.takeIf {
            val fields = it.statusFields
            val firmwareAgeMs = fields["YBAGE"]?.toLongOrNull()
            it.ybImuAvailable == true &&
                it.ybHeadingDegrees != null &&
                fields["HSRC"] == "YBIMU" &&
                fields["YBINIT"] != "0" &&
                (firmwareAgeMs == null || firmwareAgeMs <= YB_IMU_HEADING_STALE_MS) &&
                ageMs <= YB_IMU_HEADING_STALE_MS
        }
    }

    private fun currentHeadingForLockOrNull(): Float? {
        return currentYbImuHeadingDegreesOrNull()
    }

    private fun currentFirmwareHeadingForLockOrNull(): Float? {
        val telemetry = currentYbImuHeadingTelemetryOrNull() ?: return null
        return ybImuControllerHeadingDegrees(telemetry)
    }

    private fun headingSourceUnavailableText(): String {
        return "主控 IMU 航向暂无有效读数"
    }

    private fun updatePhoneHeadingSensorRegistration() {
        startPhoneHeadingSensor()
    }

    private fun updatePhoneGpsRegistration() {
        if (mutableSettingsState.value.navigationGpsSource == NavigationGpsSource.Phone) {
            startPhoneGps()
        } else {
            stopPhoneGps(clearReading = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneGps() {
        if (phoneGpsRegistered) {
            return
        }
        val manager = locationManager
        if (manager == null) {
            mutableUiState.update {
                it.copy(phoneGps = it.phoneGps.copy(message = "当前手机不支持系统定位服务"))
            }
            return
        }
        if (!hasPhoneLocationPermission()) {
            mutableUiState.update {
                it.copy(phoneGps = it.phoneGps.copy(message = "缺少手机定位权限"))
            }
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            mutableUiState.update {
                it.copy(phoneGps = it.phoneGps.copy(message = "手机定位服务未开启"))
            }
            return
        }

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    PHONE_GPS_MIN_UPDATE_MS,
                    PHONE_GPS_MIN_UPDATE_METERS,
                    phoneGpsListener,
                    Looper.getMainLooper(),
                )
            }
        }
        phoneGpsRegistered = true
        providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let(::handlePhoneGpsLocation)
        mutableUiState.update {
            if (it.phoneGps.hasLocation) {
                it
            } else {
                it.copy(phoneGps = it.phoneGps.copy(message = "等待手机 GPS 定位"))
            }
        }
    }

    private fun stopPhoneGps(clearReading: Boolean) {
        if (phoneGpsRegistered) {
            locationManager?.removeUpdates(phoneGpsListener)
            phoneGpsRegistered = false
        }
        if (clearReading) {
            phoneGpsLastUpdateMs = 0L
            mutableUiState.update {
                it.copy(phoneGps = com.smartsup.controller.model.PhoneGpsState())
            }
        }
    }

    private fun hasPhoneLocationPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handlePhoneGpsLocation(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return
        }
        val nowMs = System.currentTimeMillis()
        phoneGpsLastUpdateMs = nowMs
        val accuracy = location.takeIf { it.hasAccuracy() }?.accuracy
        val message = if (accuracy != null && accuracy > PHONE_GPS_MAX_ACCURACY_METERS) {
            "手机 GPS 精度较差：${accuracy.roundToInt()}m"
        } else {
            "手机 GPS 已定位"
        }
        mutableUiState.update {
            it.copy(
                phoneGps = it.phoneGps.copy(
                    latitude = latitude,
                    longitude = longitude,
                    speedKmh = location.takeIf { value -> value.hasSpeed() }?.speed?.times(3.6),
                    accuracyMeters = accuracy,
                    provider = location.provider.orEmpty(),
                    updatedAtMs = nowMs,
                    message = message,
                ),
                magneticDeclinationDegrees = if (mutableSettingsState.value.navigationGpsSource == NavigationGpsSource.Phone) {
                    magneticDeclinationDegrees(
                        latitude = latitude,
                        longitude = longitude,
                        altitudeMeters = location.altitude.takeIf { value -> location.hasAltitude() },
                    )
                } else {
                    it.magneticDeclinationDegrees
                },
            )
        }
    }

    private fun startPhoneHeadingSensor() {
        if (phoneHeadingSensorRegistered) {
            return
        }
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sensor == null) {
            mutableUiState.update {
                it.copy(
                    phoneHeadingAvailable = false,
                    phoneHeadingSensorName = "无可用指南针传感器",
                    statusMessage = "手机没有可用的指南针航向传感器",
                )
            }
            return
        }
        phoneHeadingSensorRegistered = sensorManager.registerListener(
            phoneHeadingListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        mutableUiState.update {
            it.copy(
                phoneHeadingAvailable = false,
                phoneHeadingSensorName = sensor.name,
            )
        }
    }

    private fun stopPhoneHeadingSensor(clearReading: Boolean) {
        if (phoneHeadingSensorRegistered) {
            sensorManager?.unregisterListener(phoneHeadingListener)
            phoneHeadingSensorRegistered = false
        }
        if (clearReading) {
            phoneHeadingLastUpdateMs = 0L
            mutableUiState.update {
                it.copy(
                    phoneHeadingDegrees = null,
                    phoneHeadingAvailable = false,
                    phoneHeadingSensorName = "",
                )
            }
        }
    }

    private fun normalizeCompassDegrees(degrees: Float): Float {
        val normalized = degrees % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    private fun normalizeSignedCompassOffset(degrees: Float): Float {
        val normalized = normalizeCompassDegrees(degrees)
        return if (normalized > 180f) normalized - 360f else normalized
    }

    private fun uploadEsp32FirmwareBytes(
        firmwareBytes: ByteArray,
        sourceLabel: String,
        targetVersion: String?,
    ) {
        viewModelScope.launch {
            val nextTransport = transport
            if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
                mutableUpdateState.update { it.copy(message = "请先连接主控再更新固件") }
                return@launch
            }

            if (firmwareBytes.isEmpty()) {
                mutableUpdateState.update { it.copy(message = "固件文件为空") }
                return@launch
            }

            commandHeartbeatJob?.cancel()
            clearAutonomousCommands()
            esp32OtaExclusive = true
            esp32OtaAccepted = false
            otaReconnectSawDisconnect = false
            pendingEsp32FirmwareVersion = targetVersion
            mutableUiState.update {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "准备更新主控固件，推进输出回空挡",
                )
            }

            runCatching { nextTransport.send(ControlCommand.Idle) }

            val md5Hex = md5Hex(firmwareBytes)
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = true,
                    targetEsp32FirmwareVersion = targetVersion ?: it.targetEsp32FirmwareVersion,
                    message = "正在通过蓝牙发送 $sourceLabel 到主控",
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
                esp32OtaAccepted = true
                otaReconnectSawDisconnect = true
                if (transport === nextTransport) {
                    transport = null
                }
                telemetryJob?.cancel()
                trackLogJob?.cancel()
                trackLogSyncJob?.cancel()
                commandHeartbeatJob?.cancel()
                runCatching { nextTransport.closeSilently() }
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.App,
                        headingLockEnabled = false,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        statusMessage = "主控 OTA 已完成，开始每 3 秒自动重连",
                    )
                }
                startOtaReconnectLoop(forceRestart = true)
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = if (targetVersion == null) {
                            "主控固件校验通过；每 3 秒自动重连确认版本"
                        } else {
                            "主控固件校验通过；每 3 秒自动重连验证 FW=$targetVersion"
                        },
                        progressText = "",
                    )
                }
            }.onFailure { error ->
                esp32OtaExclusive = false
                esp32OtaAccepted = false
                pendingEsp32FirmwareVersion = null
                stopOtaReconnectLoop()
                if (transport === nextTransport) {
                    transport = null
                }
                telemetryJob?.cancel()
                trackLogJob?.cancel()
                trackLogSyncJob?.cancel()
                commandHeartbeatJob?.cancel()
                clearAutonomousCommands()
                runCatching { nextTransport.closeSilently() }
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.App,
                        headingLockEnabled = false,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        statusMessage = "主控 OTA 已中断，蓝牙已断开；等待主控回退后再重连",
                    )
                }
                mutableSettingsState.update {
                    it.copy(message = "主控 OTA 中断，已静默断开蓝牙，避免普通控制命令污染固件流")
                }
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "主控固件更新失败：${error.message ?: "未知错误"}；已断开蓝牙，等待主控 OTA 超时回退后再重连",
                        progressText = "",
                    )
                }
            }
        }
    }

    private fun buildCurrentCommand(): ControlCommand {
        val state = mutableUiState.value
        val settings = mutableSettingsState.value
        if (state.voiceSamplingEnabled) {
            return if (state.armed) {
                neutralArmedCommand(state.commandSource, settings)
            } else {
                ControlCommand.Idle
            }
        }
        pendingControllerAuthorityCommandOrNull(settings)?.let { pendingCommand ->
            return pendingCommand
        }
        val source = state.commandSource
        if (state.canSendThrottle && state.autoNavigation.stationKeepingEnabled) {
            return buildStationKeepingCommand(state, settings)
        }
        if (state.canSendThrottle && state.autoNavigation.trackLineLockEnabled) {
            return buildTrackLineLockCommand(state, settings)
        }
        if (state.canSendThrottle && state.autoNavigation.executing) {
            return buildAutoNavigationCommand(state, settings)
        }
        if (state.canSendThrottle && (activeTurnCommand != null || activeHeadingLockCommand != null)) {
            return buildAppHeadingControlCommand(state, settings)
        }
        return if (state.canSendThrottle) {
            val leftPercent = coerceCommandPercentForSource(
                percent = state.leftThrottlePercent,
                source = source,
            )
            val rightPercent = coerceCommandPercentForSource(
                percent = state.rightThrottlePercent,
                source = source,
            )
            ControlCommand(
                leftThrottlePercent = leftPercent,
                rightThrottlePercent = rightPercent,
                armed = true,
                source = source,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        } else {
            ControlCommand(
                source = source,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        }
    }

    private fun pendingControllerAuthorityCommandOrNull(settings: SettingsUiState): ControlCommand? {
        val requestedArmed = pendingControllerArmedRequest ?: return null
        if (System.currentTimeMillis() > pendingControllerArmedRequestUntilMs) {
            pendingControllerArmedRequest = null
            pendingControllerArmedRequestUntilMs = 0L
            return null
        }
        return ControlCommand(
            armed = requestedArmed,
            source = CommandSource.App,
            leftThrottlePercent = 0,
            rightThrottlePercent = 0,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun buildAutoNavigationCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        val autoState = state.autoNavigation
        val route = autoState.routes.firstOrNull { it.id == autoState.executingRouteId }
        if (route == null || route.points.size < 2) {
            failAutoNavigation("自动导航停止：路线无效")
            return neutralArmedCommand(CommandSource.App, settings)
        }
        val currentPoint = currentAutoNavigationPointOrNull(settings.autoNavigationGpsJumpResetMeters.toDouble())
        if (currentPoint == null || gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES) {
            val message = "自动导航暂停：GPS 定位不足"
            if (autoNavigationGpsInvalidSinceMs == 0L) {
                autoNavigationGpsInvalidSinceMs = System.currentTimeMillis()
            }
            return pauseAutoNavigationCommand(message, settings)
        }
        autoNavigationGpsInvalidSinceMs = 0L
        val currentHeading = currentHeadingForLockOrNull()
        autoNavigationHeadingInvalidSinceMs = if (currentHeading == null) {
            if (autoNavigationHeadingInvalidSinceMs == 0L) System.currentTimeMillis() else autoNavigationHeadingInvalidSinceMs
        } else {
            0L
        }

        var targetIndex = autoState.targetPointIndex.coerceIn(0, route.points.lastIndex)
        var targetPoint = route.points[targetIndex]
        var distanceMeters = distanceMeters(currentPoint, targetPoint)
        while (distanceMeters <= AUTO_NAVIGATION_ARRIVAL_RADIUS_METERS && targetIndex < route.points.lastIndex) {
            targetIndex += 1
            targetPoint = route.points[targetIndex]
            distanceMeters = distanceMeters(currentPoint, targetPoint)
        }
        if (distanceMeters <= AUTO_NAVIGATION_ARRIVAL_RADIUS_METERS && targetIndex == route.points.lastIndex) {
            clearAutoNavigationExecution("自动导航完成")
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "自动导航完成，已回空挡，主控保持当前解锁状态",
                )
            }
            return neutralArmedCommand(CommandSource.App, settings)
        }

        val targetHeading = normalizeCompassDegrees(bearingBetween(currentPoint, targetPoint).toFloat())
        val errorDegrees = currentHeading?.let { shortestCompassError(targetHeading, it) }
        val gearIndex = autoState.gearIndex.coerceIn(0, AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
        val outputLimitPercent = minOf(
            settings.maxThrottlePercent,
            AUTO_NAVIGATION_MAX_OUTPUT_PERCENT,
        ).coerceIn(0, 100)
        val basePercent = AUTO_NAVIGATION_GEAR_PERCENTS[gearIndex].coerceAtMost(outputLimitPercent)
        val statusFields = state.telemetry.statusFields
        val telemetryLeft = state.telemetry.leftOutputPercent ?: 0
        val telemetryRight = state.telemetry.rightOutputPercent ?: 0

        mutableUiState.update {
            it.copy(
                leftThrottlePercent = basePercent,
                rightThrottlePercent = basePercent,
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                autoNavigation = it.autoNavigation.copy(
                    targetPointIndex = targetIndex,
                    gearIndex = gearIndex,
                    distanceToTargetMeters = distanceMeters,
                    headingErrorDegrees = errorDegrees,
                    leftOutputPercent = telemetryLeft,
                    rightOutputPercent = telemetryRight,
                    message = "自动导航执行中：目标航向 ${targetHeading.roundToInt()}°",
                ),
            )
        }

        return firmwareHeadingIntentCommand(
            targetHeadingDegrees = targetHeading,
            basePercent = basePercent,
            settings = settings,
            fullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
        )
    }

    private fun pauseAutoNavigationCommand(
        message: String,
        settings: SettingsUiState,
    ): ControlCommand {
        clearActiveHeadingLockCommand()
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                autoNavigation = it.autoNavigation.copy(
                    leftOutputPercent = 0,
                    rightOutputPercent = 0,
                    message = message,
                ),
                statusMessage = message,
            )
        }
        return ControlCommand(
            leftThrottlePercent = 0,
            rightThrottlePercent = 0,
            armed = true,
            source = CommandSource.App,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun buildTrackLineLockCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        val autoState = state.autoNavigation
        val origin = autoState.trackLineOrigin
        val lineBearing = autoState.trackLineBearingDegrees
        if (origin == null || lineBearing == null) {
            failAutoNavigation("航迹线锁定停止：目标线无效")
            return neutralArmedCommand(CommandSource.App, settings)
        }
        val currentPoint = currentAutoNavigationPointOrNull(settings.autoNavigationGpsJumpResetMeters.toDouble())
        if (currentPoint == null || gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES) {
            failAutoNavigation("航迹线锁定停止：GPS 定位不足")
            return neutralArmedCommand(CommandSource.App, settings)
        }
        val currentHeading = currentHeadingForLockOrNull()

        val lineError = trackLineError(
            origin = origin,
            bearingDegrees = lineBearing.toDouble(),
            point = currentPoint,
        )
        val crossTrackAbs = abs(lineError.crossTrackMeters)
        if (crossTrackAbs > TRACK_LINE_MAX_CROSS_TRACK_METERS) {
            failAutoNavigation("航迹线锁定停止：偏离航迹 ${crossTrackAbs.roundToInt()}m")
            return neutralArmedCommand(CommandSource.App, settings)
        }

        val targetCorrectionDegrees = Math.toDegrees(
            atan2(-lineError.crossTrackMeters, TRACK_LINE_LOOKAHEAD_METERS),
        ).coerceIn(
            -TRACK_LINE_MAX_CORRECTION_DEGREES.toDouble(),
            TRACK_LINE_MAX_CORRECTION_DEGREES.toDouble(),
        )
        val targetHeading = normalizeCompassDegrees((lineBearing + targetCorrectionDegrees).toFloat())
        val errorDegrees = currentHeading?.let { shortestCompassError(targetHeading, it) }
        val gearIndex = autoState.gearIndex.coerceIn(0, AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
        val outputLimitPercent = minOf(
            settings.maxThrottlePercent,
            AUTO_NAVIGATION_MAX_OUTPUT_PERCENT,
        ).coerceIn(0, 100)
        val basePercent = AUTO_NAVIGATION_GEAR_PERCENTS[gearIndex].coerceAtMost(outputLimitPercent)
        val telemetryLeft = state.telemetry.leftOutputPercent ?: 0
        val telemetryRight = state.telemetry.rightOutputPercent ?: 0

        mutableUiState.update {
            it.copy(
                leftThrottlePercent = basePercent,
                rightThrottlePercent = basePercent,
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = gearIndex,
                    distanceToTargetMeters = crossTrackAbs,
                    headingErrorDegrees = errorDegrees,
                    trackLineTargetHeadingDegrees = targetHeading,
                    trackLineCrossTrackErrorMeters = lineError.crossTrackMeters,
                    trackLineAlongTrackMeters = lineError.alongTrackMeters,
                    leftOutputPercent = telemetryLeft,
                    rightOutputPercent = telemetryRight,
                    message = "航迹线锁定执行中：目标航向 ${targetHeading.roundToInt()}°",
                ),
            )
        }

        return firmwareHeadingIntentCommand(
            targetHeadingDegrees = targetHeading,
            basePercent = basePercent,
            settings = settings,
            fullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
        )
    }

    private fun buildStationKeepingCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        val autoState = state.autoNavigation
        val targetPoint = autoState.stationKeepingTarget
        val targetHeading = autoState.stationKeepingTargetHeadingDegrees
        if (targetPoint == null || targetHeading == null) {
            failAutoNavigation("定点保持停止：目标点无效")
            return neutralArmedCommand(CommandSource.App, settings)
        }
        val fusionSample = stationKeepingFusionSampleOrNull(
            state = state,
            jumpResetMeters = settings.autoNavigationGpsJumpResetMeters.toDouble(),
        )
        if (fusionSample == null || gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES) {
            val message = "定点保持暂停：GPS 定位不足"
            if (autoNavigationGpsInvalidSinceMs == 0L) {
                autoNavigationGpsInvalidSinceMs = System.currentTimeMillis()
            }
            return pauseAutoNavigationCommand(message, settings)
        }
        autoNavigationGpsInvalidSinceMs = 0L
        val currentPoint = fusionSample.filteredPoint
        val currentHeading = currentHeadingForLockOrNull()
        if (currentHeading == null) {
            val message = "定点保持暂停：${headingSourceUnavailableText()}"
            if (autoNavigationHeadingInvalidSinceMs == 0L) {
                autoNavigationHeadingInvalidSinceMs = System.currentTimeMillis()
            }
            return pauseAutoNavigationCommand(message, settings)
        }
        autoNavigationHeadingInvalidSinceMs = 0L

        val stationError = stationKeepingError(
            targetPoint = targetPoint,
            targetHeadingDegrees = targetHeading,
            currentPoint = currentPoint,
        )
        val bearingToTarget = bearingBetween(currentPoint, targetPoint).toFloat()
        val positionActive = when {
            autoState.stationKeepingPositionActive ->
                stationError.distanceMeters > STATION_KEEPING_HOLD_RADIUS_METERS
            else ->
                stationError.distanceMeters >= STATION_KEEPING_REENGAGE_RADIUS_METERS
        }
        val reverseBearingToTarget = normalizeCompassDegrees(bearingToTarget + 180f)
        val forwardHeadingError = abs(shortestCompassError(bearingToTarget, currentHeading))
        val reverseHeadingError = abs(shortestCompassError(reverseBearingToTarget, currentHeading))
        val movingReverse = if (!positionActive) {
            false
        } else if (!autoState.stationKeepingPositionActive) {
            reverseHeadingError < forwardHeadingError
        } else if (autoState.stationKeepingMovingReverse) {
            reverseHeadingError <= forwardHeadingError + STATION_KEEPING_REVERSE_SELECTION_DEADBAND_DEGREES
        } else {
            reverseHeadingError + STATION_KEEPING_REVERSE_SELECTION_DEADBAND_DEGREES < forwardHeadingError
        }
        val finalHeadingError = shortestCompassError(targetHeading, currentHeading)
        val finalHeadingSuppressed = !positionActive &&
            abs(finalHeadingError) > STATION_KEEPING_MAX_FINAL_HEADING_ERROR_DEGREES
        val desiredHeading = when {
            positionActive && movingReverse -> reverseBearingToTarget
            positionActive -> bearingToTarget
            finalHeadingSuppressed -> currentHeading
            else -> targetHeading
        }
        val errorDegrees = shortestCompassError(desiredHeading, currentHeading)
        val absError = abs(errorDegrees)
        val outputLimitPercent = minOf(
            settings.maxThrottlePercent,
            STATION_KEEPING_MAX_OUTPUT_PERCENT,
        ).coerceIn(0, 100)
        val gearIndex = autoState.gearIndex.coerceIn(0, AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
        val requestedBaseMagnitude = if (positionActive && absError <= STATION_KEEPING_GO_HEADING_ERROR_DEGREES) {
            stationKeepingBasePercent(
                distanceMeters = stationError.distanceMeters,
                gearPercent = AUTO_NAVIGATION_GEAR_PERCENTS[gearIndex],
                outputLimitPercent = outputLimitPercent,
            )
        } else {
            0
        }
        val basePercent = if (movingReverse) -requestedBaseMagnitude else requestedBaseMagnitude
        val statusFields = state.telemetry.statusFields
        val telemetryLeft = state.telemetry.leftOutputPercent ?: 0
        val telemetryRight = state.telemetry.rightOutputPercent ?: 0
        val firmwareCorrection = statusFields["HCORR"]?.toIntOrNull() ?: 0
        val leftCommandPercent = state.telemetry.leftOutputPercent ?: 0
        val rightCommandPercent = state.telemetry.rightOutputPercent ?: 0
        val decisionMessage = stationKeepingMessage(
            positionActive = positionActive,
            movingReverse = movingReverse,
            basePercent = basePercent,
            absErrorDegrees = absError,
            finalHeadingSuppressed = finalHeadingSuppressed,
        )
        val logSnapshot = stationKeepingLogStore.append(
            StationKeepingLogEntry(
                phoneTimeMs = System.currentTimeMillis(),
                reason = decisionMessage,
                gpsFix = navigationGpsFixTextForLog(),
                gpsSat = gpsSatelliteCount(),
                gpsSpeedKmh = navigationGpsSpeedKmhTextForLog(),
                currentLatitude = currentPoint.latitude,
                currentLongitude = currentPoint.longitude,
                rawGpsLatitude = fusionSample.rawPoint.latitude,
                rawGpsLongitude = fusionSample.rawPoint.longitude,
                filteredGpsLatitude = fusionSample.filteredPoint.latitude,
                filteredGpsLongitude = fusionSample.filteredPoint.longitude,
                gpsJumpMeters = fusionSample.gpsJumpMeters,
                gpsWeight = fusionSample.gpsWeight,
                gpsFilterAlpha = fusionSample.filterAlpha,
                gpsFusionReason = fusionSample.reason,
                imuAccelXG = fusionSample.imuMotion.accelXG,
                imuAccelYG = fusionSample.imuMotion.accelYG,
                imuAccelZG = fusionSample.imuMotion.accelZG,
                imuAccelNormG = fusionSample.imuMotion.accelNormG,
                imuMotionG = fusionSample.imuMotion.motionG,
                targetLatitude = targetPoint.latitude,
                targetLongitude = targetPoint.longitude,
                targetHeadingDegrees = targetHeading,
                currentHeadingDegrees = currentHeading,
                desiredHeadingDegrees = desiredHeading,
                headingOffsetDegrees = shortestCompassError(desiredHeading, targetHeading).toDouble(),
                headingErrorDegrees = errorDegrees,
                distanceMeters = stationError.distanceMeters,
                forwardErrorMeters = stationError.forwardMeters,
                lateralErrorMeters = stationError.lateralMeters,
                positionActive = positionActive,
                movingReverse = movingReverse,
                gearIndex = gearIndex,
                outputLimitPercent = outputLimitPercent,
                requestedBasePercent = requestedBaseMagnitude,
                basePercent = basePercent,
                baseCorrectionPercent = firmwareCorrection,
                leftOutputPercent = telemetryLeft,
                rightOutputPercent = telemetryRight,
                leftCommandPercent = leftCommandPercent,
                rightCommandPercent = rightCommandPercent,
                statusArmed = statusFields["ARMED"].orEmpty(),
                statusLeft = statusFields["L"].orEmpty(),
                statusRight = statusFields["R"].orEmpty(),
                statusLeftPwm = statusFields["LPWM"].orEmpty(),
                statusRightPwm = statusFields["RPWM"].orEmpty(),
                rawStatus = state.telemetry.lastReceivedStatus,
            ),
        )

        mutableUiState.update {
            it.copy(
                leftThrottlePercent = basePercent,
                rightThrottlePercent = basePercent,
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = gearIndex,
                    distanceToTargetMeters = stationError.distanceMeters,
                    headingErrorDegrees = errorDegrees,
                    stationKeepingForwardErrorMeters = stationError.forwardMeters,
                    stationKeepingLateralErrorMeters = stationError.lateralMeters,
                    stationKeepingPositionActive = positionActive,
                    stationKeepingMovingReverse = movingReverse,
                    leftOutputPercent = telemetryLeft,
                    rightOutputPercent = telemetryRight,
                    message = decisionMessage,
                ),
                stationKeepingLogPath = logSnapshot.filePath,
                stationKeepingLogSampleCount = logSnapshot.sampleCount,
            )
        }

        return firmwareHeadingIntentCommand(
            targetHeadingDegrees = desiredHeading,
            basePercent = basePercent,
            settings = settings,
            fullCorrectionDegrees = STATION_KEEPING_FULL_CORRECTION_DEGREES,
            maxPivotDifferencePercent = minOf(
                settings.headingLockNeutralPivotMaxDifferencePercent,
                STATION_KEEPING_MAX_PIVOT_DIFFERENCE_PERCENT,
            ),
        )
    }

    private fun buildAppHeadingControlCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        activeTurnCommand?.let { command ->
            val configuredCommand = command.copy(
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
            mutableUiState.update {
                it.copy(
                    commandSource = configuredCommand.source,
                    appHeadingLockTargetDegrees = null,
                    appHeadingLockErrorDegrees = it.telemetry.statusFields["TERR"]?.toFloatOrNull(),
                    appHeadingLeftOutputPercent = it.telemetry.leftOutputPercent,
                    appHeadingRightOutputPercent = it.telemetry.rightOutputPercent,
                    appHeadingLeftCommandPercent = null,
                    appHeadingRightCommandPercent = null,
                )
            }
            return configuredCommand
        }
        activeHeadingLockCommand?.let { command ->
            val configuredCommand = command.copy(
                headingLockToleranceDegrees = settings.headingLockToleranceDegrees,
                headingLockFullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
                headingLockNeutralPivotMinDifferencePercent = settings.headingLockNeutralPivotMinDifferencePercent,
                headingLockNeutralPivotMaxDifferencePercent = settings.headingLockNeutralPivotMaxDifferencePercent,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
            val heartbeat = configuredCommand.asHeadingLockHeartbeat()
            val outgoingCommand = if (activeHeadingLockStartPending) {
                configuredCommand
            } else {
                heartbeat
            }
            activeHeadingLockStartPending = false
            activeHeadingLockCommand = configuredCommand
            mutableUiState.update {
                it.copy(
                    commandSource = outgoingCommand.source,
                    headingLockEnabled = configuredCommand.headingLockEnabled,
                    appHeadingLockTargetDegrees = it.telemetry.targetHeadingDegrees ?: appHeadingControlTargetDegrees,
                    appHeadingLockErrorDegrees = it.telemetry.statusFields["HERR"]?.toFloatOrNull(),
                    appHeadingLockCorrectionPercent = it.telemetry.statusFields["HCORR"]?.toIntOrNull() ?: 0,
                    appHeadingLeftOutputPercent = it.telemetry.leftOutputPercent,
                    appHeadingRightOutputPercent = it.telemetry.rightOutputPercent,
                    appHeadingLeftCommandPercent = null,
                    appHeadingRightCommandPercent = null,
                )
            }
            return outgoingCommand
        }
        return neutralArmedCommand(CommandSource.App, settings)
    }

    private fun shortestCompassError(targetDegrees: Float, currentDegrees: Float): Float {
        var delta = normalizeCompassDegrees(targetDegrees) - normalizeCompassDegrees(currentDegrees)
        if (delta > 180f) {
            delta -= 360f
        } else if (delta < -180f) {
            delta += 360f
        }
        return delta
    }

    private fun firmwareHeadingIntentCommand(
        targetHeadingDegrees: Float,
        basePercent: Int,
        settings: SettingsUiState,
        fullCorrectionDegrees: Int,
        maxPivotDifferencePercent: Int = settings.headingLockNeutralPivotMaxDifferencePercent,
    ): ControlCommand {
        val normalizedTarget = normalizeCompassDegrees(targetHeadingDegrees)
        val existing = activeHeadingLockCommand?.takeIf { it.headingLockEnabled }
        val requestId = existing?.headingLockRequestId ?: nextHeadingLockRequestId()
        val pivotMax = maxPivotDifferencePercent.coerceIn(
            settings.headingLockNeutralPivotMinDifferencePercent,
            HEADING_LOCK_NEUTRAL_PIVOT_MAX_REVERSE_PERCENT,
        )
        val command = ControlCommand(
            armed = true,
            source = CommandSource.App,
            mode = ControlCommandMode.HeadingLock,
            headingLockEnabled = true,
            headingLockRequestId = requestId,
            headingLockBaseThrottlePercent = coerceHeadingLockBasePercent(
                percent = basePercent,
                source = CommandSource.App,
            ),
            headingLockToleranceDegrees = settings.headingLockToleranceDegrees,
            headingLockFullCorrectionDegrees = fullCorrectionDegrees.coerceIn(
                settings.headingLockToleranceDegrees + 1,
                180,
            ),
            headingLockNeutralPivotMinDifferencePercent = settings.headingLockNeutralPivotMinDifferencePercent,
            headingLockNeutralPivotMaxDifferencePercent = pivotMax,
            headingLockTargetDegrees = normalizedTarget,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
        activeHeadingLockStartPending = existing == null
        activeHeadingLockOwnedByAutoNavigation = true
        activeHeadingLockCommand = command
        appHeadingControlTargetDegrees = normalizedTarget
        mutableUiState.update {
            it.copy(
                headingLockEnabled = true,
                appHeadingLockTargetDegrees = normalizedTarget,
                appHeadingLockErrorDegrees = it.telemetry.statusFields["HERR"]?.toFloatOrNull(),
                appHeadingLockCorrectionPercent = it.telemetry.statusFields["HCORR"]?.toIntOrNull() ?: 0,
                appHeadingLeftOutputPercent = it.telemetry.leftOutputPercent,
                appHeadingRightOutputPercent = it.telemetry.rightOutputPercent,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
            )
        }
        return command
    }

    private fun startAppHeadingLock(command: ControlCommand, targetHeadingDegrees: Float) {
        val normalizedTarget = normalizeCompassDegrees(targetHeadingDegrees)
        activeHeadingLockCommand = command
        activeHeadingLockStartPending = true
        activeHeadingLockOwnedByAutoNavigation = false
        appHeadingControlTargetDegrees = normalizedTarget
        appHeadingControlStartedAtMs = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                headingLockEnabled = true,
                appHeadingLockTargetDegrees = appHeadingControlTargetDegrees,
                appHeadingLockErrorDegrees = 0f,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
            )
        }
    }

    private fun startAppTurn(command: ControlCommand) {
        activeTurnCommand = command
        appHeadingControlStartedAtMs = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                headingLockEnabled = false,
                appHeadingLockTargetDegrees = null,
                appHeadingLockErrorDegrees = 0f,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
            )
        }
    }

    private fun failAppHeadingControl(message: String) {
        clearAutonomousCommands()
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = message,
            )
        }
    }

    private fun neutralArmedCommand(source: CommandSource, settings: SettingsUiState): ControlCommand {
        return ControlCommand(
            armed = true,
            source = source,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun prepareRuntimeCommand(
        command: ControlCommand,
        allocateTurnRequestId: Boolean = false,
        allocateHeadingLockRequestId: Boolean = false,
    ): ControlCommand {
        if (command.mode == ControlCommandMode.TurnAngle) {
            val settings = mutableSettingsState.value
            return command.copy(
                turnRequestId = if (allocateTurnRequestId) nextTurnRequestId() else previewTurnRequestId(),
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        }
        if (command.mode == ControlCommandMode.HeadingLock) {
            val settings = mutableSettingsState.value
            val requestedBasePercent = if (command.headingLockEnabled && command.headingLockBaseThrottlePercent == 0) {
                currentAverageThrottlePercent()
            } else {
                command.headingLockBaseThrottlePercent
            }
            return command.copy(
                headingLockRequestId = when {
                    !command.headingLockEnabled -> null
                    allocateHeadingLockRequestId -> nextHeadingLockRequestId()
                    else -> previewHeadingLockRequestId()
                },
                headingLockBaseThrottlePercent = coerceHeadingLockBasePercent(
                    percent = requestedBasePercent,
                    source = command.source,
                ),
                headingLockToleranceDegrees = settings.headingLockToleranceDegrees,
                headingLockFullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
                headingLockNeutralPivotMinDifferencePercent = settings.headingLockNeutralPivotMinDifferencePercent,
                headingLockNeutralPivotMaxDifferencePercent = settings.headingLockNeutralPivotMaxDifferencePercent,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        }
        if (!command.armed) {
            return command.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                voicePowerLimitPercent = mutableSettingsState.value.voicePowerLimitPercent,
            )
        }
        val settings = mutableSettingsState.value
        val leftPercent = coerceCommandPercentForSource(
            percent = command.leftThrottlePercent,
            source = command.source,
        )
        val rightPercent = coerceCommandPercentForSource(
            percent = command.rightThrottlePercent,
            source = command.source,
        )
        return command.copy(
            leftThrottlePercent = leftPercent,
            rightThrottlePercent = rightPercent,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun formatCommandLine(command: ControlCommand): String {
        if (command.mode == ControlCommandMode.TurnAngle) {
            return if (command.source == CommandSource.Voice) {
                "主控角度转向，发送 MODE=TURN 指令"
            } else {
                "主控角度转向，发送 MODE=TURN 指令"
            }
        }
        if (command.mode == ControlCommandMode.HeadingLock) {
            return if (command.headingLockEnabled) {
                if (command.source == CommandSource.Voice) {
                    "固件航向锁定，发送启动请求"
                } else {
                    "固件航向锁定，发送启动请求"
                }
            } else {
                "退出固件航向锁定，回到普通 L/R 心跳"
            }
        }
        if (command.mode == ControlCommandMode.KeepAlive) {
            return "控制保活，发送 MODE=KEEPALIVE"
        }
        return command.toWireLine()
    }

    private fun initializeVoiceReplyEngine(enginePackage: String? = null, retriedFallback: Boolean = false) {
        voiceReplyReady = false
        var createdEngine: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                val engine = voiceReplyEngine ?: createdEngine
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    Log.w(
                        TAG,
                        "语音回复初始化失败 status=$status engine=${engine != null} package=${enginePackage ?: "default"}",
                    )
                    engine?.shutdown()
                    voiceReplyEngine = null
                    val fallbackEngine = if (!retriedFallback) findVoiceReplyEnginePackage() else null
                    if (fallbackEngine != null && fallbackEngine != enginePackage) {
                        Log.i(TAG, "尝试使用系统 TTS 引擎：$fallbackEngine")
                        initializeVoiceReplyEngine(fallbackEngine, retriedFallback = true)
                    }
                    return@post
                }
                val languageResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "语音回复不支持中文 TTS result=$languageResult package=${enginePackage ?: "default"}")
                    return@post
                }
                engine.setSpeechRate(1.0f)
                engine.setOnUtteranceProgressListener(voiceReplyProgressListener())
                voiceReplyReady = true
                Log.i(TAG, "语音回复 TTS 已就绪 languageResult=$languageResult package=${enginePackage ?: "default"}")
                flushPendingVoiceReplies()
            }
        }
        createdEngine = if (enginePackage == null) {
            TextToSpeech(getApplication(), listener)
        } else {
            TextToSpeech(getApplication(), listener, enginePackage)
        }
        voiceReplyEngine = createdEngine
    }

    private fun findVoiceReplyEnginePackage(): String? {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return getApplication<Application>().packageManager
            .queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .firstOrNull()
    }

    private fun speakVoiceCommandReply(result: VoiceParseResult.Accepted, command: ControlCommand) {
        speakVoiceReply(voiceCommandReplyText(result, command))
    }

    private fun voiceCommandReplyText(result: VoiceParseResult.Accepted, command: ControlCommand): String {
        if (result.action == VoiceCommandAction.DisableVoiceControl) {
            return replyWithDetail("已停止声控")
        }
        if (command.mode == ControlCommandMode.TurnAngle) {
            return turnAngleReplyText(result, command)
        }
        if (command.mode == ControlCommandMode.HeadingLock) {
            return if (command.headingLockEnabled) {
                replyWithDetail("已发送航向锁定请求")
            } else {
                replyWithDetail("已发送取消航向锁定请求")
            }
        }
        if (!command.armed) {
            return replyWithDetail("已停止")
        }
        if (result.label == "空档") {
            return replyWithDetail("已空档")
        }
        return randomVoiceAck()
    }

    private fun turnAngleReplyText(result: VoiceParseResult.Accepted, command: ControlCommand): String {
        val direction = command.turnDirection?.label ?: result.label.compactVoiceLabel()
        val angle = command.turnAngleDegrees ?: return randomVoiceAck()
        val options = buildList {
            add(randomVoiceAck())
            add(replyWithDetail("${direction}${angle}度"))
            add("${randomVoiceAck()}，${direction}${angle}度")
            if (isHeadingLockActiveForState(mutableUiState.value)) {
                add(replyWithDetail("锁航目标${direction}${angle}度"))
            }
        }
        return options.random(Random.Default)
    }

    private fun normalizeHeadingDegrees(degrees: Float): Int {
        return (((degrees % 360f) + 360f) % 360f).toInt()
    }

    private fun replyWithDetail(detail: String): String {
        return if (Random.nextBoolean()) {
            detail
        } else {
            "${randomVoiceAck()}，$detail"
        }
    }

    private fun randomVoiceAck(): String {
        return VOICE_ACK_REPLIES.random(Random.Default)
    }

    private fun String.compactVoiceLabel(): String {
        return replace(" ", "").replace("/", "")
    }

    private fun speakVoiceReply(text: String) {
        val reply = text.trim()
        if (reply.isBlank()) {
            return
        }
        if (!voiceReplyReady) {
            pendingVoiceReplies.clear()
            pendingVoiceReplies.addLast(reply)
            Log.i(TAG, "语音回复未就绪，已缓存：$reply")
            return
        }
        suppressVoiceRecognitionDuringReply()
        val speakResult = voiceReplyEngine?.speak(
            reply,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "voice_reply_${System.currentTimeMillis()}",
        ) ?: TextToSpeech.ERROR
        if (speakResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "语音回复播放失败 result=$speakResult text=$reply")
            suppressVoiceRecognitionAfterReply()
        }
    }

    private fun flushPendingVoiceReplies() {
        val reply = pendingVoiceReplies.removeFirstOrNull() ?: return
        pendingVoiceReplies.clear()
        speakVoiceReply(reply)
    }

    private fun voiceReplyProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    suppressVoiceRecognitionDuringReply()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    suppressVoiceRecognitionAfterReply()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    suppressVoiceRecognitionAfterReply()
                }
            }
        }
    }

    private fun isVoiceReplySuppressingRecognition(): Boolean {
        return System.currentTimeMillis() < ignoreVoiceRecognitionUntilMs
    }

    private fun suppressVoiceRecognitionDuringReply() {
        ignoreVoiceRecognitionUntilMs = System.currentTimeMillis() + VOICE_REPLY_MAX_SUPPRESSION_MS
        mutableUiState.update {
            if (it.voiceReplySuppressingRecognition) {
                it
            } else {
                it.copy(voiceReplySuppressingRecognition = true)
            }
        }
        mainHandler.postDelayed({
            if (!isVoiceReplySuppressingRecognition()) {
                mutableUiState.update { it.copy(voiceReplySuppressingRecognition = false) }
            }
        }, VOICE_REPLY_MAX_SUPPRESSION_MS + 50L)
    }

    private fun suppressVoiceRecognitionAfterReply() {
        ignoreVoiceRecognitionUntilMs = System.currentTimeMillis() + VOICE_REPLY_POST_SUPPRESSION_MS
        mutableUiState.update {
            if (it.voiceReplySuppressingRecognition) {
                it
            } else {
                it.copy(voiceReplySuppressingRecognition = true)
            }
        }
        mainHandler.postDelayed({
            if (!isVoiceReplySuppressingRecognition()) {
                mutableUiState.update { it.copy(voiceReplySuppressingRecognition = false) }
            }
        }, VOICE_REPLY_POST_SUPPRESSION_MS + 50L)
    }

    private fun nextTurnRequestId(): Int {
        voiceTurnRequestCounter = if (voiceTurnRequestCounter >= MAX_TURN_REQUEST_ID) {
            1
        } else {
            voiceTurnRequestCounter + 1
        }
        return voiceTurnRequestCounter
    }

    private fun previewTurnRequestId(): Int {
        return if (voiceTurnRequestCounter >= MAX_TURN_REQUEST_ID) {
            1
        } else {
            voiceTurnRequestCounter + 1
        }
    }

    private fun nextHeadingLockRequestId(): Int {
        headingLockRequestCounter = if (headingLockRequestCounter >= MAX_TURN_REQUEST_ID) {
            1
        } else {
            headingLockRequestCounter + 1
        }
        return headingLockRequestCounter
    }

    private fun previewHeadingLockRequestId(): Int {
        return if (headingLockRequestCounter >= MAX_TURN_REQUEST_ID) {
            1
        } else {
            headingLockRequestCounter + 1
        }
    }

    private fun clearActiveTurnCommand() {
        activeTurnCommand = null
        if (activeHeadingLockCommand == null) {
            clearAppHeadingControlTarget()
        }
    }

    private fun clearActiveHeadingLockCommand() {
        activeHeadingLockCommand = null
        activeHeadingLockStartPending = false
        activeHeadingLockOwnedByAutoNavigation = false
        if (activeTurnCommand == null) {
            clearAppHeadingControlTarget()
        }
    }

    private fun clearPendingControllerArmedRequest() {
        pendingControllerArmedRequest = null
        pendingControllerArmedRequestUntilMs = 0L
    }

    private fun updateActiveHeadingLockBaseFromThrottle(
        leftPercent: Int,
        rightPercent: Int,
        source: CommandSource,
    ): Boolean {
        val command = activeHeadingLockCommand ?: return false
        if (!command.headingLockEnabled || !mutableUiState.value.headingLockEnabled) {
            return false
        }
        val settings = mutableSettingsState.value
        val basePercent = ((leftPercent + rightPercent) / 2).coerceIn(-100, 100)
        activeHeadingLockCommand = command.copy(
            source = source,
            headingLockBaseThrottlePercent = coerceHeadingLockBasePercent(
                percent = basePercent,
                source = source,
            ),
            headingLockToleranceDegrees = settings.headingLockToleranceDegrees,
            headingLockFullCorrectionDegrees = settings.headingLockFullCorrectionDegrees,
            headingLockNeutralPivotMinDifferencePercent = settings.headingLockNeutralPivotMinDifferencePercent,
            headingLockNeutralPivotMaxDifferencePercent = settings.headingLockNeutralPivotMaxDifferencePercent,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
        return true
    }

    private fun ControlCommand.asHeadingLockHeartbeat(): ControlCommand {
        return ControlCommand(
            armed = true,
            source = source,
            mode = ControlCommandMode.KeepAlive,
            voicePowerLimitPercent = voicePowerLimitPercent,
        )
    }

    private fun clearAutonomousCommands(autoNavigationMessage: String = "自动导航已取消") {
        realtimeVoiceActionToken++
        activeTurnCommand = null
        activeHeadingLockCommand = null
        activeHeadingLockStartPending = false
        activeHeadingLockOwnedByAutoNavigation = false
        clearAutoNavigationExecution(autoNavigationMessage)
        clearAppHeadingControlTarget()
    }

    private fun refreshAutoNavigationRoutes(message: String, selectedRouteId: String? = null) {
        val routes = autoNavigationRouteStore.readRoutes()
        mutableUiState.update {
            val nextSelectedRouteId = selectedRouteId
                ?.takeIf { id -> routes.any { it.id == id } }
                ?: it.autoNavigation.selectedRouteId?.takeIf { id -> routes.any { route -> route.id == id } }
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    routes = routes,
                    selectedRouteId = nextSelectedRouteId,
                    editingRouteId = null,
                    editingRouteName = "",
                    editingPoints = emptyList(),
                    editingNewRoute = false,
                    message = message,
                ),
            )
        }
    }

    private fun clearAutoNavigationExecution(message: String) {
        if (activeHeadingLockOwnedByAutoNavigation) {
            clearActiveHeadingLockCommand()
        }
        autoNavigationFilteredPoint = null
        autoNavigationLastRawPoint = null
        stationKeepingFilteredPoint = null
        stationKeepingLastRawPoint = null
        mutableUiState.update {
            if (!it.autoNavigation.active && it.autoNavigation.leftOutputPercent == 0 && it.autoNavigation.rightOutputPercent == 0) {
                it
            } else {
                it.copy(
                    autoNavigation = it.autoNavigation.copy(
                        executingRouteId = null,
                        targetPointIndex = 0,
                        distanceToTargetMeters = null,
                        headingErrorDegrees = null,
                        trackLineLockEnabled = false,
                        trackLineOrigin = null,
                        trackLineBearingDegrees = null,
                        trackLineTargetHeadingDegrees = null,
                        trackLineCrossTrackErrorMeters = null,
                        trackLineAlongTrackMeters = null,
                        stationKeepingEnabled = false,
                        stationKeepingTarget = null,
                        stationKeepingTargetHeadingDegrees = null,
                        stationKeepingForwardErrorMeters = null,
                        stationKeepingLateralErrorMeters = null,
                        stationKeepingPositionActive = false,
                        stationKeepingMovingReverse = false,
                        leftOutputPercent = 0,
                        rightOutputPercent = 0,
                        message = message,
                    ),
                    statusMessage = message,
                )
            }
        }
    }

    private fun failAutoNavigation(message: String) {
        clearAutoNavigationExecution(message)
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = message,
            )
        }
    }

    private fun autoNavigationStartRejectReason(route: NavigationRoute?): String? {
        val state = mutableUiState.value
        return when {
            route == null -> "自动导航拒绝：未找到路线"
            route.points.size < 2 -> "自动导航拒绝：路线至少需要 2 个航点"
            state.connectionState != ConnectionState.Connected -> "自动导航拒绝：未连接主控"
            !state.armed -> "自动导航拒绝：请先手动解锁"
            currentGpsPointOrNull() == null -> "自动导航拒绝：${navigationGpsUnavailableText()}"
            gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES -> "自动导航拒绝：GPS 卫星数不足"
            currentHeadingForLockOrNull() == null -> "自动导航拒绝：${headingSourceUnavailableText()}"
            else -> null
        }
    }

    private fun trackLineLockStartRejectReason(): String? {
        val state = mutableUiState.value
        return when {
            state.connectionState != ConnectionState.Connected -> "航迹线锁定拒绝：未连接主控"
            !state.armed -> "航迹线锁定拒绝：请先手动解锁"
            currentGpsPointOrNull() == null -> "航迹线锁定拒绝：${navigationGpsUnavailableText()}"
            gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES -> "航迹线锁定拒绝：GPS 卫星数不足"
            currentHeadingForLockOrNull() == null -> "航迹线锁定拒绝：${headingSourceUnavailableText()}"
            else -> null
        }
    }

    private fun stationKeepingStartRejectReason(): String? {
        val state = mutableUiState.value
        return when {
            state.connectionState != ConnectionState.Connected -> "定点保持拒绝：未连接主控"
            !state.armed -> "定点保持拒绝：请先手动解锁"
            currentGpsPointOrNull() == null -> "定点保持拒绝：${navigationGpsUnavailableText()}"
            gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES -> "定点保持拒绝：GPS 卫星数不足"
            currentHeadingForLockOrNull() == null -> "定点保持拒绝：${headingSourceUnavailableText()}"
            else -> null
        }
    }

    private fun currentGpsPointOrNull(): NavigationRoutePoint? {
        return when (mutableSettingsState.value.navigationGpsSource) {
            NavigationGpsSource.Esp32 -> currentEsp32GpsPointOrNull()
            NavigationGpsSource.Phone -> currentPhoneGpsPointOrNull()
        }
    }

    private fun currentEsp32GpsPointOrNull(): NavigationRoutePoint? {
        val telemetry = mutableUiState.value.telemetry
        if (telemetry.statusFields["GPS_FIX"] != "1") {
            return null
        }
        val latitude = telemetry.statusFields["GPS_LAT"]?.toDoubleOrNull() ?: return null
        val longitude = telemetry.statusFields["GPS_LON"]?.toDoubleOrNull() ?: return null
        return NavigationRoutePoint(latitude, longitude)
    }

    private fun currentPhoneGpsPointOrNull(): NavigationRoutePoint? {
        val phoneGps = mutableUiState.value.phoneGps
        val ageMs = System.currentTimeMillis() - phoneGpsLastUpdateMs
        if (!phoneGps.hasLocation || ageMs > PHONE_GPS_STALE_MS) {
            return null
        }
        val accuracy = phoneGps.accuracyMeters
        if (accuracy != null && accuracy > PHONE_GPS_MAX_ACCURACY_METERS) {
            return null
        }
        val latitude = phoneGps.latitude ?: return null
        val longitude = phoneGps.longitude ?: return null
        return NavigationRoutePoint(latitude, longitude)
    }

    private fun magneticDeclinationDegreesFromTelemetry(
        telemetry: com.smartsup.controller.model.Telemetry,
    ): Float? {
        if (telemetry.statusFields["GPS_FIX"] != "1") {
            return null
        }
        val latitude = telemetry.statusFields["GPS_LAT"]?.toDoubleOrNull() ?: return null
        val longitude = telemetry.statusFields["GPS_LON"]?.toDoubleOrNull() ?: return null
        return magneticDeclinationDegrees(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = null,
        )
    }

    private fun magneticDeclinationDegrees(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
    ): Float? {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return null
        }
        return runCatching {
            GeomagneticField(
                latitude.toFloat(),
                longitude.toFloat(),
                (altitudeMeters ?: 0.0).toFloat(),
                System.currentTimeMillis(),
            ).declination.takeIf { it.isFinite() }
        }.getOrNull()
    }

    private fun currentAutoNavigationPointOrNull(jumpResetMeters: Double): NavigationRoutePoint? {
        val rawPoint = currentGpsPointOrNull() ?: return null
        val lastRawPoint = autoNavigationLastRawPoint
        val previousPoint = autoNavigationFilteredPoint
        val rawJumped = lastRawPoint != null && distanceMeters(lastRawPoint, rawPoint) > jumpResetMeters
        val nextPoint = if (
            previousPoint == null ||
            rawJumped
        ) {
            rawPoint
        } else {
            interpolatePoint(
                from = previousPoint,
                to = rawPoint,
                fraction = AUTO_NAVIGATION_GPS_FILTER_ALPHA,
            )
        }
        autoNavigationFilteredPoint = nextPoint
        autoNavigationLastRawPoint = rawPoint
        return nextPoint
    }

    private fun stationKeepingFusionSampleOrNull(
        state: ControlUiState,
        jumpResetMeters: Double,
    ): StationKeepingFusionSample? {
        val rawPoint = currentGpsPointOrNull() ?: return null
        val previousPoint = stationKeepingFilteredPoint
        val lastRawPoint = stationKeepingLastRawPoint
        val safeJumpResetMeters = jumpResetMeters.coerceAtLeast(1.0)
        val gpsJumpMeters = lastRawPoint?.let { distanceMeters(it, rawPoint) }
        val rawJumped = gpsJumpMeters != null && gpsJumpMeters > safeJumpResetMeters
        val imuMotion = stationKeepingImuMotionCue(state.telemetry.statusFields)
        val motionStrong = (imuMotion.motionG ?: 0.0) >= STATION_KEEPING_FUSION_MOTION_G
        val outputActive =
            abs(state.autoNavigation.leftOutputPercent) >= STATION_KEEPING_FUSION_OUTPUT_ACTIVE_PERCENT ||
                abs(state.autoNavigation.rightOutputPercent) >= STATION_KEEPING_FUSION_OUTPUT_ACTIVE_PERCENT

        val (filterAlpha, gpsWeight, reason) = when {
            previousPoint == null -> Triple(1.0, 1.0, "init")
            rawJumped && imuMotion.motionG != null && !motionStrong && !outputActive ->
                Triple(
                    STATION_KEEPING_FUSION_JUMP_LOW_MOTION_ALPHA,
                    STATION_KEEPING_FUSION_JUMP_LOW_MOTION_WEIGHT,
                    "gps_jump_low_motion",
                )
            rawJumped && (motionStrong || outputActive) ->
                Triple(
                    STATION_KEEPING_FUSION_JUMP_ACCEPT_ALPHA,
                    STATION_KEEPING_FUSION_JUMP_ACCEPT_WEIGHT,
                    "gps_jump_with_motion",
                )
            rawJumped ->
                Triple(
                    STATION_KEEPING_FUSION_JUMP_NO_IMU_ALPHA,
                    STATION_KEEPING_FUSION_JUMP_NO_IMU_WEIGHT,
                    "gps_jump_no_imu",
                )
            motionStrong || outputActive ->
                Triple(
                    STATION_KEEPING_FUSION_MOVING_ALPHA,
                    STATION_KEEPING_FUSION_MOVING_WEIGHT,
                    "gps_normal_motion",
                )
            else ->
                Triple(
                    STATION_KEEPING_FUSION_STILL_ALPHA,
                    STATION_KEEPING_FUSION_STILL_WEIGHT,
                    "gps_normal_still",
                )
        }

        val filteredPoint = if (previousPoint == null) {
            rawPoint
        } else {
            interpolatePoint(
                from = previousPoint,
                to = rawPoint,
                fraction = filterAlpha,
            )
        }
        stationKeepingFilteredPoint = filteredPoint
        stationKeepingLastRawPoint = rawPoint
        return StationKeepingFusionSample(
            rawPoint = rawPoint,
            filteredPoint = filteredPoint,
            gpsJumpMeters = gpsJumpMeters,
            gpsWeight = gpsWeight,
            filterAlpha = filterAlpha,
            reason = reason,
            imuMotion = imuMotion,
        )
    }

    private fun stationKeepingImuMotionCue(fields: Map<String, String>): StationKeepingImuMotionCue {
        val accelXG = fields["IAX"]?.toDoubleOrNull()
        val accelYG = fields["IAY"]?.toDoubleOrNull()
        val accelZG = fields["IAZ"]?.toDoubleOrNull()
        val accelNormG = fields["IAN"]?.toDoubleOrNull()
        val normDeltaG = accelNormG?.let { abs(it - 1.0) }
        val horizontalG = if (accelXG != null && accelYG != null) {
            sqrt(accelXG * accelXG + accelYG * accelYG)
        } else {
            null
        }
        val motionG = normDeltaG ?: horizontalG
        return StationKeepingImuMotionCue(
            accelXG = accelXG,
            accelYG = accelYG,
            accelZG = accelZG,
            accelNormG = accelNormG,
            motionG = motionG,
        )
    }

    private fun gpsSatelliteCount(): Int {
        return when (mutableSettingsState.value.navigationGpsSource) {
            NavigationGpsSource.Esp32 ->
                mutableUiState.value.telemetry.statusFields["GPS_SAT"]?.toIntOrNull() ?: 0
            NavigationGpsSource.Phone ->
                if (currentPhoneGpsPointOrNull() == null) 0 else AUTO_NAVIGATION_MIN_SATELLITES
        }
    }

    private fun navigationGpsFixTextForLog(): String {
        return if (currentGpsPointOrNull() == null) "0" else "1"
    }

    private fun navigationGpsSpeedKmhTextForLog(): String {
        return when (mutableSettingsState.value.navigationGpsSource) {
            NavigationGpsSource.Esp32 -> mutableUiState.value.telemetry.statusFields["GPS_SPD_KMH"].orEmpty()
            NavigationGpsSource.Phone -> mutableUiState.value.phoneGps.speedKmh
                ?.let { String.format(Locale.US, "%.2f", it.coerceAtLeast(0.0)) }
                .orEmpty()
        }
    }

    private fun navigationGpsUnavailableText(): String {
        return when (mutableSettingsState.value.navigationGpsSource) {
            NavigationGpsSource.Esp32 -> "ESP32 GPS 未定位"
            NavigationGpsSource.Phone -> mutableUiState.value.phoneGps.message.ifBlank { "手机 GPS 未定位" }
        }
    }

    private fun bearingBetween(from: NavigationRoutePoint, to: NavigationRoutePoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun nearestAutoNavigationTargetIndex(
        route: NavigationRoute,
        currentPoint: NavigationRoutePoint?,
    ): Int {
        if (currentPoint == null || route.points.isEmpty()) {
            return 0
        }
        return route.points
            .mapIndexed { index, point -> index to distanceMeters(currentPoint, point) }
            .minByOrNull { it.second }
            ?.first
            ?: 0
    }

    private data class TrackLineError(
        val crossTrackMeters: Double,
        val alongTrackMeters: Double,
    )

    private data class StationKeepingError(
        val distanceMeters: Double,
        val forwardMeters: Double,
        val lateralMeters: Double,
    )

    private data class StationKeepingImuMotionCue(
        val accelXG: Double?,
        val accelYG: Double?,
        val accelZG: Double?,
        val accelNormG: Double?,
        val motionG: Double?,
    )

    private data class StationKeepingFusionSample(
        val rawPoint: NavigationRoutePoint,
        val filteredPoint: NavigationRoutePoint,
        val gpsJumpMeters: Double?,
        val gpsWeight: Double,
        val filterAlpha: Double,
        val reason: String,
        val imuMotion: StationKeepingImuMotionCue,
    )

    private fun trackLineError(
        origin: NavigationRoutePoint,
        bearingDegrees: Double,
        point: NavigationRoutePoint,
    ): TrackLineError {
        val earthRadiusMeters = 6_371_000.0
        val originLat = Math.toRadians(origin.latitude)
        val pointLat = Math.toRadians(point.latitude)
        val eastMeters = Math.toRadians(point.longitude - origin.longitude) *
            cos((originLat + pointLat) / 2.0) *
            earthRadiusMeters
        val northMeters = Math.toRadians(point.latitude - origin.latitude) * earthRadiusMeters
        val bearingRadians = Math.toRadians(bearingDegrees)
        val eastUnit = sin(bearingRadians)
        val northUnit = cos(bearingRadians)
        return TrackLineError(
            crossTrackMeters = eastMeters * northUnit - northMeters * eastUnit,
            alongTrackMeters = eastMeters * eastUnit + northMeters * northUnit,
        )
    }

    private fun stationKeepingError(
        targetPoint: NavigationRoutePoint,
        targetHeadingDegrees: Float,
        currentPoint: NavigationRoutePoint,
    ): StationKeepingError {
        val distance = distanceMeters(currentPoint, targetPoint)
        if (distance <= 0.0) {
            return StationKeepingError(
                distanceMeters = 0.0,
                forwardMeters = 0.0,
                lateralMeters = 0.0,
            )
        }
        val bearingToTarget = bearingBetween(currentPoint, targetPoint).toFloat()
        val relativeBearing = Math.toRadians(shortestCompassError(bearingToTarget, targetHeadingDegrees).toDouble())
        return StationKeepingError(
            distanceMeters = distance,
            forwardMeters = distance * cos(relativeBearing),
            lateralMeters = distance * sin(relativeBearing),
        )
    }

    private fun stationKeepingBasePercent(
        distanceMeters: Double,
        gearPercent: Int,
        outputLimitPercent: Int,
    ): Int {
        if (outputLimitPercent <= 0) {
            return 0
        }
        val minEffective = minOf(STATION_KEEPING_MIN_EFFECTIVE_OUTPUT_PERCENT, outputLimitPercent)
        val maxEffective = minOf(gearPercent, outputLimitPercent)
        if (distanceMeters <= STATION_KEEPING_HOLD_RADIUS_METERS || maxEffective <= 0) {
            return 0
        }
        if (maxEffective <= minEffective) {
            return maxEffective
        }
        val ratio = ((distanceMeters - STATION_KEEPING_HOLD_RADIUS_METERS) /
            (STATION_KEEPING_FULL_OUTPUT_RADIUS_METERS - STATION_KEEPING_HOLD_RADIUS_METERS))
            .coerceIn(0.0, 1.0)
        return (minEffective + (maxEffective - minEffective) * ratio).roundToInt()
            .coerceIn(minEffective, maxEffective)
    }

    private fun stationKeepingMessage(
        positionActive: Boolean,
        movingReverse: Boolean,
        basePercent: Int,
        absErrorDegrees: Float,
        finalHeadingSuppressed: Boolean,
    ): String {
        if (finalHeadingSuppressed) {
            return "定点保持中：到点，目标航向偏差过大，禁止大角度原地转向"
        }
        if (!positionActive) {
            return "定点保持中：到点，调整目标航向"
        }
        if (basePercent == 0 && absErrorDegrees > STATION_KEEPING_GO_HEADING_ERROR_DEGREES) {
            return if (movingReverse) {
                "定点保持中：先转向倒车回点方向"
            } else {
                "定点保持中：先转向目标点"
            }
        }
        return if (movingReverse) {
            "定点保持中：倒车回点"
        } else {
            "定点保持中：直线推进到目标点"
        }
    }

    private fun interpolatePoint(
        from: NavigationRoutePoint,
        to: NavigationRoutePoint,
        fraction: Double,
    ): NavigationRoutePoint {
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        return NavigationRoutePoint(
            latitude = from.latitude + (to.latitude - from.latitude) * clampedFraction,
            longitude = from.longitude + (to.longitude - from.longitude) * clampedFraction,
        )
    }

    private fun distanceMeters(from: NavigationRoutePoint, to: NavigationRoutePoint): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
        return earthRadiusMeters * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun clearAppHeadingControlTarget() {
        appHeadingControlTargetDegrees = null
        appHeadingControlStartedAtMs = 0L
        mutableUiState.update {
            it.copy(
                appHeadingLockTargetDegrees = null,
                appHeadingLockErrorDegrees = null,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
            )
        }
    }

    private fun currentVoiceSampleTarget(): VoiceSampleTarget {
        return voiceSampleTargets[voiceSampleTargetIndex.coerceIn(0, voiceSampleTargets.lastIndex)]
    }

    private fun applyVoiceSampleTarget(messagePrefix: String) {
        val target = currentVoiceSampleTarget()
        mutableUiState.update {
            it.copy(
                voiceSampleTargetLabel = target.label,
                voiceSampleTargetText = target.spokenText,
                voiceSampleExpectedCommand = target.expectedCommand,
                voiceSamplePendingText = "",
                voiceSamplePendingCommand = "无待保存样本",
                voiceSampleLastMessage = "$messagePrefix：${target.label}",
            )
        }
    }

    private fun coerceSignedThrottle(percent: Int): Int {
        val limit = mutableSettingsState.value.maxThrottlePercent
        return percent.coerceIn(-limit, limit)
    }

    private fun coerceVoiceThrottle(percent: Int): Int {
        val limit = mutableSettingsState.value.voicePowerLimitPercent
        return percent.coerceIn(-limit, limit)
    }

    private fun coerceCommandPercentForSource(percent: Int, source: CommandSource): Int {
        return if (source == CommandSource.Voice) {
            coerceVoiceThrottle(percent)
        } else {
            coerceSignedThrottle(percent)
        }
    }

    private fun voiceAsrStateFor(message: String, shouldRun: Boolean): VoiceAsrState {
        if (!shouldRun) {
            return VoiceAsrState.Stopped
        }
        return if (message.contains("模型已加载") || message.contains("开始监听")) {
            VoiceAsrState.Ready
        } else {
            VoiceAsrState.Starting
        }
    }

    private fun currentAverageThrottlePercent(): Int {
        val state = mutableUiState.value
        return ((state.leftThrottlePercent + state.rightThrottlePercent) / 2)
            .coerceIn(-100, 100)
    }

    private fun previewFineTuneCommandLine(increase: Boolean, source: CommandSource): String {
        val target = nextFineTuneTarget(increase, source)
        return formatCommandLine(
            prepareRuntimeCommand(
                ControlCommand(
                    armed = true,
                    leftThrottlePercent = target.leftPercent,
                    rightThrottlePercent = target.rightPercent,
                    source = source,
                ),
            ),
        )
    }

    private fun nextFineTuneTarget(increase: Boolean, source: CommandSource): FineTuneTarget {
        val state = mutableUiState.value
        val step = mutableSettingsState.value.fineTuneStepPercent
        val leftPercent = coerceCommandPercentForSource(
            percent = adjustSpeedPercent(state.leftThrottlePercent, increase, step),
            source = source,
        )
        val rightPercent = coerceCommandPercentForSource(
            percent = adjustSpeedPercent(state.rightThrottlePercent, increase, step),
            source = source,
        )
        val averagePercent = ((leftPercent + rightPercent) / 2).coerceIn(-100, 100)
        return FineTuneTarget(
            leftPercent = leftPercent,
            rightPercent = rightPercent,
            trimPercent = fineTuneTrimFor(state.selectedGear, leftPercent, rightPercent),
            averagePercent = averagePercent,
        )
    }

    private fun adjustSpeedPercent(percent: Int, increase: Boolean, step: Int): Int {
        return when {
            increase && percent < 0 -> percent - step
            increase -> percent + step
            percent < 0 -> (percent + step).coerceAtMost(0)
            else -> (percent - step).coerceAtLeast(0)
        }
    }

    private fun selectedGearThrottle(gear: ThrottleGear, trimPercent: Int): Int {
        return (gearPercent(gear) + trimPercent).coerceIn(-100, 100)
    }

    private fun fineTuneTrimFor(gear: ThrottleGear, leftPercent: Int, rightPercent: Int): Int {
        val averagePercent = ((leftPercent + rightPercent) / 2).coerceIn(-100, 100)
        return (averagePercent - gearPercent(gear)).coerceIn(-100, 100)
    }

    private fun coerceHeadingLockBasePercent(percent: Int, source: CommandSource): Int {
        if (source == CommandSource.App) {
            return coerceSignedThrottle(percent)
        }
        return coerceVoiceThrottle(percent)
    }

    private fun Int.signedPercentText(): String {
        return if (this > 0) "+$this%" else "$this%"
    }

    private suspend fun sendCommand(command: ControlCommand): Boolean {
        if (esp32OtaExclusive) {
            return true
        }
        val activeTransport = transport ?: return false
        return runCatching {
            val outgoingCommand = command.withRuntimeHeadingSource()
            activeTransport.send(outgoingCommand)
            appendSentCommandLog(outgoingCommand)
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

    private fun startOtaReconnectLoop(forceRestart: Boolean = false) {
        if (forceRestart) {
            otaReconnectJob?.cancel()
            otaReconnectJob = null
        }
        if (otaReconnectJob?.isActive == true) {
            return
        }
        otaReconnectAttempt = 0
        otaReconnectJob = viewModelScope.launch {
            while (esp32OtaExclusive && esp32OtaAccepted) {
                val targetVersion = pendingEsp32FirmwareVersion
                val savedDevice = mutableSettingsState.value.savedDevice
                if (savedDevice == null) {
                    mutableUpdateState.update {
                        it.copy(message = "主控 OTA 已完成，但没有已保存设备，无法自动重连验证版本")
                    }
                    return@launch
                }

                val connectionState = mutableUiState.value.connectionState
                if (!otaReconnectSawDisconnect && connectionState == ConnectionState.Connected) {
                    mutableUpdateState.update {
                        it.copy(
                            message = targetVersion?.let { target ->
                                "主控 OTA 已接受，等待主控重启断开后每 3 秒自动重连验证 FW=$target"
                            } ?: "主控 OTA 已接受，等待主控重启断开后每 3 秒自动重连确认版本",
                        )
                    }
                } else if (connectionState == ConnectionState.Connected && transport != null) {
                    mutableUpdateState.value.currentEsp32FirmwareVersion?.let { firmwareVersion ->
                        handlePendingOtaFirmwareVerification(
                            firmwareVersion = firmwareVersion,
                            allowMismatch = false,
                        )
                    }
                    if (esp32OtaExclusive && esp32OtaAccepted) {
                        mutableUpdateState.update {
                            it.copy(
                                message = targetVersion?.let { target ->
                                    "已连接 ${savedDevice.name}，正在读取主控固件版本以验证 FW=$target"
                                } ?: "已连接 ${savedDevice.name}，正在读取主控固件版本",
                            )
                        }
                        runCatching { transport?.sendRawLine("INFO?") }
                    }
                } else {
                    otaReconnectAttempt += 1
                    Log.i(TAG, "OTA reconnect attempt=$otaReconnectAttempt device=${savedDevice.name} address=${savedDevice.address}")
                    runCatching { transport?.closeSilently() }
                    transport = null
                    bluetoothConnectJob?.cancel()
                    telemetryJob?.cancel()
                    trackLogJob?.cancel()
                    trackLogSyncJob?.cancel()
                    commandHeartbeatJob?.cancel()
                    mutableUiState.update {
                        it.copy(
                            connectionState = ConnectionState.Disconnected,
                            armed = false,
                            leftThrottlePercent = 0,
                            rightThrottlePercent = 0,
                            commandSource = CommandSource.App,
                            headingLockEnabled = false,
                            selectedGear = ThrottleGear.Neutral,
                            throttleTrimPercent = 0,
                            statusMessage = "OTA 后第 $otaReconnectAttempt 次自动重连 ${savedDevice.name}",
                        )
                    }
                    mutableUpdateState.update {
                        it.copy(
                            message = targetVersion?.let { target ->
                                "第 $otaReconnectAttempt 次自动重连 ${savedDevice.name}，验证 FW=$target"
                            } ?: "第 $otaReconnectAttempt 次自动重连 ${savedDevice.name}，确认固件版本",
                        )
                    }
                    connectBluetooth(savedDevice)
                }
                delay(OTA_RECONNECT_INTERVAL_MS)
            }
        }
    }

    private fun stopOtaReconnectLoop() {
        otaReconnectJob?.cancel()
        otaReconnectJob = null
        otaReconnectAttempt = 0
        otaReconnectSawDisconnect = false
    }

    private fun handleTransportError(error: Throwable) {
        val updateStateBeforeDisconnect = mutableUpdateState.value
        val errorText = error.message ?: error::class.java.simpleName.ifBlank { "未知错误" }
        val disconnectMessage = "蓝牙链路异常：$errorText"
        val autoNavigationDisconnectMessage = "自动导航因蓝牙链路异常取消：$errorText"
        lastBluetoothDisconnectMessage = errorText
        val otaMayBeRebooting =
            esp32OtaAccepted &&
                (
                    updateStateBeforeDisconnect.message.contains("校验通过") ||
                        updateStateBeforeDisconnect.message.contains("正在重启") ||
                        updateStateBeforeDisconnect.message.contains("等待设备校验并重启")
                    )
        transport = null
        telemetryJob?.cancel()
        commandHeartbeatJob?.cancel()
        clearPendingControllerArmedRequest()
        clearAutonomousCommands(autoNavigationDisconnectMessage)
        mutableUiState.update {
            it.copy(
                connectionState = ConnectionState.Disconnected,
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = disconnectMessage,
            )
        }
        mutableSettingsState.update {
            it.copy(message = disconnectMessage)
        }
        if (
            updateStateBeforeDisconnect.esp32Uploading ||
            updateStateBeforeDisconnect.message.contains("OTA") ||
            updateStateBeforeDisconnect.message.contains("固件已发送完成") ||
            updateStateBeforeDisconnect.message.contains("正在重启")
        ) {
            if (!otaMayBeRebooting) {
                esp32OtaExclusive = false
                esp32OtaAccepted = false
                pendingEsp32FirmwareVersion = null
                stopOtaReconnectLoop()
            } else {
                otaReconnectSawDisconnect = true
                startOtaReconnectLoop(forceRestart = true)
            }
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = false,
                    message = if (otaMayBeRebooting) {
                        pendingEsp32FirmwareVersion?.let { target ->
                            "蓝牙已断开，主控正在重启；每 3 秒自动重连验证 FW=$target"
                        } ?: "蓝牙已断开，主控正在重启；每 3 秒自动重连确认固件版本"
                    } else {
                        "蓝牙 OTA 中断：${error.message ?: "未知错误"}"
                    },
                    progressText = "",
                )
            }
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
                appendTelemetryLog(telemetry)
                handleControllerAuthorityLine(telemetry.lastReceivedStatus)
                handleControllerTurnLine(telemetry.lastReceivedStatus)
                handleControllerHeadingLockLine(telemetry.lastReceivedStatus)
                handleControllerErrorLine(telemetry.lastReceivedStatus)
                handleControllerFaultLine(telemetry.lastReceivedStatus)
                handleOtaStatusLine(telemetry.lastReceivedStatus)
                handleControllerInfoFields(telemetry.statusFields)
                val receivedFields = parseControlLogFields(telemetry.lastReceivedStatus)
                clearStaleHeadingLockUiIfNeeded(receivedFields)
                if (
                    receivedFields["HSRC"] == "YBIMU" &&
                    receivedFields["YBIMU"] == "1" &&
                    receivedFields["YBHDG"]?.toFloatOrNull() != null
                ) {
                    ybImuHeadingLastUpdateMs = System.currentTimeMillis()
                }
                // IMU CSV 调试日志暂时关闭；新 IMU 到货后再打开。
                val phoneHeading = mutableUiState.value.phoneHeadingDegrees
                val logSnapshot = if (
                    IMU_TELEMETRY_LOGGING_ENABLED &&
                    telemetry.lastReceivedStatus != lastLoggedImuStatusLine
                ) {
                    withContext(Dispatchers.IO) {
                        imuTelemetryLogStore.append(telemetry, phoneHeading)
                    }?.also {
                        lastLoggedImuStatusLine = telemetry.lastReceivedStatus
                    }
                } else {
                    null
                }
                mutableUiState.update {
                    val declination = if (mutableSettingsState.value.navigationGpsSource == NavigationGpsSource.Esp32) {
                        magneticDeclinationDegreesFromTelemetry(telemetry)
                    } else {
                        it.magneticDeclinationDegrees
                    }
                    it.copy(
                        telemetry = telemetry,
                        magneticDeclinationDegrees = declination,
                        imuTelemetryLogPath = logSnapshot?.filePath ?: it.imuTelemetryLogPath,
                        imuTelemetryLogSampleCount = logSnapshot?.sampleCount ?: it.imuTelemetryLogSampleCount,
                    )
                }
            }
        }
    }

    private fun clearStaleHeadingLockUiIfNeeded(fields: Map<String, String>) {
        val isFullStatus = fields.containsKey("L") || fields.containsKey("R") || fields.containsKey("LPWM")
        if (!isFullStatus) {
            return
        }
        val controllerHeadingLockActive = fields["HLOCK"] == "ACTIVE" || fields["MODE"] == "HEADING_LOCK"
        if (controllerHeadingLockActive) {
            return
        }
        if (activeHeadingLockCommand != null || activeTurnCommand != null || activeHeadingLockStartPending) {
            return
        }
        val state = mutableUiState.value
        if (
            !state.headingLockEnabled &&
            state.appHeadingLockTargetDegrees == null &&
            state.appHeadingLockErrorDegrees == null &&
            state.appHeadingLeftOutputPercent == null &&
            state.appHeadingRightOutputPercent == null
        ) {
            return
        }
        mutableUiState.update {
            it.copy(
                headingLockEnabled = false,
                appHeadingLockTargetDegrees = null,
                appHeadingLockErrorDegrees = null,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
                statusMessage = "主控当前为普通油门，已清除本地锁航显示",
            )
        }
    }

    private fun handleControllerInfoFields(fields: Map<String, String>) {
        val firmwareVersion = fields["FW"]?.takeIf { it.isNotBlank() } ?: return
        mutableUpdateState.update {
            it.copy(
                currentEsp32FirmwareVersion = firmwareVersion,
                esp32UpdateAvailable = isEsp32FirmwareUpdateAvailable(
                    targetVersion = it.targetEsp32FirmwareVersion,
                    currentVersion = firmwareVersion,
                ) && it.firmwareDownloadUrl != null && it.firmwareManifestName != null,
            )
        }
        handlePendingOtaFirmwareVerification(firmwareVersion = firmwareVersion, allowMismatch = true)
    }

    private fun handlePendingOtaFirmwareVerification(firmwareVersion: String, allowMismatch: Boolean) {
        val targetVersion = pendingEsp32FirmwareVersion
        if (!esp32OtaExclusive) {
            return
        }

        if (!esp32OtaAccepted) {
            mutableUpdateState.update {
                if (it.esp32Uploading) {
                    it
                } else {
                    it.copy(message = "等待主控 OTA 完成；当前仍为 $firmwareVersion")
                }
            }
            return
        }

        if (!otaReconnectSawDisconnect) {
            mutableUpdateState.update {
                it.copy(
                    message = targetVersion?.let { target ->
                        "主控固件校验通过；等待断开后每 3 秒自动重连验证 FW=$target"
                    } ?: "主控固件校验通过；等待断开后每 3 秒自动重连确认版本",
                )
            }
            return
        }

        if (targetVersion == null) {
            esp32OtaExclusive = false
            esp32OtaAccepted = false
            stopOtaReconnectLoop()
            mutableUpdateState.update {
                it.copy(message = "主控已重连，当前固件 $firmwareVersion")
            }
            startCommandHeartbeat()
            sendIdle()
            requestTrackLogSync()
            return
        }

        if (sameVersion(firmwareVersion, targetVersion)) {
            pendingEsp32FirmwareVersion = null
            esp32OtaExclusive = false
            esp32OtaAccepted = false
            stopOtaReconnectLoop()
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = false,
                    esp32UpdateAvailable = false,
                    message = "主控固件已验证：$firmwareVersion",
                    progressText = "",
                )
            }
            mutableUiState.update {
                it.copy(statusMessage = "主控固件已更新到 $firmwareVersion，系统保持锁定")
            }
            startCommandHeartbeat()
            sendIdle()
            requestTrackLogSync()
            speakVoiceReply("主控固件已更新")
        } else {
            if (!allowMismatch) {
                mutableUpdateState.update {
                    it.copy(message = "已重连主控，等待主控回报 FW=$targetVersion")
                }
                return
            }
            pendingEsp32FirmwareVersion = null
            esp32OtaExclusive = false
            esp32OtaAccepted = false
            stopOtaReconnectLoop()
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = false,
                    message = "主控固件版本验证失败：当前 $firmwareVersion，目标 $targetVersion",
                    progressText = "",
                )
            }
            startCommandHeartbeat()
            sendIdle()
        }
    }

    private fun handleOtaStatusLine(line: String) {
        if (!line.startsWith("OTA;")) {
            return
        }
        when {
            line.startsWith("OTA;READY") -> {
                mutableUpdateState.update {
                    it.copy(message = "主控已进入 OTA 接收模式")
                }
            }
            line.startsWith("OTA;PROGRESS=") -> {
                mutableUpdateState.update {
                    it.copy(message = "主控正在写入固件", progressText = line.removePrefix("OTA;PROGRESS="))
                }
            }
            line.startsWith("OTA;OK") -> {
                esp32OtaAccepted = true
                startOtaReconnectLoop()
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = pendingEsp32FirmwareVersion?.let { target ->
                            "主控固件校验通过，设备正在重启；每 3 秒自动重连验证 FW=$target"
                        } ?: "主控固件校验通过，设备正在重启；每 3 秒自动重连确认版本",
                        progressText = "",
                    )
                }
            }
            line.startsWith("OTA;REBOOTING") -> {
                Log.i(TAG, "OTA rebooting event received; start reconnect loop")
                esp32OtaAccepted = true
                otaReconnectSawDisconnect = true
                transport = null
                bluetoothConnectJob?.cancel()
                telemetryJob?.cancel()
                trackLogJob?.cancel()
                trackLogSyncJob?.cancel()
                commandHeartbeatJob?.cancel()
                mutableUiState.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        armed = false,
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.App,
                        headingLockEnabled = false,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        statusMessage = "主控 OTA 后蓝牙已断开，等待自动重连",
                    )
                }
                startOtaReconnectLoop(forceRestart = true)
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = pendingEsp32FirmwareVersion?.let { target ->
                            "主控正在重启；每 3 秒自动重连验证 FW=$target"
                        } ?: "主控正在重启；每 3 秒自动重连确认固件版本",
                        progressText = "",
                    )
                }
            }
            line.startsWith("OTA;ERR=") -> {
                esp32OtaExclusive = false
                esp32OtaAccepted = false
                pendingEsp32FirmwareVersion = null
                stopOtaReconnectLoop()
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "主控固件更新失败：${line.removePrefix("OTA;ERR=")}",
                        progressText = "",
                    )
                }
            }
        }
    }

    private fun collectTrackLogEvents(nextTransport: ControlTransport) {
        trackLogJob?.cancel()
        trackLogJob = viewModelScope.launch {
            nextTransport.trackLogEvents.collect { event ->
                handleTrackLogEvent(event)
            }
        }
    }

    private suspend fun handleTrackLogEvent(event: TrackLogEvent) {
        when (event) {
            is TrackLogEvent.Info -> handleTrackLogInfo(event.info.oldestSequence, event.info.newestSequence, event.info)
            is TrackLogEvent.Begin -> {
                trackLogBatchPoints.clear()
                mutableUiState.update {
                    val start = it.gpsTrack.syncStartSequence
                    val target = it.gpsTrack.syncTargetSequence
                    val current = event.fromSequence - 1
                    it.copy(
                        gpsTrack = it.gpsTrack.copy(
                            syncing = true,
                            syncCurrentSequence = current,
                            syncMessage = if (start != null && target != null) {
                                val total = (target - start + 1).coerceAtLeast(1)
                                val done = (current - start + 1).coerceIn(0, total)
                                "正在读取 ${event.count} 点，本次进度 $done/$total"
                            } else {
                                "正在读取 ${event.count} 个轨迹点"
                            },
                        ),
                    )
                }
            }
            is TrackLogEvent.Point -> trackLogBatchPoints += event.point
            is TrackLogEvent.End -> handleTrackLogEnd(event.nextSequence)
            is TrackLogEvent.Error -> {
                trackLogBatchPoints.clear()
                refreshGpsTrackState("轨迹同步错误：${event.message}")
            }
        }
    }

    private fun handleTrackLogInfo(oldestSequence: Int, newestSequence: Int, info: com.smartsup.controller.model.TrackLogInfo) {
        val nextTransport = transport ?: return
        val localLastSequence = gpsTrackStore.lastSyncedSequence()
        val nextFrom = if (gpsTrackStore.hasAnyPoint() && newestSequence < localLastSequence) {
            oldestSequence.coerceAtLeast(1)
        } else {
            (localLastSequence + 1).coerceAtLeast(oldestSequence.coerceAtLeast(1))
        }
        val noNewTrackMessage = gpsTrackNoNewMessage(info)
        mutableUiState.update {
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    latestInfo = info,
                    syncing = nextFrom <= newestSequence,
                    syncStartSequence = if (nextFrom <= newestSequence) nextFrom else null,
                    syncTargetSequence = if (nextFrom <= newestSequence) newestSequence else null,
                    syncCurrentSequence = if (nextFrom <= newestSequence) nextFrom - 1 else null,
                    syncMessage = if (nextFrom <= newestSequence) {
                        "准备同步 seq $nextFrom..$newestSequence"
                    } else {
                        noNewTrackMessage
                    },
                ),
            )
        }
        if (nextFrom <= newestSequence) {
            sendTrackLogRead(nextTransport, nextFrom)
        } else {
            refreshGpsTrackState(noNewTrackMessage)
        }
    }

    private fun gpsTrackNoNewMessage(info: com.smartsup.controller.model.TrackLogInfo): String {
        val fields = mutableUiState.value.telemetry.statusFields
        val fix = fields["GPS_FIX"]
        val satellites = fields["GPS_SAT"] ?: "--"
        val antenna = fields["GPS_ANT"] ?: "UNKNOWN"
        val trackCount = fields["TRK_COUNT"] ?: info.count.toString()

        if (fix != "1") {
            return "主控没有新增有效轨迹：GPS 未定位，卫星 $satellites，天线 $antenna，缓存 $trackCount/${info.capacity}"
        }
        if (info.count == 0) {
            return "主控轨迹缓存为空：GPS 已定位但还没有写入轨迹点"
        }
        return "主控暂无新轨迹点，缓存 $trackCount/${info.capacity}"
    }

    private suspend fun handleTrackLogEnd(nextSequence: Int) {
        flushTrackLogBatch()
        val info = mutableUiState.value.gpsTrack.latestInfo
        val nextTransport = transport
        if (info != null && nextTransport != null && nextSequence in 1..info.newestSequence) {
            mutableUiState.update {
                it.copy(
                    gpsTrack = it.gpsTrack.copy(
                        syncing = true,
                        syncMessage = "继续同步 seq $nextSequence..${info.newestSequence}",
                    ),
                )
            }
            sendTrackLogRead(nextTransport, nextSequence)
        } else {
            refreshGpsTrackState("轨迹同步完成")
        }
    }

    private suspend fun flushTrackLogBatch() {
        if (trackLogBatchPoints.isEmpty()) {
            return
        }
        val points = trackLogBatchPoints.toList()
        trackLogBatchPoints.clear()
        val savedCount = withContext(Dispatchers.IO) {
            gpsTrackStore.appendAllIfNew(points)
        }
        val lastSequence = points.maxOf { it.sequence }
        mutableUiState.update {
            val start = it.gpsTrack.syncStartSequence
            val target = it.gpsTrack.syncTargetSequence
            val total = if (start != null && target != null) {
                (target - start + 1).coerceAtLeast(1)
            } else {
                null
            }
            val done = if (start != null && total != null) {
                (lastSequence - start + 1).coerceIn(0, total)
            } else {
                null
            }
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    syncing = true,
                    syncCurrentSequence = lastSequence,
                    storedPointCount = it.gpsTrack.storedPointCount + savedCount,
                    lastSyncedSequence = maxOf(it.gpsTrack.lastSyncedSequence, lastSequence),
                    syncMessage = if (done != null && total != null) {
                        "同步中 $done/$total，seq $lastSequence"
                    } else {
                        "已同步到 seq $lastSequence"
                    },
                ),
            )
        }
    }

    private fun sendTrackLogRead(nextTransport: ControlTransport, fromSequence: Int) {
        if (esp32OtaExclusive) {
            refreshGpsTrackState("主控 OTA 进行中，暂不同步轨迹")
            return
        }
        trackLogSyncJob?.cancel()
        trackLogSyncJob = viewModelScope.launch {
            delay(TRACK_LOG_READ_DELAY_MS)
            runCatching {
                nextTransport.sendRawLine("LOG_READ;FROM=$fromSequence;LIMIT=$TRACK_LOG_READ_LIMIT")
            }.onFailure { error ->
                refreshGpsTrackState("轨迹读取失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun refreshGpsTrackState(message: String, syncing: Boolean = false) {
        val tracks = gpsTrackStore.readTracks()
        val currentSelectedTrackId = mutableUiState.value.gpsTrack.selectedTrackId
        val selectedTrackId = currentSelectedTrackId
            ?.takeIf { id -> tracks.any { it.id == id } }
            ?: tracks.lastOrNull()?.id
        val points = gpsTrackStore.readTrackPoints(selectedTrackId)
        val pointCount = gpsTrackStore.pointCount()
        val lastSynced = gpsTrackStore.lastSyncedSequence()
        mutableUiState.update {
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    storedPointCount = pointCount,
                    lastSyncedSequence = lastSynced,
                    syncMessage = message,
                    syncing = syncing,
                    tracks = tracks,
                    selectedTrackId = selectedTrackId,
                    recentPoints = points,
                    playbackIndex = if (points.isEmpty()) {
                        0
                    } else {
                        it.gpsTrack.playbackIndex.coerceIn(0, points.lastIndex)
                    },
                ),
            )
        }
    }

    private fun appendSentCommandLog(command: ControlCommand) {
        val line = command.toWireLine()
        if (line == lastLoggedSentCommand) {
            return
        }
        lastLoggedSentCommand = line
        appendControlLog(
            level = ControlLogLevel.Info,
            title = "下发命令",
            message = summarizeCommandLine(line),
            rawLine = line,
        )
    }

    private fun appendTelemetryLog(telemetry: Telemetry) {
        val line = telemetry.lastReceivedStatus
        if (line.isBlank() || line == "--") {
            return
        }
        val fields = parseControlLogFields(line)
        val faultCode = fields["FAULT"]
        if (line.startsWith("ERR;")) {
            if (line != lastLoggedReceivedLine) {
                lastLoggedReceivedLine = line
                val errorCode = controllerErrorCode(line)
                val detailText = controllerErrorDetailText(line)
                appendControlLog(
                    level = ControlLogLevel.Error,
                    title = "主控错误",
                    message = listOf(controllerFaultText(errorCode), detailText)
                        .filter { it.isNotBlank() }
                        .joinToString("；"),
                    rawLine = line,
                )
            }
            return
        }
        if (faultCode != null) {
            if (line != lastLoggedReceivedLine) {
                lastLoggedReceivedLine = line
                val faultArmed = fields["ARMED"] == "1"
                appendControlLog(
                    level = ControlLogLevel.Error,
                    title = if (faultArmed) "主控退出自主控制" else "主控退出/锁定",
                    message = controllerFaultText(faultCode),
                    rawLine = line,
                )
            }
            return
        }

        fields["ARMED"]?.let { armedValue ->
            val nextArmed = armedValue == "1"
            val uiArmed = mutableUiState.value.armed
            val unrequestedLock = !nextArmed &&
                uiArmed &&
                pendingControllerArmedRequest != false
            val unrequestedUnlock = nextArmed &&
                !uiArmed &&
                pendingControllerArmedRequest != true
            if (!unrequestedLock && !unrequestedUnlock && lastLoggedArmedState != nextArmed) {
                lastLoggedArmedState = nextArmed
                appendControlLog(
                    level = if (nextArmed) ControlLogLevel.Info else ControlLogLevel.Warning,
                    title = if (nextArmed) "主控已解锁" else "主控已锁定",
                    message = if (nextArmed) "主控回传 ARMED=1" else "主控回传 ARMED=0，未携带 FAULT",
                    rawLine = line,
                )
            }
        }

        fields["HSRC"]?.let { headingSource ->
            if (lastLoggedHeadingSource != headingSource) {
                lastLoggedHeadingSource = headingSource
                val detailText = headingSourceDetailText(fields)
                appendControlLog(
                    level = ControlLogLevel.Info,
                    title = "航向来源",
                    message = listOf("主控当前航向来源：$headingSource", detailText)
                        .filter { it.isNotBlank() }
                        .joinToString("；"),
                    rawLine = line,
                )
            }
        }

        val headingLockState = fields["HLOCK"]
        if (headingLockState != null) {
            val key = when (headingLockState) {
                "ACTIVE" -> "ACTIVE:${fields["HID"].orEmpty()}"
                else -> "$headingLockState:${fields["HID"].orEmpty()}:${fields["TARGET"].orEmpty()}"
            }
            val unrequestedHeadingLockOff = headingLockState == "OFF" &&
                fields["FAULT"].isNullOrBlank() &&
                (activeHeadingLockCommand != null || mutableUiState.value.headingLockEnabled)
            if (!unrequestedHeadingLockOff && lastLoggedHeadingLockState != key) {
                lastLoggedHeadingLockState = key
                appendControlLog(
                    level = if (headingLockState == "OFF") ControlLogLevel.Warning else ControlLogLevel.Info,
                    title = "锁航状态",
                    message = headingLockLogMessage(headingLockState, fields),
                    rawLine = line,
                )
            }
        }

        fields["TURN"]?.let { turnState ->
            val key = "TURN:$turnState:${fields["TID"].orEmpty()}:${fields["TARGET"].orEmpty()}:${fields["ERR"].orEmpty()}"
            if (lastLoggedReceivedLine != key) {
                lastLoggedReceivedLine = key
                appendControlLog(
                    level = if (turnState == "TIMEOUT") ControlLogLevel.Warning else ControlLogLevel.Info,
                    title = "主控转向",
                    message = when (turnState) {
                        "START" -> "主控开始执行角度转向"
                        "ACTIVE" -> listOfNotNull(
                            fields["TARGET"]?.let { "目标=$it°" },
                            fields["TERR"]?.let { "误差=$it°" },
                        ).joinToString("；").ifBlank { "主控转向运行中" }
                        "APPLIED" -> "主控已把角度转向应用到锁航目标"
                        "DONE" -> "主控角度转向完成"
                        "TIMEOUT" -> "主控角度转向超时"
                        else -> "TURN=$turnState"
                    },
                    rawLine = line,
                )
            }
        }

        fields["HWARN"]?.let { warning ->
            val warningKey = "$warning:${fields["HID"].orEmpty()}:${fields["HBOOST"].orEmpty()}:${fields["HERR"].orEmpty()}"
            if (lastLoggedHeadingLockWarning != warningKey) {
                lastLoggedHeadingLockWarning = warningKey
                appendControlLog(
                    level = ControlLogLevel.Warning,
                    title = "锁航告警",
                    message = headingLockWarningLogMessage(warning, fields),
                    rawLine = line,
                )
            }
        }

        appendHeadingLockSampleLogIfNeeded(fields)

        if (line.startsWith("INFO;") && line != lastLoggedReceivedLine) {
            lastLoggedReceivedLine = line
            appendControlLog(
                level = ControlLogLevel.Info,
                title = "主控信息",
                message = listOfNotNull(
                    fields["FW"]?.let { "FW=$it" },
                    fields["ID"]?.let { "ID=$it" },
                    fields["BT"]?.let { "BT=$it" },
                ).joinToString("；").ifBlank { "收到主控信息" },
                rawLine = line,
            )
        }
    }

    private fun appendHeadingLockSampleLogIfNeeded(fields: Map<String, String>) {
        if (fields["HLOCK"] != "ACTIVE") {
            lastHeadingLockSample = null
            lastHeadingLockSampleLogMs = 0L
            return
        }

        val state = mutableUiState.value
        val userLeftPercent = fields["L"]?.toIntOrNull() ?: state.telemetry.leftOutputPercent
        val userRightPercent = fields["R"]?.toIntOrNull() ?: state.telemetry.rightOutputPercent
        val sample = HeadingLockSample(
            hid = fields["HID"].orEmpty(),
            headingDegrees = fields["YBHDG"]?.toFloatOrNull() ?: fields["HDG"]?.toFloatOrNull(),
            targetDegrees = fields["TARGET"]?.toFloatOrNull(),
            errorDegrees = fields["HERR"]?.toFloatOrNull(),
            leftUserPercent = userLeftPercent,
            rightUserPercent = userRightPercent,
            gearLabel = state.selectedGear.label,
            gearBasePercent = selectedGearThrottle(state.selectedGear, state.throttleTrimPercent),
            phase = fields["HPHASE"].orEmpty(),
            correctionPercent = fields["HCORR"]?.toIntOrNull(),
            boostPercent = fields["HBOOST"]?.toIntOrNull(),
            referenceRateDegS = fields["HREF"]?.toFloatOrNull(),
            rateErrorDegS = fields["HRERR"]?.toFloatOrNull(),
            rateDotDegS2 = fields["HRDOT"]?.toFloatOrNull(),
            innerPercent = fields["HINNER"]?.toFloatOrNull(),
            disturbancePercent = fields["HFHAT"]?.toFloatOrNull(),
        )
        val now = System.currentTimeMillis()
        val previous = lastHeadingLockSample
        val elapsedMs = now - lastHeadingLockSampleLogMs
        val shouldLog = previous == null ||
            elapsedMs >= HEADING_LOCK_SAMPLE_LOG_INTERVAL_MS ||
            (
                elapsedMs >= HEADING_LOCK_SAMPLE_LOG_MIN_CHANGE_INTERVAL_MS &&
                    sample.hasMaterialChangeFrom(previous)
                )
        if (!shouldLog) {
            return
        }

        lastHeadingLockSample = sample
        lastHeadingLockSampleLogMs = now
        appendControlLog(
            level = ControlLogLevel.Info,
            title = "锁航采样",
            message = sample.toLogMessage(),
            rawLine = sample.toCompactLine(),
        )
    }

    private fun appendControlLog(
        level: ControlLogLevel,
        title: String,
        message: String,
        rawLine: String? = null,
    ) {
        val entry = ControlLogEntry(
            id = nextControlLogId++,
            timestampMs = System.currentTimeMillis(),
            level = level,
            title = title,
            message = message,
            rawLine = rawLine,
        )
        mutableUiState.update {
            it.copy(controlLog = (listOf(entry) + it.controlLog).take(MAX_CONTROL_LOG_ENTRIES))
        }
        viewModelScope.launch(Dispatchers.IO) {
            controlLogFileStore.append(entry)
        }
    }

    private fun appendRateLimitedUnrequestedControllerLockLog(line: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnrequestedControllerLockLogMs < UNREQUESTED_STATE_LOG_INTERVAL_MS) {
            return
        }
        lastUnrequestedControllerLockLogMs = now
        appendControlLog(
            level = ControlLogLevel.Warning,
            title = "拒绝无授权锁定",
            message = "已解锁状态下收到无故 ARMED=0；继续维持用户解锁目标",
            rawLine = line,
        )
    }

    private fun appendRateLimitedUnrequestedControllerUnlockLog(line: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnrequestedControllerUnlockLogMs < UNREQUESTED_STATE_LOG_INTERVAL_MS) {
            return
        }
        lastUnrequestedControllerUnlockLogMs = now
        appendControlLog(
            level = ControlLogLevel.Warning,
            title = "拒绝无授权解锁",
            message = "锁定状态下收到无授权 ARMED=1；继续维持用户锁定目标",
            rawLine = line,
        )
    }

    private fun appendRateLimitedUnrequestedHeadingLockOffLog(line: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnrequestedHeadingLockOffLogMs < UNREQUESTED_STATE_LOG_INTERVAL_MS) {
            return
        }
        lastUnrequestedHeadingLockOffLogMs = now
        appendControlLog(
            level = ControlLogLevel.Warning,
            title = "拒绝无授权退出锁航",
            message = "锁航状态下收到无故 HLOCK=OFF；继续维持用户锁航目标",
            rawLine = line,
        )
    }

    private fun parseControlLogFields(line: String): Map<String, String> {
        return line.split(';')
            .drop(1)
            .mapNotNull { token ->
                val index = token.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    token.substring(0, index) to token.substring(index + 1)
                }
            }
            .toMap()
    }

    private fun summarizeCommandLine(line: String): String {
        val fields = parseControlLogFields("CMD;$line")
        val mode = fields["MODE"] ?: "THROTTLE"
        val source = fields["SRC"] ?: "APP"
        val armed = fields["ARM"] ?: "0"
        val parts = mutableListOf("SRC=$source", "ARM=$armed", "MODE=$mode")
        fields["HLOCK"]?.let { parts += "HLOCK=$it" }
        fields["BASE"]?.let { parts += "BASE=$it%" }
        fields["HID"]?.let { parts += "HID=$it" }
        fields["H_SRC"]?.let { parts += "H_SRC=$it" }
        if (mode == "THROTTLE") {
            fields["L"]?.let { parts += "L=$it%" }
            fields["R"]?.let { parts += "R=$it%" }
        }
        return parts.joinToString("；")
    }

    private fun headingLockLogMessage(
        state: String,
        fields: Map<String, String>,
    ): String {
        return when (state) {
            "START" -> listOfNotNull(
                "锁航开始",
                fields["HID"]?.let { "HID=$it" },
                fields["TARGET"]?.let { "目标=$it°" },
            ).joinToString("；")
            "ACTIVE" -> listOfNotNull(
                "锁航运行中",
                fields["HID"]?.let { "HID=$it" },
                fields["TARGET"]?.let { "目标=$it°" },
                fields["HERR"]?.let { "误差=$it°" },
                fields["HREF"]?.let { "目标角速=$it°/s" },
                fields["HRATE"]?.let { "角速=$it°/s" },
                fields["HFHAT"]?.let { "扰动=$it%" },
                fields["HCORR"]?.let { "修正=$it%" },
            ).joinToString("；")
            "OFF" -> "锁航关闭，主控回传 HLOCK=OFF"
            else -> "锁航状态：$state"
        }
    }

    private fun headingLockWarningLogMessage(
        warning: String,
        fields: Map<String, String>,
    ): String {
        return when (warning) {
            "HEADING_LOCK_DIVERGED" -> listOfNotNull(
                "锁航误差扩大，主控继续锁航并回到保守差速",
                fields["HERR"]?.let { "误差=$it°" },
                fields["HCORR"]?.let { "修正=$it%" },
                fields["HFHAT"]?.let { "扰动=$it%" },
            ).joinToString("；")
            else -> listOfNotNull(
                "锁航告警：$warning",
                fields["HERR"]?.let { "误差=$it°" },
                fields["HFHAT"]?.let { "扰动=$it%" },
            ).joinToString("；")
        }
    }

    private data class HeadingLockSample(
        val hid: String,
        val headingDegrees: Float?,
        val targetDegrees: Float?,
        val errorDegrees: Float?,
        val leftUserPercent: Int?,
        val rightUserPercent: Int?,
        val gearLabel: String,
        val gearBasePercent: Int,
        val phase: String,
        val correctionPercent: Int?,
        val boostPercent: Int?,
        val referenceRateDegS: Float?,
        val rateErrorDegS: Float?,
        val rateDotDegS2: Float?,
        val innerPercent: Float?,
        val disturbancePercent: Float?,
    ) {
        fun hasMaterialChangeFrom(previous: HeadingLockSample): Boolean {
            return hid != previous.hid ||
                gearLabel != previous.gearLabel ||
                phase != previous.phase ||
                floatDeltaAtLeast(errorDegrees, previous.errorDegrees, 3.0f) ||
                intDeltaAtLeast(leftUserPercent, previous.leftUserPercent, 5) ||
                intDeltaAtLeast(rightUserPercent, previous.rightUserPercent, 5) ||
                intDeltaAtLeast(correctionPercent, previous.correctionPercent, 5) ||
                intDeltaAtLeast(boostPercent, previous.boostPercent, 5) ||
                floatDeltaAtLeast(referenceRateDegS, previous.referenceRateDegS, 5.0f) ||
                floatDeltaAtLeast(disturbancePercent, previous.disturbancePercent, 5.0f)
        }

        fun toLogMessage(): String {
            return listOf(
                "船头=${headingDegrees.degreesText()}",
                "目标=${targetDegrees.degreesText()}",
                "误差=${errorDegrees.signedDegreesText()}",
                "左=${leftUserPercent.signedPercentText()}",
                "右=${rightUserPercent.signedPercentText()}",
                "档位=$gearLabel(${gearBasePercent.signedPercentText()})",
                if (phase.isNotBlank()) "阶段=$phase" else null,
                referenceRateDegS?.let { "目标角速=${it.signedRateText()}" },
                rateErrorDegS?.let { "角速误差=${it.signedRateText()}" },
                rateDotDegS2?.let { "角加速=${it.signedAccelText()}" },
                innerPercent?.let { "内环=${it.signedPercentFloatText()}" },
                disturbancePercent?.let { "扰动=${it.signedPercentFloatText()}" },
                correctionPercent?.let { "修正=${it.signedPercentText()}" },
            ).filterNotNull().joinToString("；")
        }

        fun toCompactLine(): String {
            return listOf(
                "HLOCK_SAMPLE",
                "HID=${hid.ifBlank { "--" }}",
                "HDG=${headingDegrees.numberText()}",
                "TARGET=${targetDegrees.numberText()}",
                "HERR=${errorDegrees.numberText()}",
                "L_USER=${leftUserPercent?.toString() ?: "--"}",
                "R_USER=${rightUserPercent?.toString() ?: "--"}",
                "GEAR=$gearLabel",
                "GBASE=$gearBasePercent",
                "HPHASE=${phase.ifBlank { "--" }}",
                "HREF=${referenceRateDegS.numberText()}",
                "HRERR=${rateErrorDegS.numberText()}",
                "HRDOT=${rateDotDegS2.numberText()}",
                "HINNER=${innerPercent.numberText()}",
                "HFHAT=${disturbancePercent.numberText()}",
                "HCORR=${correctionPercent?.toString() ?: "--"}",
                "HBOOST=${boostPercent?.toString() ?: "--"}",
            ).joinToString(";")
        }

        private fun Float?.degreesText(): String {
            return this?.let { "%.1f°".format(Locale.US, it) } ?: "--"
        }

        private fun Float?.signedDegreesText(): String {
            return this?.let {
                if (it > 0f) "+%.1f°".format(Locale.US, it) else "%.1f°".format(Locale.US, it)
            } ?: "--"
        }

        private fun Float?.numberText(): String {
            return this?.let { "%.1f".format(Locale.US, it) } ?: "--"
        }

        private fun Int?.signedPercentText(): String {
            return this?.let { if (it > 0) "+$it%" else "$it%" } ?: "--"
        }

        private fun Float.signedPercentFloatText(): String {
            return if (this > 0f) "+%.1f%%".format(Locale.US, this) else "%.1f%%".format(Locale.US, this)
        }

        private fun Float.signedRateText(): String {
            return if (this > 0f) "+%.1f°/s".format(Locale.US, this) else "%.1f°/s".format(Locale.US, this)
        }

        private fun Float.signedAccelText(): String {
            return if (this > 0f) "+%.1f°/s²".format(Locale.US, this) else "%.1f°/s²".format(Locale.US, this)
        }

        private fun floatDeltaAtLeast(left: Float?, right: Float?, threshold: Float): Boolean {
            if (left == null || right == null) {
                return left != right
            }
            return abs(left - right) >= threshold
        }

        private fun intDeltaAtLeast(left: Int?, right: Int?, threshold: Int): Boolean {
            if (left == null || right == null) {
                return left != right
            }
            return abs(left - right) >= threshold
        }
    }

    private fun handleControllerAuthorityLine(line: String) {
        if (!line.startsWith("STATUS;")) {
            return
        }
        val fields = parseControlLogFields(line)
        val armedValue = fields["ARMED"] ?: return
        val controllerArmed = when (armedValue) {
            "1" -> true
            "0" -> false
            else -> return
        }
        val currentState = mutableUiState.value
        val pendingRequest = pendingControllerArmedRequest
        if (pendingRequest != null) {
            val now = System.currentTimeMillis()
            if (controllerArmed == pendingRequest) {
                pendingControllerArmedRequest = null
                pendingControllerArmedRequestUntilMs = 0L
                applyControllerArmedState(
                    controllerArmed = controllerArmed,
                    fields = fields,
                    announce = true,
                )
                return
            }
            if (now <= pendingControllerArmedRequestUntilMs) {
                mutableUiState.update {
                    it.copy(
                        statusMessage = if (pendingRequest) {
                            "等待主控解锁确认：当前仍回传 ARMED=0"
                        } else {
                            "等待主控锁定确认：当前仍回传 ARMED=1"
                        },
                    )
                }
                return
            }
            pendingControllerArmedRequest = null
            pendingControllerArmedRequestUntilMs = 0L
            mutableUiState.update {
                it.copy(
                    statusMessage = if (pendingRequest) {
                        "主控未确认解锁，当前仍为锁定"
                    } else {
                        "主控未确认锁定，当前仍为解锁"
                    },
                )
            }
            speakVoiceReply(if (pendingRequest) "主控未确认解锁" else "主控未确认锁定")
        }

        val faultText = fields["FAULT"]?.let(::controllerFaultText)
        if (!controllerArmed && currentState.armed && pendingControllerArmedRequest != false && faultText == null) {
            handleUnrequestedControllerLock(line)
            return
        }
        if (controllerArmed && !currentState.armed && pendingControllerArmedRequest != true) {
            handleUnrequestedControllerUnlock(line)
            return
        }

        if (controllerArmed == currentState.armed) {
            return
        }

        applyControllerArmedState(
            controllerArmed = controllerArmed,
            fields = fields,
            announce = false,
        )
    }

    private fun handleUnrequestedControllerLock(line: String) {
        pendingControllerArmedRequest = true
        pendingControllerArmedRequestUntilMs = System.currentTimeMillis() + CONTROLLER_ARM_CONFIRM_TIMEOUT_MS
        appendRateLimitedUnrequestedControllerLockLog(line)
        mutableUiState.update {
            it.copy(
                statusMessage = "主控无授权回传 ARMED=0；按用户规则继续维持解锁目标",
            )
        }
        sendCurrentCommand()
    }

    private fun handleUnrequestedControllerUnlock(line: String) {
        pendingControllerArmedRequest = false
        pendingControllerArmedRequestUntilMs = System.currentTimeMillis() + CONTROLLER_ARM_CONFIRM_TIMEOUT_MS
        appendRateLimitedUnrequestedControllerUnlockLog(line)
        mutableUiState.update {
            it.copy(
                statusMessage = "主控无授权回传 ARMED=1；按用户规则继续维持锁定目标",
            )
        }
        sendCurrentCommand()
    }

    private fun applyControllerArmedState(
        controllerArmed: Boolean,
        fields: Map<String, String>,
        announce: Boolean,
    ) {
        if (!controllerArmed) {
            clearAutonomousCommands()
            val faultText = fields["FAULT"]?.let(::controllerFaultText)
            mutableUiState.update {
                it.copy(
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    appHeadingLockTargetDegrees = null,
                    appHeadingLockErrorDegrees = null,
                    appHeadingLockCorrectionPercent = 0,
                    appHeadingLeftOutputPercent = null,
                    appHeadingRightOutputPercent = null,
                    appHeadingLeftCommandPercent = null,
                    appHeadingRightCommandPercent = null,
                    statusMessage = faultText?.let { reason ->
                        "主控已锁定：$reason"
                    } ?: "主控回传 ARMED=0，App 已同步为锁定",
                )
            }
            if (announce) {
                speakVoiceReply(faultText?.let { "主控已锁定，$it" } ?: "主控已锁定")
            }
            return
        }

        if (mutableUiState.value.connectionState == ConnectionState.Connected) {
            mutableUiState.update {
                it.copy(
                    armed = true,
                    statusMessage = "主控回传 ARMED=1，App 已同步为解锁",
                )
            }
            if (announce) {
                speakVoiceReply("主控已解锁")
            }
        }
    }

    private fun handleControllerHeadingLockLine(line: String) {
        if (!line.startsWith("STATUS;")) {
            return
        }
        val fields = parseControlLogFields(line)
        val headingLockState = fields["HLOCK"] ?: return
        when (headingLockState) {
            "START",
            "ACTIVE" -> {
                val target = fields["TARGET"]?.toFloatOrNull()
                val error = fields["HERR"]?.toFloatOrNull()
                val correction = fields["HCORR"]?.toFloatOrNull()?.roundToInt()
                mutableUiState.update {
                    it.copy(
                        headingLockEnabled = true,
                        appHeadingLockTargetDegrees = target ?: it.appHeadingLockTargetDegrees,
                        appHeadingLockErrorDegrees = error ?: it.appHeadingLockErrorDegrees,
                        appHeadingLockCorrectionPercent = correction ?: it.appHeadingLockCorrectionPercent,
                        statusMessage = if (headingLockState == "START") {
                            "主控已开始航向锁定"
                        } else {
                            "主控航向锁定运行中"
                        },
                    )
                }
            }
            "OFF" -> {
                if (activeHeadingLockCommand == null && !mutableUiState.value.headingLockEnabled) {
                    return
                }
                if (fields["FAULT"].isNullOrBlank()) {
                    appendRateLimitedUnrequestedHeadingLockOffLog(line)
                    mutableUiState.update {
                        it.copy(
                            headingLockEnabled = true,
                            statusMessage = "主控无故回传 HLOCK=OFF；按用户规则继续维持航向锁定目标",
                        )
                    }
                    sendCurrentCommand()
                    return
                }
                clearActiveHeadingLockCommand()
                mutableUiState.update {
                    it.copy(
                        commandSource = CommandSource.App,
                        headingLockEnabled = false,
                        appHeadingLockTargetDegrees = null,
                        appHeadingLockErrorDegrees = null,
                        appHeadingLockCorrectionPercent = 0,
                        appHeadingLeftOutputPercent = null,
                        appHeadingRightOutputPercent = null,
                        appHeadingLeftCommandPercent = null,
                        appHeadingRightCommandPercent = null,
                        statusMessage = "主控回传 HLOCK=OFF，App 已同步为未锁航",
                    )
                }
            }
        }
    }

    private fun handleControllerTurnLine(line: String) {
        if (!line.startsWith("STATUS;")) {
            return
        }
        val fields = parseControlLogFields(line)
        val turnState = fields["TURN"] ?: return
        when (turnState) {
            "START" -> {
                mutableUiState.update {
                    it.copy(
                        commandSource = CommandSource.Voice,
                        statusMessage = "主控已开始角度转向",
                    )
                }
            }
            "APPLIED" -> {
                clearActiveTurnCommand()
                mutableUiState.update {
                    it.copy(
                        commandSource = CommandSource.Voice,
                        headingLockEnabled = true,
                        appHeadingLockTargetDegrees = fields["TARGET"]?.toFloatOrNull() ?: it.appHeadingLockTargetDegrees,
                        statusMessage = "主控已更新锁航目标",
                    )
                }
            }
            "DONE" -> {
                clearActiveTurnCommand()
                mutableUiState.update {
                    it.copy(
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.Voice,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        appHeadingLockErrorDegrees = fields["ERR"]?.toFloatOrNull() ?: it.appHeadingLockErrorDegrees,
                        appHeadingLeftOutputPercent = 0,
                        appHeadingRightOutputPercent = 0,
                        statusMessage = "主控角度转向完成",
                    )
                }
            }
            "TIMEOUT" -> {
                clearActiveTurnCommand()
                mutableUiState.update {
                    it.copy(
                        leftThrottlePercent = 0,
                        rightThrottlePercent = 0,
                        commandSource = CommandSource.Voice,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        statusMessage = "主控角度转向超时，已回空挡",
                    )
                }
            }
        }
    }

    private fun handleControllerErrorLine(line: String) {
        if (!line.startsWith("ERR;")) {
            return
        }
        val errorCode = controllerErrorCode(line)
        val detailText = controllerErrorDetailText(line)
        if (activeHeadingLockCommand != null && errorCode == "YB_HEADING_UNAVAILABLE") {
            lastHandledControllerErrorLine = line
            mutableUiState.update {
                it.copy(
                    headingLockEnabled = true,
                    appHeadingLockCorrectionPercent = 0,
                    statusMessage = "主控暂时没有 IMU 航向${detailText.prependIfNotBlank("，")}；按用户规则继续维持锁航目标",
                )
            }
            sendCurrentCommand()
            return
        }

        val hadAutonomousCommand = activeTurnCommand != null || activeHeadingLockCommand != null
        if (!hadAutonomousCommand && line == lastHandledControllerErrorLine) {
            return
        }
        lastHandledControllerErrorLine = line

        if (!hadAutonomousCommand) {
            mutableUiState.update {
                it.copy(statusMessage = "主控错误：$errorCode${detailText.prependIfNotBlank("，")}")
            }
            return
        }

        clearAutonomousCommands()
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "主控拒绝自主控制：$errorCode${detailText.prependIfNotBlank("，")}，已回到手动心跳",
            )
        }
        sendCurrentCommand()
    }

    private fun handleControllerFaultLine(line: String) {
        if (!line.startsWith("STATUS;") || !line.contains("FAULT=")) {
            return
        }
        val faultCode = line.substringAfter("FAULT=", missingDelimiterValue = "")
            .substringBefore(';')
            .trim()
        if (faultCode.isBlank()) {
            return
        }
        if (faultCode == "HEADING_LOCK_DIVERGED") {
            appendRateLimitedUnrequestedHeadingLockOffLog(line)
            mutableUiState.update {
                it.copy(
                    headingLockEnabled = isHeadingLockActiveForState(it) || it.headingLockEnabled,
                    statusMessage = "主控报告锁航误差扩大；按用户规则继续维持锁航，不退出",
                )
            }
            sendCurrentCommand()
            return
        }

        val hadAutonomousCommand = activeTurnCommand != null ||
            activeHeadingLockCommand != null ||
            mutableUiState.value.headingLockEnabled
        if (!hadAutonomousCommand && line == lastHandledControllerFaultLine) {
            return
        }
        lastHandledControllerFaultLine = line

        val statusArmed = when {
            line.startsWith("STATUS;ARMED=0") || line.contains(";ARMED=0") -> false
            line.startsWith("STATUS;ARMED=1") || line.contains(";ARMED=1") -> true
            else -> mutableUiState.value.armed
        }
        val faultText = controllerFaultText(faultCode)
        if (!hadAutonomousCommand) {
            mutableUiState.update {
                it.copy(
                    armed = statusArmed,
                    statusMessage = "主控故障：$faultText",
                )
            }
            return
        }

        clearAutonomousCommands()
        mutableUiState.update {
            it.copy(
                armed = statusArmed,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                appHeadingLockTargetDegrees = null,
                appHeadingLockErrorDegrees = null,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
                appHeadingLeftCommandPercent = null,
                appHeadingRightCommandPercent = null,
                statusMessage = "主控退出自主控制：$faultText，已回空挡",
            )
        }
        sendCurrentCommand()
    }

    private fun controllerFaultText(faultCode: String): String {
        return when (faultCode) {
            "YB_HEADING_UNAVAILABLE" -> "主控 IMU 航向中断"
            "HEADING_LOCK_DIVERGED" -> "锁航误差扩大警告"
            "HEADING_UNAVAILABLE" -> "航向暂无有效读数"
            "PHONE_HEADING_TIMEOUT" -> "手机航向超时"
            "BT_TIMEOUT" -> "蓝牙控制心跳超时"
            "YB_IMU_READ_FAILED" -> "主控 IMU 读取失败"
            "IMU_MOTION_READ_FAILED" -> "运动传感器读取失败"
            "IMU_MAG_READ_FAILED" -> "磁力计读取失败"
            else -> faultCode
        }
    }

    private fun controllerErrorCode(line: String): String {
        return line.removePrefix("ERR;").substringBefore(';')
    }

    private fun controllerErrorDetailText(line: String): String {
        val fields = parseControlLogFields(line)
        return headingSourceDetailText(fields)
    }

    private fun headingSourceDetailText(fields: Map<String, String>): String {
        return listOfNotNull(
            fields["HSRC"]?.let { "HSRC=$it" },
            fields["YBIMU"]?.let { "YBIMU=$it" },
            fields["YBINIT"]?.let { "YBINIT=$it" },
            fields["YBAGE"]?.let { "YBAGE=${it}ms" },
            fields["YBHDG"]?.let { "YBHDG=$it°" },
        ).joinToString("，")
    }

    private fun String.prependIfNotBlank(prefix: String): String {
        return if (isBlank()) "" else prefix + this
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
        val neutralPivotMinDifference = coerceHeadingLockNeutralPivotMinDifferencePercent(
            preferences.getInt(
                KEY_HEADING_LOCK_NEUTRAL_PIVOT_MIN_DIFFERENCE_PERCENT,
                HEADING_LOCK_NEUTRAL_PIVOT_MIN_DIFFERENCE_DEFAULT,
            ),
            HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_DEFAULT,
        )
        val neutralPivotMaxDifference = coerceHeadingLockNeutralPivotMaxDifferencePercent(
            preferences.getInt(
                KEY_HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_PERCENT,
                HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_DEFAULT,
            ),
            neutralPivotMinDifference,
        )
        val savedHeadingLockFullCorrection = preferences.getInt(
            KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES,
            HEADING_LOCK_FULL_CORRECTION_DEFAULT,
        )
        val headingLockFullCorrection = if (savedHeadingLockFullCorrection < HEADING_LOCK_FULL_CORRECTION_DEFAULT) {
            preferences.edit()
                .putInt(KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES, HEADING_LOCK_FULL_CORRECTION_DEFAULT)
                .apply()
            HEADING_LOCK_FULL_CORRECTION_DEFAULT
        } else {
            savedHeadingLockFullCorrection
        }
        val headingLockTolerance = loadHeadingLockToleranceDegrees(headingLockFullCorrection)
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
            voicePowerLimitPercent = coerceVoicePowerLimitPercent(
                preferences.getInt(KEY_VOICE_POWER_LIMIT, VOICE_POWER_LIMIT_DEFAULT),
            ),
            fineTuneStepPercent = coerceFineTuneStepPercent(
                preferences.getInt(KEY_FINE_TUNE_STEP_PERCENT, FINE_TUNE_STEP_DEFAULT),
            ),
            gearPercents = loadGearPercents(),
            leftEscReversed = preferences.getBoolean(KEY_LEFT_ESC_REVERSED, false),
            rightEscReversed = preferences.getBoolean(KEY_RIGHT_ESC_REVERSED, false),
            rampLimitEnabled = preferences.getBoolean(KEY_RAMP_LIMIT, true),
            headingLockToleranceDegrees = headingLockTolerance,
            headingLockFullCorrectionDegrees = coerceHeadingLockFullCorrectionDegrees(
                degrees = headingLockFullCorrection,
                toleranceDegrees = headingLockTolerance,
            ),
            headingLockNeutralPivotMinDifferencePercent = neutralPivotMinDifference,
            headingLockNeutralPivotMaxDifferencePercent = neutralPivotMaxDifference,
            phoneHeadingOffsetDegrees = normalizeSignedCompassOffset(
                preferences.getFloat(KEY_PHONE_HEADING_OFFSET_DEGREES, 0f),
            ),
            navigationGpsSource = preferences.getString(KEY_NAVIGATION_GPS_SOURCE, null)
                ?.let { value -> runCatching { NavigationGpsSource.valueOf(value) }.getOrNull() }
                ?: NavigationGpsSource.Esp32,
            autoNavigationGpsJumpResetMeters = coerceAutoNavigationGpsJumpResetMeters(
                preferences.getInt(
                    KEY_AUTO_NAVIGATION_GPS_JUMP_RESET_METERS,
                    AUTO_NAVIGATION_GPS_JUMP_RESET_DEFAULT_METERS,
                ),
            ),
            usePhoneHeading = false,
            realtimeVoiceEndpoint = preferences.getString(KEY_REALTIME_VOICE_ENDPOINT, null)
                ?.ifBlank { REALTIME_VOICE_ENDPOINT_DEFAULT }
                ?: REALTIME_VOICE_ENDPOINT_DEFAULT,
            realtimeVoiceAppId = preferences.getString(KEY_REALTIME_VOICE_APP_ID, null)
                ?.ifBlank { BuildConfig.DOUBAO_APP_ID }
                ?: BuildConfig.DOUBAO_APP_ID,
            realtimeVoiceApiKey = preferences.getString(KEY_REALTIME_VOICE_API_KEY, null)
                ?.ifBlank { BuildConfig.ARK_API_KEY }
                ?: BuildConfig.ARK_API_KEY,
            realtimeVoiceModel = preferences.getString(KEY_REALTIME_VOICE_MODEL, null)
                ?.ifBlank { REALTIME_VOICE_MODEL_DEFAULT }
                ?: REALTIME_VOICE_MODEL_DEFAULT,
            realtimeVoiceVoice = preferences.getString(KEY_REALTIME_VOICE_VOICE, null)
                ?.let { normalizeRealtimeVoiceVoice(it) }
                ?.ifBlank { REALTIME_VOICE_VOICE_DEFAULT }
                ?: REALTIME_VOICE_VOICE_DEFAULT,
            realtimeTtsMode = preferences.getString(KEY_REALTIME_TTS_MODE, null)
                ?.let { runCatching { RealtimeTtsMode.valueOf(it) }.getOrNull() }
                ?: RealtimeTtsMode.Local,
            cloudTtsConfigured = preferences.getString(KEY_REALTIME_VOICE_API_KEY, null)
                ?.ifBlank { BuildConfig.ARK_API_KEY }
                ?.isNotBlank() == true || BuildConfig.DOUBAO_API_KEY.isNotBlank(),
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

    private fun loadHeadingLockToleranceDegrees(fullCorrectionDegrees: Int): Int {
        val migrated = preferences.getBoolean(KEY_HEADING_LOCK_TOLERANCE_2_DEGREES_MIGRATED, false)
        val degrees = if (migrated) {
            preferences.getInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, HEADING_LOCK_TOLERANCE_DEFAULT)
        } else {
            HEADING_LOCK_TOLERANCE_DEFAULT
        }
        val constrained = coerceHeadingLockToleranceDegrees(degrees, fullCorrectionDegrees)
        if (!migrated) {
            preferences.edit()
                .putInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, constrained)
                .putBoolean(KEY_HEADING_LOCK_TOLERANCE_2_DEGREES_MIGRATED, true)
                .apply()
        }
        return constrained
    }

    private fun normalizeRealtimeVoiceVoice(value: String): String {
        val trimmed = value.trim()
        return when (trimmed) {
            "",
            "zh_male_yunzhou_jupiter_bigtts",
            -> REALTIME_VOICE_VOICE_DEFAULT
            else -> trimmed
        }
    }

    private fun navigationGpsSourceLabel(source: NavigationGpsSource): String {
        return when (source) {
            NavigationGpsSource.Esp32 -> "ESP32 GPS"
            NavigationGpsSource.Phone -> "手机 GPS"
        }
    }

    private fun coerceAutoNavigationGpsJumpResetMeters(meters: Int): Int {
        return meters.coerceIn(
            AUTO_NAVIGATION_GPS_JUMP_RESET_MIN_METERS,
            AUTO_NAVIGATION_GPS_JUMP_RESET_MAX_METERS,
        )
    }

    override fun onCleared() {
        stopBluetoothDiscovery()
        if (discoveryReceiverRegistered) {
            runCatching {
                getApplication<Application>().unregisterReceiver(discoveryReceiver)
            }
            discoveryReceiverRegistered = false
        }
        stopPhoneHeadingSensor(clearReading = true)
        stopPhoneGps(clearReading = true)
        realtimeVoiceActionTimeoutJob?.cancel()
        voiceReplyEngine?.stop()
        voiceReplyEngine?.shutdown()
        voiceReplyEngine = null
        voiceReplyReady = false
        super.onCleared()
    }

    companion object {
        private const val TAG = "SmartSupControl"
        private const val SMART_SUP_DEVICE_PREFIX = "SmartSUP-"
        private const val ESP32_BOARD_ID = "lolin32_lite"
        private const val IMU_TELEMETRY_LOGGING_ENABLED = false
        private const val PREFERENCES_NAME = "smart_sup_settings"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_MAX_THROTTLE = "max_throttle"
        private const val KEY_VOICE_POWER_LIMIT = "voice_power_limit"
        private const val KEY_FINE_TUNE_STEP_PERCENT = "fine_tune_step_percent"
        private const val KEY_RAMP_LIMIT = "ramp_limit"
        private const val KEY_HEADING_LOCK_TOLERANCE_DEGREES = "heading_lock_tolerance_degrees"
        private const val KEY_HEADING_LOCK_TOLERANCE_2_DEGREES_MIGRATED =
            "heading_lock_tolerance_2_degrees_migrated"
        private const val KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES = "heading_lock_full_correction_degrees"
        private const val KEY_HEADING_LOCK_NEUTRAL_PIVOT_MIN_DIFFERENCE_PERCENT =
            "heading_lock_neutral_pivot_min_difference_percent"
        private const val KEY_HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_PERCENT =
            "heading_lock_neutral_pivot_max_difference_percent"
        private const val KEY_PHONE_HEADING_OFFSET_DEGREES = "phone_heading_offset_degrees"
        private const val KEY_NAVIGATION_GPS_SOURCE = "navigation_gps_source"
        private const val KEY_AUTO_NAVIGATION_GPS_JUMP_RESET_METERS = "auto_navigation_gps_jump_reset_meters"
        private const val KEY_LEFT_ESC_REVERSED = "left_esc_reversed"
        private const val KEY_RIGHT_ESC_REVERSED = "right_esc_reversed"
        private const val KEY_USE_PHONE_HEADING = "use_phone_heading"
        private const val KEY_REALTIME_VOICE_ENDPOINT = "realtime_voice_endpoint"
        private const val KEY_REALTIME_VOICE_APP_ID = "realtime_voice_app_id"
        private const val KEY_REALTIME_VOICE_API_KEY = "realtime_voice_api_key"
        private const val KEY_REALTIME_VOICE_MODEL = "realtime_voice_model"
        private const val KEY_REALTIME_VOICE_VOICE = "realtime_voice_voice"
        private const val KEY_REALTIME_TTS_MODE = "realtime_tts_mode"
        private const val KEY_REALTIME_WAKE_WORD_REQUIRED = "realtime_wake_word_required"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GEAR_PREFIX = "gear_percent_"
        private const val MAX_CONTROL_LOG_ENTRIES = 200
        private const val HEADING_LOCK_SAMPLE_LOG_INTERVAL_MS = 1_000L
        private const val HEADING_LOCK_SAMPLE_LOG_MIN_CHANGE_INTERVAL_MS = 300L
        private const val COMMAND_HEARTBEAT_MS = 100L
        private const val CONTROLLER_ARM_CONFIRM_TIMEOUT_MS = 2_000L
        private const val UNREQUESTED_STATE_LOG_INTERVAL_MS = 2_000L
        private const val OTA_RECONNECT_INTERVAL_MS = 3_000L
        private const val TRACK_LOG_READ_LIMIT = 256
        private const val TRACK_LOG_READ_DELAY_MS = 10L
        private const val PHONE_HEADING_STALE_MS = 1_500L
        private const val PHONE_GPS_STALE_MS = 5_000L
        private const val PHONE_GPS_MIN_UPDATE_MS = 500L
        private const val PHONE_GPS_MIN_UPDATE_METERS = 0f
        private const val PHONE_GPS_MAX_ACCURACY_METERS = 25f
        private const val YB_IMU_HEADING_STALE_MS = 500L
        private const val PIVOT_TURN_NEUTRAL_DELAY_MS = 1_000L
        private const val HEADING_LOCK_TOLERANCE_DEFAULT = 2
        private const val HEADING_LOCK_FULL_CORRECTION_DEFAULT = 45
        private const val HEADING_LOCK_NEUTRAL_PIVOT_MIN_DIFFERENCE_DEFAULT = 20
        private const val HEADING_LOCK_NEUTRAL_PIVOT_MAX_DIFFERENCE_DEFAULT = 60
        private const val HEADING_LOCK_NEUTRAL_PIVOT_MIN_EFFECTIVE_DIFFERENCE_PERCENT = 20
        private const val HEADING_LOCK_NEUTRAL_PIVOT_MAX_REVERSE_PERCENT = 60
        private const val AUTO_NAVIGATION_MIN_SATELLITES = 4
        private const val AUTO_NAVIGATION_ARRIVAL_RADIUS_METERS = 8.0
        private const val AUTO_NAVIGATION_GPS_FILTER_ALPHA = 0.35
        private const val AUTO_NAVIGATION_GPS_JUMP_RESET_MIN_METERS = 3
        private const val AUTO_NAVIGATION_GPS_JUMP_RESET_MAX_METERS = 30
        private const val AUTO_NAVIGATION_GPS_JUMP_RESET_DEFAULT_METERS = 5
        private const val AUTO_NAVIGATION_MAX_OUTPUT_PERCENT = 70
        private const val TRACK_LINE_LOOKAHEAD_METERS = 12.0
        private const val TRACK_LINE_MAX_CORRECTION_DEGREES = 25f
        private const val TRACK_LINE_MAX_CROSS_TRACK_METERS = 15.0
        private const val STATION_KEEPING_HOLD_RADIUS_METERS = 2.0
        private const val STATION_KEEPING_REENGAGE_RADIUS_METERS = 2.8
        private const val STATION_KEEPING_FULL_OUTPUT_RADIUS_METERS = 6.0
        private const val STATION_KEEPING_GO_HEADING_ERROR_DEGREES = 20f
        private const val STATION_KEEPING_MAX_OUTPUT_PERCENT = 30
        private const val STATION_KEEPING_MIN_EFFECTIVE_OUTPUT_PERCENT = 12
        private const val STATION_KEEPING_FULL_CORRECTION_DEGREES = 30
        private const val STATION_KEEPING_MAX_PIVOT_DIFFERENCE_PERCENT = 50
        private const val STATION_KEEPING_DEFAULT_GEAR_INDEX = 1
        private const val STATION_KEEPING_REVERSE_SELECTION_DEADBAND_DEGREES = 15f
        private const val STATION_KEEPING_MAX_FINAL_HEADING_ERROR_DEGREES = 45f
        private const val STATION_KEEPING_FUSION_MOTION_G = 0.06
        private const val STATION_KEEPING_FUSION_OUTPUT_ACTIVE_PERCENT = 8
        private const val STATION_KEEPING_FUSION_JUMP_LOW_MOTION_ALPHA = 0.08
        private const val STATION_KEEPING_FUSION_JUMP_LOW_MOTION_WEIGHT = 0.2
        private const val STATION_KEEPING_FUSION_JUMP_ACCEPT_ALPHA = 0.55
        private const val STATION_KEEPING_FUSION_JUMP_ACCEPT_WEIGHT = 0.65
        private const val STATION_KEEPING_FUSION_JUMP_NO_IMU_ALPHA = 0.45
        private const val STATION_KEEPING_FUSION_JUMP_NO_IMU_WEIGHT = 0.55
        private const val STATION_KEEPING_FUSION_MOVING_ALPHA = 0.4
        private const val STATION_KEEPING_FUSION_MOVING_WEIGHT = 0.85
        private const val STATION_KEEPING_FUSION_STILL_ALPHA = 0.25
        private const val STATION_KEEPING_FUSION_STILL_WEIGHT = 0.75
        private const val MAX_TURN_REQUEST_ID = 65_535
        private const val VOICE_POWER_LIMIT_MIN = 5
        private const val VOICE_POWER_LIMIT_MAX = 100
        private const val VOICE_POWER_LIMIT_DEFAULT = 70
        private const val FINE_TUNE_STEP_MIN = 1
        private const val FINE_TUNE_STEP_MAX = 10
        private const val FINE_TUNE_STEP_DEFAULT = 3
        private const val VOICE_REPLY_POST_SUPPRESSION_MS = 1_000L
        private const val VOICE_REPLY_MAX_SUPPRESSION_MS = 15_000L
        private const val VOICE_COMMAND_DUPLICATE_SUPPRESSION_MS = 2_500L
        private const val REALTIME_VOICE_MODEL_DEFAULT = "doubao-seed-2-0-lite-260428"
        private const val REALTIME_VOICE_ENDPOINT_DEFAULT = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
        private const val REALTIME_VOICE_VOICE_DEFAULT = "zh_female_vv_uranus_bigtts"
        private val AUTO_NAVIGATION_GEAR_PERCENTS = listOf(15, 22, 30, 40, 50, 60, 70)
        private val VOICE_ACK_REPLIES = listOf(
            "好的",
            "可以",
            "明白了",
            "收到",
            "yes sir",
            "好的，帅哥",
            "安排",
            "没问题",
        )
    }

    private fun coerceVoicePowerLimitPercent(percent: Int): Int {
        return percent.coerceIn(VOICE_POWER_LIMIT_MIN, VOICE_POWER_LIMIT_MAX)
    }

    private fun coerceFineTuneStepPercent(percent: Int): Int {
        return percent.coerceIn(FINE_TUNE_STEP_MIN, FINE_TUNE_STEP_MAX)
    }

    private fun coerceHeadingLockToleranceDegrees(degrees: Int, fullCorrectionDegrees: Int): Int {
        return degrees.coerceIn(1, (fullCorrectionDegrees - 1).coerceAtMost(20).coerceAtLeast(1))
    }

    private fun coerceHeadingLockFullCorrectionDegrees(degrees: Int, toleranceDegrees: Int): Int {
        return degrees.coerceIn((toleranceDegrees + 1).coerceAtLeast(5), 180)
    }

    private fun coerceHeadingLockNeutralPivotMinDifferencePercent(percent: Int, maxDifferencePercent: Int): Int {
        val minAllowed = HEADING_LOCK_NEUTRAL_PIVOT_MIN_EFFECTIVE_DIFFERENCE_PERCENT
        val maxAllowed = maxDifferencePercent.coerceIn(minAllowed, HEADING_LOCK_NEUTRAL_PIVOT_MAX_REVERSE_PERCENT)
        return percent.coerceIn(minAllowed, maxAllowed)
    }

    private fun coerceHeadingLockNeutralPivotMaxDifferencePercent(percent: Int, minDifferencePercent: Int): Int {
        return percent.coerceIn(
            minDifferencePercent.coerceAtLeast(HEADING_LOCK_NEUTRAL_PIVOT_MIN_EFFECTIVE_DIFFERENCE_PERCENT),
            HEADING_LOCK_NEUTRAL_PIVOT_MAX_REVERSE_PERCENT,
        )
    }

    private fun lockForDirectionChange(message: String, onNeutralSent: (() -> Unit)? = null) {
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "$message，已回空挡，主控保持当前解锁状态",
            )
        }
        sendCurrentCommand(onNeutralSent)
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

    private fun parseFirmwareManifest(jsonText: String): FirmwareReleaseManifest {
        val json = JSONObject(jsonText)
        val firmware = json.getJSONObject("firmware")
        return FirmwareReleaseManifest(
            releaseVersion = json.optString("version").ifBlank {
                firmware.optString("version")
            },
            firmwareAssetName = firmware.getString("asset"),
            firmwareVersion = firmware.getString("version"),
            board = firmware.getString("board"),
            sizeBytes = firmware.optLong("size", 0L),
            sha256Hex = firmware.getString("sha256"),
            minAppVersion = firmware.optString("minAppVersion").ifBlank { null },
        )
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sameVersion(left: String, right: String): Boolean {
        return left.trim().removePrefix("v").removePrefix("V") ==
            right.trim().removePrefix("v").removePrefix("V")
    }

    private fun isVersionGreaterThan(candidate: String, current: String): Boolean {
        val candidateParts = parseVersion(candidate)
        val currentParts = parseVersion(current)
        if (candidateParts.isEmpty() || currentParts.isEmpty()) {
            return candidate != current
        }
        val maxSize = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) {
                return candidatePart > currentPart
            }
        }
        return false
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

    private fun isEsp32FirmwareUpdateAvailable(targetVersion: String?, currentVersion: String?): Boolean {
        if (targetVersion.isNullOrBlank() || currentVersion.isNullOrBlank()) {
            return false
        }
        return !sameVersion(currentVersion, targetVersion) &&
            isVersionGreaterThan(targetVersion, currentVersion)
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
