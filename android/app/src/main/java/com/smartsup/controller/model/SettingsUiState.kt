package com.smartsup.controller.model

import com.smartsup.controller.BuildConfig

data class SettingsUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val pairedDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discoveredDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discovering: Boolean = false,
    val savedDevice: BluetoothDeviceInfo? = null,
    val autoReconnect: Boolean = true,
    val maxThrottlePercent: Int = 30,
    val voicePowerLimitPercent: Int = 70,
    val fineTuneStepPercent: Int = 3,
    val gearPercents: Map<ThrottleGear, Int> = ThrottleGear.defaultPercents(),
    val leftEscReversed: Boolean = false,
    val rightEscReversed: Boolean = false,
    val rampLimitEnabled: Boolean = true,
    val headingLockToleranceDegrees: Int = 4,
    val headingLockFullCorrectionDegrees: Int = 6,
    val headingLockNeutralPivotForwardPercent: Int = 28,
    val headingLockNeutralPivotReversePercent: Int = 31,
    val autoNavigationGpsJumpResetMeters: Int = 5,
    val usePhoneHeading: Boolean = true,
    val realtimeVoiceEndpoint: String = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue",
    val realtimeVoiceAppId: String = BuildConfig.DOUBAO_APP_ID,
    val realtimeVoiceApiKey: String = BuildConfig.ARK_API_KEY,
    val realtimeVoiceModel: String = "doubao-seed-2-0-lite-260428",
    val realtimeVoiceVoice: String = "zh_female_vv_uranus_bigtts",
    val realtimeTtsMode: RealtimeTtsMode = RealtimeTtsMode.Local,
    val cloudTtsConfigured: Boolean = BuildConfig.DOUBAO_API_KEY.isNotBlank() || BuildConfig.ARK_API_KEY.isNotBlank(),
    val githubToken: String = "",
    val deviceNamePrefix: String = "SmartSUP-",
    val message: String = "在 App 内扫描并连接 SmartSUP ESP32 设备",
)

enum class RealtimeTtsMode {
    Local,
    Cloud,
}
