package com.smartsup.controller.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.control.ControlViewModel
import com.smartsup.controller.model.AppTab
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.RealtimeVoiceMode
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.service.ControlForegroundService
import com.smartsup.controller.voice.ArkAudioAgentConfig
import com.smartsup.controller.voice.ArkAudioAgentSession
import com.smartsup.controller.voice.QwenAsrSession
import com.smartsup.controller.voice.RealtimeVoiceModeValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppScreen(viewModel: ControlViewModel) {
    val controlState by viewModel.uiState.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Control) }

    AppForegroundKeepAliveHost(
        connected = controlState.connectionState == ConnectionState.Connected,
        microphoneRequested = controlState.voiceControlEnabled || controlState.voiceSamplingEnabled,
    )

    AppAsrHost(
        voiceControlEnabled = controlState.voiceControlEnabled,
        voiceSamplingEnabled = controlState.voiceSamplingEnabled,
        voiceReplySuppressingRecognition = controlState.voiceReplySuppressingRecognition,
        onStatusChange = viewModel::setVoiceAsrStatus,
        onStarting = viewModel::markVoiceAsrStarting,
        onStopped = viewModel::markVoiceAsrStopped,
        onPartialText = viewModel::setVoiceInputText,
        onFinalText = viewModel::acceptVoiceRecognition,
        onFinalSegment = viewModel::acceptVoiceSample,
    )

    AppRealtimeVoiceHost(
        voiceControlEnabled = controlState.voiceControlEnabled,
        realtimeVoiceMode = controlState.realtimeVoiceMode,
        arkApiKey = settingsState.realtimeVoiceApiKey,
        model = settingsState.realtimeVoiceModel,
        voice = settingsState.realtimeVoiceVoice,
        ttsMode = settingsState.realtimeTtsMode,
        cloudTtsApiKey = BuildConfig.DOUBAO_API_KEY.ifBlank { settingsState.realtimeVoiceApiKey },
        wakeWordRequired = controlState.realtimeWakeWordRequired,
        systemStateProvider = { buildArkAgentStatePrompt(controlState, settingsState) },
        onStatusChange = viewModel::setRealtimeVoiceStatus,
        onTranscript = viewModel::setRealtimeVoiceTranscript,
        onReply = viewModel::setRealtimeVoiceReply,
        onControlEvent = viewModel::acceptRealtimeVoiceControlEventJson,
        onMetrics = viewModel::setRealtimeVoiceMetrics,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val tabColor = tab.color()
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon(),
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = tabColor,
                            selectedTextColor = tabColor,
                            indicatorColor = tabColor.copy(alpha = 0.14f),
                            unselectedIconColor = tabColor.copy(alpha = 0.72f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { paddingValues ->
        val modifier = Modifier.padding(paddingValues)
        when (selectedTab) {
            AppTab.Navigation -> NavigationScreen(
                state = controlState,
                modifier = modifier,
                onSyncTrack = viewModel::requestTrackLogSync,
                onPlaybackIndexChange = viewModel::setGpsPlaybackIndex,
                onTrackSelected = viewModel::selectGpsTrack,
                onTrackDeleted = viewModel::deleteGpsTrack,
                onAddRoute = viewModel::addAutoNavigationRoute,
                onSelectRoute = viewModel::selectAutoNavigationRoute,
                onClearSelectedRoute = viewModel::clearSelectedAutoNavigationRoute,
                onEditRoute = viewModel::editAutoNavigationRoute,
                onAddRoutePoint = viewModel::addAutoNavigationRoutePoint,
                onUndoRoutePoint = viewModel::removeLastAutoNavigationRoutePoint,
                onSaveRoute = viewModel::saveAutoNavigationRoute,
                onCancelRouteEditing = viewModel::cancelAutoNavigationRouteEditing,
                onExecuteRoute = viewModel::startAutoNavigation,
                onDeleteRoute = viewModel::deleteAutoNavigationRoute,
                onIncreaseAutoGear = viewModel::increaseAutoNavigationGear,
                onDecreaseAutoGear = viewModel::decreaseAutoNavigationGear,
                onStopAutoNavigation = viewModel::stopAutoNavigation,
            )
            AppTab.Control -> ControlScreen(
                state = controlState,
                maxThrottlePercent = settingsState.maxThrottlePercent,
                fineTuneStepPercent = settingsState.fineTuneStepPercent,
                gearPercents = settingsState.gearPercents,
                leftEscReversed = settingsState.leftEscReversed,
                rightEscReversed = settingsState.rightEscReversed,
                modifier = modifier,
                onArm = { viewModel.setArmed(true) },
                onDisarm = { viewModel.setArmed(false) },
                onLeftThrottleChange = viewModel::setLeftThrottle,
                onRightThrottleChange = viewModel::setRightThrottle,
                onLeftThrottleRelease = viewModel::returnLeftThrottleToGear,
                onRightThrottleRelease = viewModel::returnRightThrottleToGear,
                onGearSelected = viewModel::setThrottleGear,
                onFineTuneDecrease = viewModel::decreaseThrottleFineTune,
                onFineTuneIncrease = viewModel::increaseThrottleFineTune,
                onEnableHeadingLock = viewModel::enableHeadingLock,
                onDisableHeadingLock = viewModel::cancelHeadingLock,
                onSetTargetHeading = viewModel::setHeadingLockTargetHeading,
                onConnectBluetooth = viewModel::connectSavedBluetooth,
                onToggleVoiceControl = viewModel::toggleVoiceControl,
                onEmergencyStop = viewModel::emergencyStop,
            )
            AppTab.Voice -> VoiceTestScreen(
                state = controlState,
                settingsState = settingsState,
                modifier = modifier,
                onToggleRealtimeVoice = viewModel::toggleVoiceControl,
                onPushToTalkStart = viewModel::startRealtimePushToTalk,
                onPushToTalkStop = viewModel::stopRealtimePushToTalk,
                onRealtimeTtsModeChange = viewModel::setRealtimeTtsMode,
                onWakeWordRequiredChange = viewModel::setRealtimeWakeWordRequired,
                onRealtimeControlEvent = viewModel::acceptRealtimeVoiceControlEventJson,
                onVoiceInputChange = viewModel::setVoiceInputText,
                onVoiceSamplingEnabledChange = viewModel::setVoiceSamplingEnabled,
                onNextVoiceSampleTarget = viewModel::nextVoiceSampleTarget,
                onSaveVoiceSample = viewModel::savePendingVoiceSample,
                onDiscardVoiceSample = viewModel::discardPendingVoiceSample,
            )
            AppTab.Settings -> SettingsScreen(
                controlState = controlState,
                settingsState = settingsState,
                updateState = updateState,
                modifier = modifier,
                onRefreshBluetooth = viewModel::refreshBluetoothDevices,
                onScanBluetooth = viewModel::startBluetoothDiscovery,
                onConnectSaved = viewModel::connectSavedBluetooth,
                onConnectDevice = viewModel::connectBluetooth,
                onDisconnect = viewModel::disconnectBluetooth,
                onAutoReconnectChange = viewModel::setAutoReconnect,
                onMaxThrottleChange = viewModel::setMaxThrottlePercent,
                onVoicePowerLimitChange = viewModel::setVoicePowerLimitPercent,
                onFineTuneStepChange = viewModel::setFineTuneStepPercent,
                onGearThrottleChange = viewModel::setGearThrottlePercent,
                onRampLimitChange = viewModel::setRampLimitEnabled,
                onLeftEscReversedChange = viewModel::setLeftEscReversed,
                onRightEscReversedChange = viewModel::setRightEscReversed,
                onHeadingLockToleranceChange = viewModel::setHeadingLockToleranceDegrees,
                onHeadingLockFullCorrectionChange = viewModel::setHeadingLockFullCorrectionDegrees,
                onHeadingLockNeutralPivotMinDifferenceChange =
                    viewModel::setHeadingLockNeutralPivotMinDifferencePercent,
                onHeadingLockNeutralPivotMaxDifferenceChange =
                    viewModel::setHeadingLockNeutralPivotMaxDifferencePercent,
                onAutoNavigationGpsJumpResetChange = viewModel::setAutoNavigationGpsJumpResetMeters,
                onUsePhoneHeadingChange = viewModel::setUsePhoneHeading,
                onRealtimeVoiceEndpointChange = viewModel::setRealtimeVoiceEndpoint,
                onRealtimeVoiceAppIdChange = viewModel::setRealtimeVoiceAppId,
                onRealtimeVoiceApiKeyChange = viewModel::setRealtimeVoiceApiKey,
                onRealtimeVoiceModelChange = viewModel::setRealtimeVoiceModel,
                onRealtimeVoiceVoiceChange = viewModel::setRealtimeVoiceVoice,
                onStartMagCalibration = viewModel::startMagCalibration,
                onSaveMagCalibration = viewModel::saveMagCalibration,
                onClearMagCalibration = viewModel::clearMagCalibration,
                onRefreshMagCalibrationStatus = viewModel::refreshMagCalibrationStatus,
                onCheckUpdates = viewModel::checkForUpdates,
                onInstallAppUpdate = viewModel::installLatestAppUpdate,
                onUpdateEsp32FromGitHub = viewModel::downloadAndUploadLatestEsp32Firmware,
                onUploadLocalEsp32Firmware = viewModel::uploadLocalEsp32Firmware,
            )
        }
    }
}

@Composable
private fun AppForegroundKeepAliveHost(
    connected: Boolean,
    microphoneRequested: Boolean,
) {
    val context = LocalContext.current
    val microphoneGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(connected, microphoneRequested, microphoneGranted) {
        if (!connected && (!microphoneRequested || !microphoneGranted)) {
            ControlForegroundService.stop(context)
            return@LaunchedEffect
        }
        ControlForegroundService.start(
            context = context,
            microphoneActive = microphoneRequested && microphoneGranted,
        )
    }
}

@Composable
private fun AppRealtimeVoiceHost(
    voiceControlEnabled: Boolean,
    realtimeVoiceMode: RealtimeVoiceMode,
    arkApiKey: String,
    model: String,
    voice: String,
    ttsMode: com.smartsup.controller.model.RealtimeTtsMode,
    cloudTtsApiKey: String,
    wakeWordRequired: Boolean,
    systemStateProvider: () -> String,
    onStatusChange: (String) -> Unit,
    onTranscript: (String) -> Unit,
    onReply: (String) -> Unit,
    onControlEvent: (String) -> String,
    onMetrics: (String) -> Unit,
) {
    val context = LocalContext.current
    val latestOnStatusChange = rememberUpdatedState(onStatusChange)
    val latestOnTranscript = rememberUpdatedState(onTranscript)
    val latestOnReply = rememberUpdatedState(onReply)
    val latestOnControlEvent = rememberUpdatedState(onControlEvent)
    val latestOnMetrics = rememberUpdatedState(onMetrics)
    val latestSystemStateProvider = rememberUpdatedState(systemStateProvider)
    val session = remember(context, arkApiKey, model, voice, ttsMode, cloudTtsApiKey, wakeWordRequired) {
        ArkAudioAgentSession(
            context = context,
            config = ArkAudioAgentConfig(
                arkApiKey = arkApiKey,
                model = model,
                voice = voice,
                ttsMode = ttsMode,
                cloudTtsApiKey = cloudTtsApiKey,
                wakeWordRequired = wakeWordRequired,
                wakeWordAsrApiKey = cloudTtsApiKey,
            ),
            systemStateProvider = { latestSystemStateProvider.value() },
            onStatus = { latestOnStatusChange.value(it) },
            onTranscript = { latestOnTranscript.value(it) },
            onReply = { latestOnReply.value(it) },
            onControlEvent = { latestOnControlEvent.value(it) },
            onMetrics = { latestOnMetrics.value(it) },
        )
    }

    LaunchedEffect(voiceControlEnabled, realtimeVoiceMode, session) {
        if (!voiceControlEnabled || realtimeVoiceMode == RealtimeVoiceMode.Off) {
            session.stop()
            return@LaunchedEffect
        }
        val mode = when (realtimeVoiceMode) {
            RealtimeVoiceMode.PushToTalk -> RealtimeVoiceModeValue.PushToTalk
            RealtimeVoiceMode.Live -> RealtimeVoiceModeValue.Live
            RealtimeVoiceMode.Off -> RealtimeVoiceModeValue.Live
        }
        session.start(mode)
    }

    DisposableEffect(session) {
        onDispose { session.destroy() }
    }
}

private fun buildArkAgentStatePrompt(
    state: ControlUiState,
    settings: SettingsUiState,
): String {
    val fields = state.telemetry.statusFields
    val gpsModule = when (fields["GPS"]) {
        "1" -> "在线"
        "0" -> "离线"
        else -> "--"
    }
    val gpsFix = when (fields["GPS_FIX"]) {
        "1" -> "已定位"
        "0" -> "未定位"
        else -> "--"
    }
    val phoneHeading = state.phoneHeadingDegrees
        ?.takeIf { state.phoneHeadingAvailable }
        ?.let { "${"%.1f".format(Locale.US, it)}°" }
        ?: "不可用"
    val connected = if (state.connectionState == ConnectionState.Connected) "已连接" else "未连接"
    val unlocked = if (state.armed) "已解锁" else "未解锁"
    val leftOutputPercent = state.appHeadingLeftOutputPercent ?: state.leftThrottlePercent
    val rightOutputPercent = state.appHeadingRightOutputPercent ?: state.rightThrottlePercent
    val leftCommandPercent = state.appHeadingLeftCommandPercent ?: leftOutputPercent
    val rightCommandPercent = state.appHeadingRightCommandPercent ?: rightOutputPercent
    val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
    return listOf(
        "当前状态：",
        "- 时间：$timeText",
        "- 主控：$connected；解锁：$unlocked；声控上限：${settings.voicePowerLimitPercent}%",
        "- 推进器目标：左推进器${state.leftThrottlePercent}%，右推进器${state.rightThrottlePercent}%；来源：${state.commandSource.name}",
        "- 推进器当前输出：用户视角左${leftOutputPercent}%、右${rightOutputPercent}%；实际下发 L=${leftCommandPercent}、R=${rightCommandPercent}",
        "- 手机航向：$phoneHeading；航向锁定：${if (state.headingLockEnabled) "开启" else "关闭"}",
        "- GPS：模块$gpsModule；定位$gpsFix；卫星${fields["GPS_SAT"] ?: "--"}；天线${fields["GPS_ANT"] ?: "--"}",
        "- 电池：${state.telemetry.batteryVoltage?.let { "%.1fV".format(Locale.US, it) } ?: "--"}；故障：${fields["FAULT"] ?: "无"}",
        "状态约束：未连接或未解锁时，不要调用推进/转向工具；可说明需要先连接并手动解锁。停/停止推进调用空挡工具；关闭/停止声控调用关闭语音工具；急停/锁主控不开放。",
    ).joinToString("\n")
}

@Composable
private fun AppAsrHost(
    voiceControlEnabled: Boolean,
    voiceSamplingEnabled: Boolean,
    voiceReplySuppressingRecognition: Boolean,
    onStatusChange: (String) -> Unit,
    onStarting: (String) -> Unit,
    onStopped: (String) -> Unit,
    onPartialText: (String) -> Unit,
    onFinalText: (String) -> Unit,
    onFinalSegment: (String, FloatArray) -> Unit,
) {
    val context = LocalContext.current
    val latestOnPartialText = rememberUpdatedState(onPartialText)
    val latestOnFinalText = rememberUpdatedState(onFinalText)
    val latestOnFinalSegment = rememberUpdatedState(onFinalSegment)
    val latestOnStatusChange = rememberUpdatedState(onStatusChange)
    val latestOnStarting = rememberUpdatedState(onStarting)
    val latestOnStopped = rememberUpdatedState(onStopped)
    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordAudioPermission = granted
        if (granted) {
            latestOnStarting.value("Qwen ASR：初始化模型")
        } else {
            latestOnStatusChange.value("Qwen ASR：未获得录音权限")
        }
    }
    val qwenAsrSession = remember(context) {
        QwenAsrSession(
            context = context,
            onStatusChange = { latestOnStatusChange.value(it) },
            onPartialText = { text -> latestOnPartialText.value(text) },
            onFinalText = { text -> latestOnFinalText.value(text) },
            onFinalSegment = { text, samples -> latestOnFinalSegment.value(text, samples) },
        )
    }

    LaunchedEffect(voiceControlEnabled, voiceSamplingEnabled, hasRecordAudioPermission) {
        val shouldRequestMic = voiceControlEnabled || voiceSamplingEnabled
        if (shouldRequestMic && !hasRecordAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(hasRecordAudioPermission, voiceControlEnabled, voiceSamplingEnabled, qwenAsrSession) {
        val shouldRunAsr = hasRecordAudioPermission && voiceSamplingEnabled
        if (shouldRunAsr) {
            latestOnStarting.value("本地 ASR：采样模式初始化")
            qwenAsrSession.start()
        } else {
            qwenAsrSession.stop()
            latestOnStopped.value(
                when {
                    !hasRecordAudioPermission && (voiceControlEnabled || voiceSamplingEnabled) -> "本地 ASR：等待录音权限"
                    else -> "本地 ASR：已暂缓"
                },
            )
        }
    }

    LaunchedEffect(voiceReplySuppressingRecognition, qwenAsrSession) {
        qwenAsrSession.setInputSuppressed(voiceReplySuppressingRecognition)
    }

    DisposableEffect(qwenAsrSession) {
        onDispose { qwenAsrSession.destroy() }
    }
}

private fun AppTab.icon() = when (this) {
    AppTab.Navigation -> Icons.Outlined.Explore
    AppTab.Control -> Icons.Outlined.Tune
    AppTab.Voice -> Icons.Outlined.Mic
    AppTab.Settings -> Icons.Outlined.Settings
}

private fun AppTab.color() = when (this) {
    AppTab.Navigation -> Color(0xFF1565C0)
    AppTab.Control -> Color(0xFF2E7D32)
    AppTab.Voice -> Color(0xFFE65100)
    AppTab.Settings -> Color(0xFF546E7A)
}
