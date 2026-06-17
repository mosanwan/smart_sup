package com.smartsup.controller.control

import android.app.Application
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.AutoNavigationUiState
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.CommandSource
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.ControlCommandMode
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.NavigationRoute
import com.smartsup.controller.model.NavigationRoutePoint
import com.smartsup.controller.model.ReleaseInfo
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.TrackLogEvent
import com.smartsup.controller.model.UpdateUiState
import com.smartsup.controller.model.VoiceAsrState
import com.smartsup.controller.transport.BluetoothClassicTransport
import com.smartsup.controller.transport.ControlTransport
import com.smartsup.controller.update.AppUpdateInstaller
import com.smartsup.controller.update.GitHubReleaseClient
import com.smartsup.controller.voice.VoiceCommandEvaluation
import com.smartsup.controller.voice.VoiceCommandAction
import com.smartsup.controller.voice.VoiceCommandParser
import com.smartsup.controller.voice.VoiceParseResult
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

class ControlViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var transport: ControlTransport? = null
    private var telemetryJob: Job? = null
    private var trackLogJob: Job? = null
    private var trackLogSyncJob: Job? = null
    private var commandHeartbeatJob: Job? = null
    private var autoReconnectAttempted = false
    private var discoveryReceiverRegistered = false
    private var lastHandledControllerErrorLine: String? = null
    private val releaseClient = GitHubReleaseClient(BuildConfig.GITHUB_REPOSITORY) {
        preferences.getString(KEY_GITHUB_TOKEN, null)
    }
    private val gpsTrackStore = GpsTrackStore(application)
    private val autoNavigationRouteStore = AutoNavigationRouteStore(application)
    private val appUpdateInstaller = AppUpdateInstaller(application)
    private val sensorManager = application.getSystemService(SensorManager::class.java)
    private val phoneHeadingRotation = FloatArray(9)
    private val phoneHeadingOrientation = FloatArray(3)
    private var phoneHeadingSensorRegistered = false
    private var phoneHeadingLastUpdateMs = 0L
    private var latestRelease: ReleaseInfo? = null
    private var downloadedApk: File? = null
    private var activeTurnCommand: ControlCommand? = null
    private var activeHeadingLockCommand: ControlCommand? = null
    private var appHeadingControlTargetDegrees: Float? = null
    private var appHeadingControlStartedAtMs = 0L
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
    private val voiceSampleTargets = listOf(
        VoiceSampleTarget("开始声控", "开始声控", "本地状态：恢复执行语音控制命令"),
        VoiceSampleTarget("停止声控", "停止声控", "SRC=VOICE;ARM=0;L=0;R=0"),
        VoiceSampleTarget("停止", "停止", "SRC=VOICE;ARM=0;L=0;R=0"),
        VoiceSampleTarget("快点", "快点", "当前目标按微调步进加速"),
        VoiceSampleTarget("慢点", "慢点", "当前目标按微调步进减速"),
        VoiceSampleTarget("空档", "空档", "SRC=VOICE;ARM=1;L=0;R=0"),
        VoiceSampleTarget("前进一档", "前进一档", "SRC=VOICE;ARM=1;L=20;R=20"),
        VoiceSampleTarget("前进二档", "前进二档", "SRC=VOICE;ARM=1;L=30;R=30"),
        VoiceSampleTarget("前进三档", "前进三档", "SRC=VOICE;ARM=1;L=60;R=60"),
        VoiceSampleTarget("前进四档", "前进四档", "SRC=VOICE;ARM=1;L=80;R=80"),
        VoiceSampleTarget("后退一档", "后退一档", "SRC=VOICE;ARM=1;L=-15;R=-15"),
        VoiceSampleTarget("左转", "左转", "SRC=VOICE;ARM=1;L=10;R=25"),
        VoiceSampleTarget("右转", "右转", "SRC=VOICE;ARM=1;L=25;R=10"),
        VoiceSampleTarget("左转 15 度", "左转十五度", "App 本地左转 15 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("左转 30 度", "左转三十度", "App 本地左转 30 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("左转 60 度", "左转六十度", "App 本地左转 60 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("右转 15 度", "右转十五度", "App 本地右转 15 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("右转 30 度", "右转三十度", "App 本地右转 30 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("右转 60 度", "右转六十度", "App 本地右转 60 度，发送普通 L/R 心跳"),
        VoiceSampleTarget("保持航向", "保持航向", "App 本地航向锁定，发送普通 L/R 心跳"),
        VoiceSampleTarget("锁定当前航向", "锁定当前航向", "App 本地航向锁定，发送普通 L/R 心跳"),
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
            )
        }
        initializeVoiceReplyEngine()
        updatePhoneHeadingSensorRegistration()
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
                    commandSource = CommandSource.App,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "正在连接 ${deviceInfo.name}",
                )
            }
            mutableSettingsState.update { it.copy(message = "正在连接 ${deviceInfo.name}") }

            val nextTransport = BluetoothClassicTransport(getApplication(), deviceInfo)
            runCatching {
                nextTransport.connect()
            }.onSuccess {
                transport = nextTransport
                clearAutonomousCommands()
                collectTelemetry(nextTransport)
                collectTrackLogEvents(nextTransport)
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
                requestTrackLogSync()
                speakVoiceReply("主控已连接")
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
            trackLogJob?.cancel()
            trackLogSyncJob?.cancel()
            commandHeartbeatJob?.cancel()
            clearAutonomousCommands()
            mutableUiState.value = ControlUiState(
                statusMessage = "已断开，推进输出保持空闲",
            )
            mutableSettingsState.update { it.copy(message = "已断开 ESP32 连接") }
        }
    }

    fun setArmed(armed: Boolean) {
        val canArm = armed && mutableUiState.value.connectionState == ConnectionState.Connected
        val replyText = when {
            canArm -> replyWithDetail("已解锁")
            armed -> replyWithDetail("未连接，不能解锁")
            else -> replyWithDetail("已锁定")
        }
        clearAutonomousCommands()
        mutableUiState.update {
            if (canArm) {
                it.copy(
                    armed = true,
                    commandSource = CommandSource.App,
                    headingLockEnabled = false,
                    statusMessage = "已解锁，请保持低功率测试",
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
                    statusMessage = "已锁定，油门回空挡",
                )
            }
        }
        sendCurrentCommand {
            speakVoiceReply(replyText)
        }
    }

    fun setLeftThrottle(percent: Int) {
        val constrained = coerceSignedThrottle(percent)
        val lockKept = updateActiveHeadingLockSource(CommandSource.App)
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
        val constrained = coerceSignedThrottle(percent)
        val lockKept = updateActiveHeadingLockSource(CommandSource.App)
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
        val gearThrottle = coerceSignedThrottle(
            selectedGearThrottle(mutableUiState.value.selectedGear, mutableUiState.value.throttleTrimPercent),
        )
        val lockKept = updateActiveHeadingLockSource(CommandSource.App)
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
        val gearThrottle = coerceSignedThrottle(
            selectedGearThrottle(mutableUiState.value.selectedGear, mutableUiState.value.throttleTrimPercent),
        )
        val lockKept = updateActiveHeadingLockSource(CommandSource.App)
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
        val gearThrottle = coerceSignedThrottle(gearPercent(gear))
        val updatedHeadingLockBase = updateActiveHeadingLockSource(CommandSource.App)
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
            val updatedHeadingLockBase = updateActiveHeadingLockSource(CommandSource.App)
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
        preferences.edit().putInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, constrained).apply()
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

    fun setHeadingLockNeutralReversePercent(percent: Int) {
        val constrained = coerceHeadingLockNeutralReversePercent(percent)
        preferences.edit().putInt(KEY_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT, constrained).apply()
        clearAutonomousCommands()
        mutableSettingsState.update { it.copy(headingLockNeutralReversePercent = constrained) }
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                statusMessage = "空档锁航最大反推已设置为 ${constrained}%，已取消当前航向锁定",
            )
        }
        sendCurrentCommand()
    }

    fun setUsePhoneHeading(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USE_PHONE_HEADING, true).apply()
        mutableSettingsState.update { it.copy(usePhoneHeading = true) }
        updatePhoneHeadingSensorRegistration()
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                statusMessage = "手机指南针是唯一航向来源，请把手机固定在板体上",
            )
        }
        sendCurrentCommand()
    }

    fun enableHeadingLock() {
        val state = mutableUiState.value
        val settings = mutableSettingsState.value
        if (state.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：未连接 ESP32") }
            return
        }
        if (!state.armed) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：请先手动解锁") }
            return
        }
        val currentHeading = currentPhoneHeadingDegreesOrNull()
        if (currentHeading == null) {
            mutableUiState.update { it.copy(statusMessage = "航向锁定拒绝：手机指南针暂无有效读数") }
            return
        }

        clearActiveTurnCommand()
        val command = prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.App,
                mode = ControlCommandMode.HeadingLock,
                headingLockEnabled = true,
                headingLockRequestId = 1,
            ),
            allocateHeadingLockRequestId = true,
        )
        startAppHeadingLock(command, currentHeading)
        mutableUiState.update {
            it.copy(
                commandSource = CommandSource.App,
                headingLockEnabled = true,
                statusMessage = "航向锁定已开启，目标 ${currentHeading.roundToInt()}°",
            )
        }
        sendCurrentCommand()
    }

    fun cancelHeadingLock() {
        clearActiveHeadingLockCommand()
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
            mutableUiState.update { it.copy(statusMessage = "目标航向拒绝：未连接 ESP32") }
            return
        }
        if (!state.armed) {
            mutableUiState.update { it.copy(statusMessage = "目标航向拒绝：请先手动解锁") }
            return
        }
        if (currentPhoneHeadingDegreesOrNull() == null) {
            mutableUiState.update { it.copy(statusMessage = "目标航向拒绝：手机指南针暂无有效读数") }
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
            ),
            allocateHeadingLockRequestId = true,
        )
        startAppHeadingLock(command, normalizedTarget)
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
            refreshGpsTrackState("未连接 ESP32，无法同步轨迹")
            return
        }
        viewModelScope.launch {
            runCatching {
                mutableUiState.update {
                    it.copy(
                        gpsTrack = it.gpsTrack.copy(
                            syncing = true,
                            syncStartSequence = null,
                            syncTargetSequence = null,
                            syncCurrentSequence = null,
                            syncMessage = "正在查询 ESP32 轨迹缓存",
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
                    targetPointIndex = 0,
                    gearIndex = 0,
                    distanceToTargetMeters = null,
                    headingErrorDegrees = null,
                    leftOutputPercent = 0,
                    rightOutputPercent = 0,
                    message = "自动导航已启动：${route!!.name}",
                ),
                statusMessage = "自动导航已启动，请保持人工接管准备",
            )
        }
        sendCurrentCommand()
    }

    fun increaseAutoNavigationGear() {
        mutableUiState.update {
            val nextGear = (it.autoNavigation.gearIndex + 1).coerceAtMost(AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = nextGear,
                    message = "自动导航档位 ${nextGear + 1}",
                ),
            )
        }
    }

    fun decreaseAutoNavigationGear() {
        mutableUiState.update {
            val nextGear = (it.autoNavigation.gearIndex - 1).coerceAtLeast(0)
            it.copy(
                autoNavigation = it.autoNavigation.copy(
                    gearIndex = nextGear,
                    message = "自动导航档位 ${nextGear + 1}",
                ),
            )
        }
    }

    fun stopAutoNavigation() {
        clearAutoNavigationExecution("自动导航已停止")
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
                statusMessage = "自动导航已停止，已锁定并回空挡",
            )
        }
        sendIdle()
    }

    fun toggleVoiceControl() {
        setVoiceControlEnabled(!mutableUiState.value.voiceControlEnabled)
    }

    fun setVoiceControlEnabled(enabled: Boolean) {
        val shouldClearVoiceOutput = !enabled && mutableUiState.value.commandSource == CommandSource.Voice
        if (shouldClearVoiceOutput) {
            clearAutonomousCommands()
        }
        mutableUiState.update {
            it.copy(
                voiceControlEnabled = enabled,
                voiceSamplingEnabled = if (enabled) it.voiceSamplingEnabled else false,
                voiceAsrState = if (enabled) VoiceAsrState.Starting else VoiceAsrState.Stopped,
                voiceAsrStatus = if (enabled) "Qwen ASR：初始化模型" else "Qwen ASR：已暂停",
                armed = if (shouldClearVoiceOutput) false else it.armed,
                leftThrottlePercent = if (shouldClearVoiceOutput) 0 else it.leftThrottlePercent,
                rightThrottlePercent = if (shouldClearVoiceOutput) 0 else it.rightThrottlePercent,
                commandSource = if (shouldClearVoiceOutput) CommandSource.App else it.commandSource,
                headingLockEnabled = if (shouldClearVoiceOutput) false else it.headingLockEnabled,
                selectedGear = if (shouldClearVoiceOutput) ThrottleGear.Neutral else it.selectedGear,
                throttleTrimPercent = if (shouldClearVoiceOutput) 0 else it.throttleTrimPercent,
                voiceResultMessage = if (enabled) "声控开启中" else "声控已关闭",
                voiceCommandPreview = if (enabled) it.voiceCommandPreview else "声控关闭：不发送",
                statusMessage = if (enabled) "声控开启中，等待 ASR 模型就绪" else "声控已关闭",
            )
        }
        if (shouldClearVoiceOutput) {
            sendCurrentCommand()
        }
    }

    fun setVoiceAsrStatus(message: String) {
        mutableUiState.update {
            it.copy(
                voiceAsrStatus = message,
                voiceAsrState = voiceAsrStateFor(message, it.voiceControlEnabled || it.voiceSamplingEnabled),
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
        preferences.edit().putBoolean(KEY_LEFT_ESC_REVERSED, enabled).apply()
        mutableSettingsState.update { it.copy(leftEscReversed = enabled) }
        clearAutonomousCommands()
        lockForDirectionChange("左 ESC 方向${if (enabled) "已反转" else "已恢复"}")
    }

    fun setRightEscReversed(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RIGHT_ESC_REVERSED, enabled).apply()
        mutableSettingsState.update { it.copy(rightEscReversed = enabled) }
        clearAutonomousCommands()
        lockForDirectionChange("右 ESC 方向${if (enabled) "已反转" else "已恢复"}")
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

    private data class VoicePreview(
        val message: String,
        val candidateLine: String,
        val commandLine: String,
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
        val state = mutableUiState.value
        return when {
            shouldAdjustHeadingLockTarget(command, state) -> {
                prepareHeadingLockOffsetCommand(command, allocateHeadingLockRequestId = false)
            }
            shouldApplyThrottleToHeadingLock(command, state) -> {
                prepareHeadingLockBaseCommandFromThrottle(command)
            }
            else -> prepareRuntimeCommand(command)
        }
    }

    private fun shouldAdjustHeadingLockTarget(
        command: ControlCommand,
        state: ControlUiState,
    ): Boolean {
        return command.mode == ControlCommandMode.TurnAngle &&
            command.armed &&
            isHeadingLockActiveForState(state)
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

    private fun prepareHeadingLockOffsetCommand(
        turnCommand: ControlCommand,
        allocateHeadingLockRequestId: Boolean,
    ): ControlCommand {
        val offsetDegrees = headingLockOffsetDegrees(turnCommand)
        return prepareRuntimeCommand(
            command = ControlCommand(
                armed = true,
                source = CommandSource.Voice,
                mode = ControlCommandMode.HeadingLock,
                headingLockEnabled = true,
                headingLockRequestId = 1,
                headingLockBaseThrottlePercent = currentAverageThrottlePercent(),
                headingLockTargetOffsetDegrees = offsetDegrees,
            ),
            allocateHeadingLockRequestId = allocateHeadingLockRequestId,
        )
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
            headingLockNeutralReversePercent = settings.headingLockNeutralReversePercent,
            headingLockTargetOffsetDegrees = null,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
            leftEscReversed = settings.leftEscReversed,
            rightEscReversed = settings.rightEscReversed,
        )
    }

    private fun headingLockOffsetDegrees(turnCommand: ControlCommand): Int {
        val angle = turnCommand.turnAngleDegrees ?: 0
        return when (turnCommand.turnDirection) {
            com.smartsup.controller.model.TurnDirection.Left -> -angle
            com.smartsup.controller.model.TurnDirection.Right -> angle
            null -> 0
        }.coerceIn(-90, 90)
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
                clearAutonomousCommands()
                mutableUiState.update {
                    it.copy(
                        voiceControlEnabled = false,
                    )
                }
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
        val turnWithinHeadingLock = shouldAdjustHeadingLockTarget(command, state)
        val throttleWithinHeadingLock = shouldApplyThrottleToHeadingLock(command, state)
        val runtimeCommand = if (turnWithinHeadingLock) {
            prepareHeadingLockOffsetCommand(command, allocateHeadingLockRequestId = true)
        } else if (throttleWithinHeadingLock) {
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
                    voiceResultMessage = "请先连接 ESP32",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：未连接 ESP32",
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

        if (turnWithinHeadingLock) {
            val currentHeading = currentPhoneHeadingDegreesOrNull()
            if (currentHeading == null) {
                mutableUiState.update {
                    it.copy(
                        voiceResultMessage = "手机指南针暂无有效读数",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = commandLine,
                        statusMessage = "语音转向拒绝：手机指南针暂无有效读数",
                    )
                }
                return
            }
            val targetHeading = normalizeCompassDegrees(currentHeading + headingLockOffsetDegrees(command))
            clearActiveTurnCommand()
            startAppHeadingLock(runtimeCommand.asHeadingLockHeartbeat(), targetHeading)
            mutableUiState.update {
                it.copy(
                    commandSource = CommandSource.Voice,
                    headingLockEnabled = true,
                    selectedGear = voiceGear ?: ThrottleGear.Neutral,
                    throttleTrimPercent = voiceGear?.let { gear ->
                        fineTuneTrimFor(gear, command.leftThrottlePercent, command.rightThrottlePercent)
                    } ?: 0,
                    voiceResultMessage = "已执行：${result.label}",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音控制：锁航目标 ${targetHeading.roundToInt()}°",
                )
            }
            sendCurrentCommand {
                speakVoiceCommandReply(result, command)
            }
            return
        }

        if (throttleWithinHeadingLock) {
            clearActiveTurnCommand()
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
            val currentHeading = currentPhoneHeadingDegreesOrNull()
            if (currentHeading == null) {
                mutableUiState.update {
                    it.copy(
                        voiceResultMessage = "手机指南针暂无有效读数",
                        voiceCandidatePreview = candidateLine,
                        voiceCommandPreview = commandLine,
                        statusMessage = "语音转向拒绝：手机指南针暂无有效读数",
                    )
                }
                return
            }
            val targetHeading = normalizeCompassDegrees(currentHeading + headingLockOffsetDegrees(command))
            clearActiveHeadingLockCommand()
            startAppTurn(runtimeCommand, targetHeading)
            mutableUiState.update {
                it.copy(
                        commandSource = CommandSource.Voice,
                        headingLockEnabled = false,
                        selectedGear = ThrottleGear.Neutral,
                        throttleTrimPercent = 0,
                        voiceResultMessage = "已执行：${result.label}",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音控制：${result.label}，目标 ${targetHeading.roundToInt()}°",
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
                val currentHeading = currentPhoneHeadingDegreesOrNull()
                if (currentHeading == null) {
                    mutableUiState.update {
                        it.copy(
                            voiceResultMessage = "手机指南针暂无有效读数",
                            voiceCandidatePreview = candidateLine,
                            voiceCommandPreview = commandLine,
                            statusMessage = "语音锁航拒绝：手机指南针暂无有效读数",
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
                    voiceResultMessage = "请先连接 ESP32",
                    voiceCandidatePreview = candidateLine,
                    voiceCommandPreview = commandLine,
                    statusMessage = "语音命令拒绝：未连接 ESP32",
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
        val updatedHeadingLockBase = isHeadingLockActiveForState(state) &&
            updateActiveHeadingLockSource(source)
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
                statusMessage = "微调${actionText} ${step}%：左 ${target.leftPercent.signedPercentText()}，右 ${target.rightPercent.signedPercentText()}",
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

    private fun sendMagCalibrationAction(
        action: String,
        requestedMessage: String,
        failurePrefix: String,
        forceLockedNeutral: Boolean,
    ) {
        val nextTransport = transport
        if (nextTransport == null || mutableUiState.value.connectionState != ConnectionState.Connected) {
            mutableUiState.update { it.copy(statusMessage = "$failurePrefix：未连接 ESP32") }
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

    private fun ControlCommand.withRuntimeHeadingSource(): ControlCommand {
        return copy(usePhoneHeading = false, phoneHeadingDegrees = null)
    }

    private fun currentPhoneHeadingDegreesOrNull(): Float? {
        val state = mutableUiState.value
        val ageMs = System.currentTimeMillis() - phoneHeadingLastUpdateMs
        return state.phoneHeadingDegrees
            ?.takeIf { state.phoneHeadingAvailable && ageMs <= PHONE_HEADING_STALE_MS }
    }

    private fun updatePhoneHeadingSensorRegistration() {
        startPhoneHeadingSensor()
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
                        message = "ESP32 固件已发送完成，设备会校验并重启；如果蓝牙断开，请重新连接",
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
        if (state.voiceSamplingEnabled) {
            return ControlCommand.Idle
        }
        val settings = mutableSettingsState.value
        val source = state.commandSource
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
                leftThrottlePercent = applyEscDirection(
                    percent = leftPercent,
                    reversed = settings.leftEscReversed,
                ),
                rightThrottlePercent = applyEscDirection(
                    percent = rightPercent,
                    reversed = settings.rightEscReversed,
                ),
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

    private fun buildAutoNavigationCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        val autoState = state.autoNavigation
        val route = autoState.routes.firstOrNull { it.id == autoState.executingRouteId }
        if (route == null || route.points.size < 2) {
            failAutoNavigation("自动导航停止：路线无效")
            return ControlCommand.Idle
        }
        val currentPoint = currentGpsPointOrNull()
        if (currentPoint == null || gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES) {
            failAutoNavigation("自动导航停止：GPS 定位不足")
            return ControlCommand.Idle
        }
        val currentHeading = currentPhoneHeadingDegreesOrNull()
        if (currentHeading == null) {
            failAutoNavigation("自动导航停止：手机指南针超时")
            return ControlCommand.Idle
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
                    armed = false,
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = CommandSource.App,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    statusMessage = "自动导航完成，已锁定并回空挡",
                )
            }
            return ControlCommand.Idle
        }

        val targetBearing = bearingBetween(currentPoint, targetPoint)
        val errorDegrees = shortestCompassError(targetBearing.toFloat(), currentHeading)
        val absError = abs(errorDegrees)
        val gearIndex = autoState.gearIndex.coerceIn(0, AUTO_NAVIGATION_GEAR_PERCENTS.lastIndex)
        val basePercent = if (absError > AUTO_NAVIGATION_STOP_TURN_DEGREES) {
            0
        } else {
            AUTO_NAVIGATION_GEAR_PERCENTS[gearIndex].coerceAtMost(settings.maxThrottlePercent)
        }
        val correction = if (basePercent == 0) {
            0
        } else {
            (errorDegrees / AUTO_NAVIGATION_FULL_CORRECTION_DEGREES)
                .coerceIn(-1f, 1f)
                .let { it * AUTO_NAVIGATION_MAX_CORRECTION_PERCENT }
                .roundToInt()
        }
        val leftPercent = (basePercent + correction).coerceIn(0, settings.maxThrottlePercent)
        val rightPercent = (basePercent - correction).coerceIn(0, settings.maxThrottlePercent)

        mutableUiState.update {
            it.copy(
                leftThrottlePercent = leftPercent,
                rightThrottlePercent = rightPercent,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                autoNavigation = it.autoNavigation.copy(
                    targetPointIndex = targetIndex,
                    gearIndex = gearIndex,
                    distanceToTargetMeters = distanceMeters,
                    headingErrorDegrees = errorDegrees,
                    leftOutputPercent = leftPercent,
                    rightOutputPercent = rightPercent,
                    message = if (basePercent == 0) {
                        "目标偏差过大，自动导航暂停推进"
                    } else {
                        "自动导航执行中"
                    },
                ),
            )
        }

        return ControlCommand(
            leftThrottlePercent = applyEscDirection(leftPercent, settings.leftEscReversed),
            rightThrottlePercent = applyEscDirection(rightPercent, settings.rightEscReversed),
            armed = true,
            source = CommandSource.App,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun buildAppHeadingControlCommand(
        state: ControlUiState,
        settings: SettingsUiState,
    ): ControlCommand {
        val activeCommand = activeHeadingLockCommand ?: activeTurnCommand
        if (activeCommand == null) {
            return ControlCommand.Idle
        }
        val currentHeading = currentPhoneHeadingDegreesOrNull()
        if (currentHeading == null) {
            failAppHeadingControl("手机指南针超时，已锁定并回空挡")
            return ControlCommand.Idle
        }
        val targetHeading = appHeadingControlTargetDegrees ?: currentHeading.also {
            appHeadingControlTargetDegrees = it
        }
        val errorDegrees = shortestCompassError(targetHeading, currentHeading)
        val absError = abs(errorDegrees)
        val isOneShotTurn = activeTurnCommand != null && activeHeadingLockCommand == null
        val now = System.currentTimeMillis()
        if (isOneShotTurn && absError <= APP_TURN_DONE_DEGREES) {
            clearActiveTurnCommand()
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = activeCommand.source,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    appHeadingLockErrorDegrees = errorDegrees,
                    appHeadingLockCorrectionPercent = 0,
                    appHeadingLeftOutputPercent = 0,
                    appHeadingRightOutputPercent = 0,
                    statusMessage = "角度转向完成，误差 ${errorDegrees.roundToInt()}°",
                )
            }
            return ControlCommand(
                armed = true,
                source = activeCommand.source,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        }
        if (isOneShotTurn && now - appHeadingControlStartedAtMs > APP_TURN_TIMEOUT_MS) {
            clearActiveTurnCommand()
            mutableUiState.update {
                it.copy(
                    leftThrottlePercent = 0,
                    rightThrottlePercent = 0,
                    commandSource = activeCommand.source,
                    selectedGear = ThrottleGear.Neutral,
                    throttleTrimPercent = 0,
                    appHeadingLockErrorDegrees = errorDegrees,
                    appHeadingLockCorrectionPercent = 0,
                    appHeadingLeftOutputPercent = 0,
                    appHeadingRightOutputPercent = 0,
                    statusMessage = "角度转向超时，已回空挡",
                )
            }
            return ControlCommand(
                armed = true,
                source = activeCommand.source,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
            )
        }

        val baseLeftPercent = if (isOneShotTurn) 0 else state.leftThrottlePercent
        val baseRightPercent = if (isOneShotTurn) 0 else state.rightThrottlePercent
        val correction = headingCorrectionPercent(
            errorDegrees = errorDegrees,
            toleranceDegrees = activeCommand.headingLockToleranceDegrees,
            fullCorrectionDegrees = activeCommand.headingLockFullCorrectionDegrees,
        )
        val rawLeftRight = headingCorrectedThrottle(
            leftBasePercent = baseLeftPercent,
            rightBasePercent = baseRightPercent,
            correction = correction,
            neutralReversePercent = activeCommand.headingLockNeutralReversePercent,
        )
        val leftPercent = coerceCommandPercentForSource(rawLeftRight.first, activeCommand.source)
        val rightPercent = coerceCommandPercentForSource(rawLeftRight.second, activeCommand.source)
        mutableUiState.update {
            it.copy(
                leftThrottlePercent = if (isOneShotTurn) it.leftThrottlePercent else baseLeftPercent,
                rightThrottlePercent = if (isOneShotTurn) it.rightThrottlePercent else baseRightPercent,
                commandSource = activeCommand.source,
                headingLockEnabled = !isOneShotTurn,
                appHeadingLockTargetDegrees = targetHeading,
                appHeadingLockErrorDegrees = errorDegrees,
                appHeadingLockCorrectionPercent = correction,
                appHeadingLeftOutputPercent = leftPercent,
                appHeadingRightOutputPercent = rightPercent,
            )
        }
        return ControlCommand(
            leftThrottlePercent = applyEscDirection(leftPercent, settings.leftEscReversed),
            rightThrottlePercent = applyEscDirection(rightPercent, settings.rightEscReversed),
            armed = true,
            source = activeCommand.source,
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun headingCorrectionPercent(
        errorDegrees: Float,
        toleranceDegrees: Int,
        fullCorrectionDegrees: Int,
    ): Int {
        val absError = abs(errorDegrees)
        val tolerance = toleranceDegrees.toFloat()
        if (absError <= tolerance) {
            return 0
        }
        val full = fullCorrectionDegrees.coerceAtLeast(toleranceDegrees + 1).toFloat()
        val activeError = (absError.coerceAtMost(full) - tolerance).coerceAtLeast(0f)
        val activeRange = (full - tolerance).coerceAtLeast(1f)
        val ratio = activeError / activeRange
        val correction = HEADING_LOCK_MIN_CORRECTION_PERCENT +
            ((HEADING_LOCK_MAX_CORRECTION_PERCENT - HEADING_LOCK_MIN_CORRECTION_PERCENT) * ratio).roundToInt()
        return correction.coerceIn(HEADING_LOCK_MIN_CORRECTION_PERCENT, HEADING_LOCK_MAX_CORRECTION_PERCENT)
            .let { if (errorDegrees < 0f) -it else it }
    }

    private fun headingCorrectedThrottle(
        leftBasePercent: Int,
        rightBasePercent: Int,
        correction: Int,
        neutralReversePercent: Int,
    ): Pair<Int, Int> {
        return headingCorrectedSideThrottle(
            basePercent = leftBasePercent,
            correctionDelta = correction,
            neutralReversePercent = neutralReversePercent,
        ) to headingCorrectedSideThrottle(
            basePercent = rightBasePercent,
            correctionDelta = -correction,
            neutralReversePercent = neutralReversePercent,
        )
    }

    private fun headingCorrectedSideThrottle(
        basePercent: Int,
        correctionDelta: Int,
        neutralReversePercent: Int,
    ): Int {
        val rawPercent = basePercent + correctionDelta
        val constrained = when {
            basePercent > 0 -> rawPercent.coerceAtLeast(0)
            basePercent < 0 -> rawPercent.coerceAtMost(0)
            else -> rawPercent.coerceAtLeast(-neutralReversePercent)
        }
        return constrained.coerceIn(-100, 100)
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

    private fun startAppHeadingLock(command: ControlCommand, targetHeadingDegrees: Float) {
        activeHeadingLockCommand = command.asHeadingLockHeartbeat()
        appHeadingControlTargetDegrees = normalizeCompassDegrees(targetHeadingDegrees)
        appHeadingControlStartedAtMs = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                headingLockEnabled = true,
                appHeadingLockTargetDegrees = appHeadingControlTargetDegrees,
                appHeadingLockErrorDegrees = 0f,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
            )
        }
    }

    private fun startAppTurn(command: ControlCommand, targetHeadingDegrees: Float) {
        activeTurnCommand = command
        appHeadingControlTargetDegrees = normalizeCompassDegrees(targetHeadingDegrees)
        appHeadingControlStartedAtMs = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                headingLockEnabled = false,
                appHeadingLockTargetDegrees = appHeadingControlTargetDegrees,
                appHeadingLockErrorDegrees = 0f,
                appHeadingLockCorrectionPercent = 0,
                appHeadingLeftOutputPercent = null,
                appHeadingRightOutputPercent = null,
            )
        }
    }

    private fun failAppHeadingControl(message: String) {
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
                statusMessage = message,
            )
        }
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
                leftEscReversed = settings.leftEscReversed,
                rightEscReversed = settings.rightEscReversed,
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
                headingLockNeutralReversePercent = settings.headingLockNeutralReversePercent,
                voicePowerLimitPercent = settings.voicePowerLimitPercent,
                leftEscReversed = settings.leftEscReversed,
                rightEscReversed = settings.rightEscReversed,
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
            leftThrottlePercent = applyEscDirection(
                percent = leftPercent,
                reversed = settings.leftEscReversed,
            ),
            rightThrottlePercent = applyEscDirection(
                percent = rightPercent,
                reversed = settings.rightEscReversed,
            ),
            voicePowerLimitPercent = settings.voicePowerLimitPercent,
        )
    }

    private fun formatCommandLine(command: ControlCommand): String {
        if (command.mode == ControlCommandMode.TurnAngle) {
            return "App 本地角度转向，发送普通 SRC=${command.source.wireValue};ARM=1;L/R 心跳"
        }
        if (command.mode == ControlCommandMode.HeadingLock) {
            return if (command.headingLockEnabled) {
                "App 本地航向锁定，发送普通 SRC=${command.source.wireValue};ARM=1;L/R 心跳"
            } else {
                "退出 App 本地航向锁定，回到普通 L/R 心跳"
            }
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
                replyWithDetail("航向已锁定")
            } else {
                replyWithDetail("已取消航向锁定")
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
        val targetHeading = targetHeadingAfterTurn(command)
        val options = buildList {
            add(randomVoiceAck())
            add(replyWithDetail("${direction}${angle}度"))
            add("${randomVoiceAck()}，${direction}${angle}度")
            if (isHeadingLockActiveForState(mutableUiState.value)) {
                add(replyWithDetail("锁航目标${direction}${angle}度"))
                targetHeading?.let { add(replyWithDetail("已锁定${it}度")) }
            }
        }
        return options.random(Random.Default)
    }

    private fun targetHeadingAfterTurn(command: ControlCommand): Int? {
        val currentHeading = currentPhoneHeadingDegreesOrNull() ?: return null
        val offset = headingLockOffsetDegrees(command)
        return normalizeHeadingDegrees(currentHeading + offset)
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
        if (activeTurnCommand == null) {
            clearAppHeadingControlTarget()
        }
    }

    private fun updateActiveHeadingLockSource(
        source: CommandSource? = null,
    ): Boolean {
        val command = activeHeadingLockCommand ?: return false
        if (!command.headingLockEnabled) {
            return false
        }
        val nextSource = source ?: command.source
        activeHeadingLockCommand = command.copy(
            source = nextSource,
            headingLockTargetOffsetDegrees = null,
            voicePowerLimitPercent = mutableSettingsState.value.voicePowerLimitPercent,
        )
        return true
    }

    private fun ControlCommand.asHeadingLockHeartbeat(): ControlCommand {
        return if (mode == ControlCommandMode.HeadingLock) {
            copy(headingLockTargetOffsetDegrees = null)
        } else {
            this
        }
    }

    private fun clearAutonomousCommands() {
        activeTurnCommand = null
        activeHeadingLockCommand = null
        clearAutoNavigationExecution("自动导航已取消")
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
        mutableUiState.update {
            if (!it.autoNavigation.executing && it.autoNavigation.leftOutputPercent == 0 && it.autoNavigation.rightOutputPercent == 0) {
                it
            } else {
                it.copy(
                    autoNavigation = it.autoNavigation.copy(
                        executingRouteId = null,
                        targetPointIndex = 0,
                        distanceToTargetMeters = null,
                        headingErrorDegrees = null,
                        leftOutputPercent = 0,
                        rightOutputPercent = 0,
                        message = message,
                    ),
                )
            }
        }
    }

    private fun failAutoNavigation(message: String) {
        clearAutoNavigationExecution(message)
        mutableUiState.update {
            it.copy(
                armed = false,
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
            state.connectionState != ConnectionState.Connected -> "自动导航拒绝：未连接 ESP32"
            !state.armed -> "自动导航拒绝：请先手动解锁"
            currentGpsPointOrNull() == null -> "自动导航拒绝：GPS 未定位"
            gpsSatelliteCount() < AUTO_NAVIGATION_MIN_SATELLITES -> "自动导航拒绝：GPS 卫星数不足"
            currentPhoneHeadingDegreesOrNull() == null -> "自动导航拒绝：手机指南针暂无有效读数"
            else -> null
        }
    }

    private fun currentGpsPointOrNull(): NavigationRoutePoint? {
        val telemetry = mutableUiState.value.telemetry
        if (telemetry.statusFields["GPS_FIX"] != "1") {
            return null
        }
        val latitude = telemetry.statusFields["GPS_LAT"]?.toDoubleOrNull() ?: return null
        val longitude = telemetry.statusFields["GPS_LON"]?.toDoubleOrNull() ?: return null
        return NavigationRoutePoint(latitude, longitude)
    }

    private fun gpsSatelliteCount(): Int {
        return mutableUiState.value.telemetry.statusFields["GPS_SAT"]?.toIntOrNull() ?: 0
    }

    private fun bearingBetween(from: NavigationRoutePoint, to: NavigationRoutePoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
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

    private fun applyEscDirection(percent: Int, reversed: Boolean): Int {
        return if (reversed) -percent else percent
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
        val activeTransport = transport ?: return false
        return runCatching {
            activeTransport.send(command.withRuntimeHeadingSource())
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
        val updateStateBeforeDisconnect = mutableUpdateState.value
        transport = null
        telemetryJob?.cancel()
        commandHeartbeatJob?.cancel()
        clearAutonomousCommands()
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
                statusMessage = "蓝牙链路异常：${error.message ?: "未知错误"}",
            )
        }
        mutableSettingsState.update {
            it.copy(message = "蓝牙链路异常：${error.message ?: "未知错误"}")
        }
        if (
            updateStateBeforeDisconnect.esp32Uploading ||
            updateStateBeforeDisconnect.message.contains("OTA") ||
            updateStateBeforeDisconnect.message.contains("固件已发送完成") ||
            updateStateBeforeDisconnect.message.contains("正在重启")
        ) {
            mutableUpdateState.update {
                it.copy(
                    esp32Uploading = false,
                    message = "蓝牙已断开，ESP32 可能已重启；请重新连接后确认固件状态",
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
                handleControllerErrorLine(telemetry.lastReceivedStatus)
                handleOtaStatusLine(telemetry.lastReceivedStatus)
                mutableUiState.update { it.copy(telemetry = telemetry) }
            }
        }
    }

    private fun handleOtaStatusLine(line: String) {
        if (!line.startsWith("OTA;")) {
            return
        }
        when {
            line.startsWith("OTA;READY") -> {
                mutableUpdateState.update {
                    it.copy(message = "ESP32 已进入 OTA 接收模式")
                }
            }
            line.startsWith("OTA;PROGRESS=") -> {
                mutableUpdateState.update {
                    it.copy(message = "ESP32 正在写入固件", progressText = line.removePrefix("OTA;PROGRESS="))
                }
            }
            line.startsWith("OTA;OK") -> {
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "ESP32 固件校验通过，设备正在重启；蓝牙断开后请重新连接",
                        progressText = "",
                    )
                }
            }
            line.startsWith("OTA;ERR=") -> {
                mutableUpdateState.update {
                    it.copy(
                        esp32Uploading = false,
                        message = "ESP32 固件更新失败：${line.removePrefix("OTA;ERR=")}",
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

    private fun handleTrackLogEvent(event: TrackLogEvent) {
        when (event) {
            is TrackLogEvent.Info -> handleTrackLogInfo(event.info.oldestSequence, event.info.newestSequence, event.info)
            is TrackLogEvent.Begin -> {
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
            is TrackLogEvent.Point -> handleTrackLogPoint(event.point)
            is TrackLogEvent.End -> handleTrackLogEnd(event.nextSequence)
            is TrackLogEvent.Error -> refreshGpsTrackState("轨迹同步错误：${event.message}")
        }
    }

    private fun handleTrackLogPoint(point: com.smartsup.controller.model.GpsTrackPoint) {
        val saved = gpsTrackStore.appendIfNew(point)
        mutableUiState.update {
            val start = it.gpsTrack.syncStartSequence
            val target = it.gpsTrack.syncTargetSequence
            val total = if (start != null && target != null) {
                (target - start + 1).coerceAtLeast(1)
            } else {
                null
            }
            val done = if (start != null && total != null) {
                (point.sequence - start + 1).coerceIn(0, total)
            } else {
                null
            }
            it.copy(
                gpsTrack = it.gpsTrack.copy(
                    syncing = true,
                    syncCurrentSequence = point.sequence,
                    storedPointCount = if (saved) it.gpsTrack.storedPointCount + 1 else it.gpsTrack.storedPointCount,
                    lastSyncedSequence = maxOf(it.gpsTrack.lastSyncedSequence, point.sequence),
                    syncMessage = if (done != null && total != null) {
                        "同步中 $done/$total，seq ${point.sequence}"
                    } else {
                        "已同步到 seq ${point.sequence}"
                    },
                ),
            )
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
            return "ESP32 没有新增有效轨迹：GPS 未定位，卫星 $satellites，天线 $antenna，缓存 $trackCount/${info.capacity}"
        }
        if (info.count == 0) {
            return "ESP32 轨迹缓存为空：GPS 已定位但还没有写入轨迹点"
        }
        return "ESP32 暂无新轨迹点，缓存 $trackCount/${info.capacity}"
    }

    private fun handleTrackLogEnd(nextSequence: Int) {
        val info = mutableUiState.value.gpsTrack.latestInfo
        val nextTransport = transport
        if (info != null && nextTransport != null && nextSequence in 1..info.newestSequence) {
            refreshGpsTrackState("继续同步 seq $nextSequence..${info.newestSequence}", syncing = true)
            sendTrackLogRead(nextTransport, nextSequence)
        } else {
            refreshGpsTrackState("轨迹同步完成")
        }
    }

    private fun sendTrackLogRead(nextTransport: ControlTransport, fromSequence: Int) {
        trackLogSyncJob?.cancel()
        trackLogSyncJob = viewModelScope.launch {
            delay(120)
            runCatching {
                nextTransport.sendRawLine("LOG_READ;FROM=$fromSequence;LIMIT=32")
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

    private fun handleControllerErrorLine(line: String) {
        if (!line.startsWith("ERR;") || line == lastHandledControllerErrorLine) {
            return
        }
        lastHandledControllerErrorLine = line

        val hadAutonomousCommand = activeTurnCommand != null || activeHeadingLockCommand != null
        if (!hadAutonomousCommand) {
            mutableUiState.update {
                it.copy(statusMessage = "ESP32 错误：${line.removePrefix("ERR;")}")
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
                statusMessage = "ESP32 拒绝自主控制：${line.removePrefix("ERR;")}，已回到手动心跳",
            )
        }
        sendCurrentCommand()
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
            headingLockToleranceDegrees = coerceHeadingLockToleranceDegrees(
                degrees = preferences.getInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, 4),
                fullCorrectionDegrees = preferences.getInt(KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES, 6),
            ),
            headingLockFullCorrectionDegrees = coerceHeadingLockFullCorrectionDegrees(
                degrees = preferences.getInt(KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES, 6),
                toleranceDegrees = preferences.getInt(KEY_HEADING_LOCK_TOLERANCE_DEGREES, 4).coerceIn(1, 20),
            ),
            headingLockNeutralReversePercent = coerceHeadingLockNeutralReversePercent(
                preferences.getInt(KEY_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT, 70),
            ),
            usePhoneHeading = true,
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
        stopPhoneHeadingSensor(clearReading = true)
        voiceReplyEngine?.stop()
        voiceReplyEngine?.shutdown()
        voiceReplyEngine = null
        voiceReplyReady = false
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
        private const val KEY_VOICE_POWER_LIMIT = "voice_power_limit"
        private const val KEY_FINE_TUNE_STEP_PERCENT = "fine_tune_step_percent"
        private const val KEY_RAMP_LIMIT = "ramp_limit"
        private const val KEY_HEADING_LOCK_TOLERANCE_DEGREES = "heading_lock_tolerance_degrees"
        private const val KEY_HEADING_LOCK_FULL_CORRECTION_DEGREES = "heading_lock_full_correction_degrees"
        private const val KEY_HEADING_LOCK_NEUTRAL_REVERSE_PERCENT = "heading_lock_neutral_reverse_percent"
        private const val KEY_LEFT_ESC_REVERSED = "left_esc_reversed"
        private const val KEY_RIGHT_ESC_REVERSED = "right_esc_reversed"
        private const val KEY_USE_PHONE_HEADING = "use_phone_heading"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GEAR_PREFIX = "gear_percent_"
        private const val COMMAND_HEARTBEAT_MS = 100L
        private const val PHONE_HEADING_STALE_MS = 1_500L
        private const val APP_TURN_TIMEOUT_MS = 8_000L
        private const val APP_TURN_DONE_DEGREES = 3.0f
        private const val HEADING_LOCK_MIN_CORRECTION_PERCENT = 3
        private const val HEADING_LOCK_MAX_CORRECTION_PERCENT = 70
        private const val AUTO_NAVIGATION_MIN_SATELLITES = 4
        private const val AUTO_NAVIGATION_ARRIVAL_RADIUS_METERS = 8.0
        private const val AUTO_NAVIGATION_MAX_CORRECTION_PERCENT = 25
        private const val AUTO_NAVIGATION_FULL_CORRECTION_DEGREES = 45f
        private const val AUTO_NAVIGATION_STOP_TURN_DEGREES = 120f
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
        private val AUTO_NAVIGATION_GEAR_PERCENTS = listOf(15, 22, 30)
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

    private fun coerceHeadingLockNeutralReversePercent(percent: Int): Int {
        return percent.coerceIn(0, 100)
    }

    private fun lockForDirectionChange(message: String) {
        mutableUiState.update {
            it.copy(
                armed = false,
                leftThrottlePercent = 0,
                rightThrottlePercent = 0,
                commandSource = CommandSource.App,
                headingLockEnabled = false,
                selectedGear = ThrottleGear.Neutral,
                throttleTrimPercent = 0,
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
