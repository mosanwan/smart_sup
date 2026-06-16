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
import com.smartsup.controller.control.ControlViewModel
import com.smartsup.controller.model.AppTab
import com.smartsup.controller.service.ControlForegroundService
import com.smartsup.controller.voice.QwenAsrSession

@Composable
fun AppScreen(viewModel: ControlViewModel) {
    val controlState by viewModel.uiState.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Control) }

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
                onToggleVoiceControl = viewModel::toggleVoiceControl,
                onEmergencyStop = viewModel::emergencyStop,
            )
            AppTab.Voice -> VoiceTestScreen(
                state = controlState,
                modifier = modifier,
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
                onHeadingLockNeutralReverseChange = viewModel::setHeadingLockNeutralReversePercent,
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

    LaunchedEffect(Unit) {
        if (!hasRecordAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(hasRecordAudioPermission, voiceControlEnabled, voiceSamplingEnabled, qwenAsrSession) {
        val shouldRunAsr = hasRecordAudioPermission &&
            (voiceControlEnabled || voiceSamplingEnabled)
        if (shouldRunAsr) {
            ControlForegroundService.start(context)
            latestOnStarting.value("Qwen ASR：初始化模型")
            qwenAsrSession.start()
        } else {
            qwenAsrSession.stop()
            ControlForegroundService.stop(context)
            latestOnStopped.value(
                when {
                    !hasRecordAudioPermission -> "Qwen ASR：等待录音权限"
                    else -> "Qwen ASR：已暂停"
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
