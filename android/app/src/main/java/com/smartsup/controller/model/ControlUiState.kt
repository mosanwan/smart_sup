package com.smartsup.controller.model

data class ControlUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val armed: Boolean = false,
    val leftThrottlePercent: Int = 0,
    val rightThrottlePercent: Int = 0,
    val commandSource: CommandSource = CommandSource.App,
    val headingLockEnabled: Boolean = false,
    val selectedGear: ThrottleGear = ThrottleGear.Default,
    val throttleTrimPercent: Int = 0,
    val telemetry: Telemetry = Telemetry(),
    val gpsTrack: GpsTrackUiState = GpsTrackUiState(),
    val autoNavigation: AutoNavigationUiState = AutoNavigationUiState(),
    val phoneHeadingDegrees: Float? = null,
    val phoneHeadingAvailable: Boolean = false,
    val phoneHeadingSensorName: String = "",
    val appHeadingLockTargetDegrees: Float? = null,
    val appHeadingLockErrorDegrees: Float? = null,
    val appHeadingLockCorrectionPercent: Int = 0,
    val appHeadingLeftOutputPercent: Int? = null,
    val appHeadingRightOutputPercent: Int? = null,
    val voiceInputText: String = "",
    val voiceResultMessage: String = "声控已关闭",
    val voiceCandidatePreview: String = "无候选",
    val voiceCommandPreview: String = "不发送",
    val voiceControlEnabled: Boolean = false,
    val voiceAsrState: VoiceAsrState = VoiceAsrState.Stopped,
    val voiceAsrStatus: String = "Qwen ASR：已暂停",
    val voiceReplySuppressingRecognition: Boolean = false,
    val voiceSamplingEnabled: Boolean = false,
    val voiceSampleTargetLabel: String = "开始声控",
    val voiceSampleTargetText: String = "开始声控",
    val voiceSampleExpectedCommand: String = "本地状态：恢复执行语音控制命令",
    val voiceSamplePendingText: String = "",
    val voiceSamplePendingCommand: String = "无待保存样本",
    val voiceSampleLastMessage: String = "采样模式未开启",
    val voiceSampleSavedCount: Int = 0,
    val voiceSampleDirectory: String = "",
    val statusMessage: String = "未连接，推进输出保持空闲",
) {
    val canSendThrottle: Boolean
        get() = connectionState == ConnectionState.Connected && armed
}

enum class VoiceAsrState {
    Stopped,
    Starting,
    Ready,
}
