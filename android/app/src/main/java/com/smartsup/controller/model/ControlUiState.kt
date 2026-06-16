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
    val voiceInputText: String = "",
    val voiceResultMessage: String = "语音控制待输入",
    val voiceCandidatePreview: String = "无候选",
    val voiceCommandPreview: String = "不发送",
    val voiceControlEnabled: Boolean = true,
    val voiceAsrState: VoiceAsrState = VoiceAsrState.Starting,
    val voiceAsrStatus: String = "Qwen ASR：初始化模型",
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
