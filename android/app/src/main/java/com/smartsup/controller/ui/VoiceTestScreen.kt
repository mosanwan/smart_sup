package com.smartsup.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.RealtimeTtsMode
import com.smartsup.controller.model.SettingsUiState

@Composable
fun VoiceTestScreen(
    state: ControlUiState,
    settingsState: SettingsUiState,
    modifier: Modifier = Modifier,
    onToggleRealtimeVoice: () -> Unit,
    onPushToTalkStart: () -> Unit,
    onPushToTalkStop: () -> Unit,
    onRealtimeTtsModeChange: (RealtimeTtsMode) -> Unit,
    onWakeWordRequiredChange: (Boolean) -> Unit,
    onRealtimeControlEvent: (String) -> Unit,
    onVoiceInputChange: (String) -> Unit,
    onVoiceSamplingEnabledChange: (Boolean) -> Unit,
    onNextVoiceSampleTarget: () -> Unit,
    onSaveVoiceSample: (Boolean) -> Unit,
    onDiscardVoiceSample: () -> Unit,
) {
    var samplingDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VoiceStatusCard(
            value = state.realtimeVoiceStatus,
        )

        RealtimeVoiceAgentCard(
            state = state,
            settingsState = settingsState,
            onToggleRealtimeVoice = onToggleRealtimeVoice,
            onPushToTalkStart = onPushToTalkStart,
            onPushToTalkStop = onPushToTalkStop,
            onRealtimeTtsModeChange = onRealtimeTtsModeChange,
            onWakeWordRequiredChange = onWakeWordRequiredChange,
            onRealtimeControlEvent = onRealtimeControlEvent,
        )

        VoiceTranscriptionCard(
            value = state.voiceInputText,
            onValueChange = onVoiceInputChange,
        )

        VoiceSamplingEntryButton(
            state = state,
            onClick = {
                samplingDialogVisible = true
                onVoiceSamplingEnabledChange(true)
            },
        )

        VoiceCommandListCard()

        VoiceInfoCard(
            title = "候选命令",
            value = state.voiceCandidatePreview,
            icon = Icons.Outlined.Info,
            color = Color(0xFFE65100),
        )

        VoiceInfoCard(
            title = "控制命令",
            value = state.voiceCommandPreview,
            emphasized = true,
            icon = Icons.Outlined.Terminal,
            color = Color(0xFF2E7D32),
        )

        VoiceInfoCard(
            title = "执行结果",
            value = state.voiceResultMessage,
            icon = Icons.Outlined.CheckCircle,
            color = Color(0xFF1565C0),
        )

        VoiceInfoCard(
            title = "安全状态",
            value = "连接=${connectionText(state.connectionState)}；锁=${if (state.armed) "已解锁" else "锁定"}；声控=${if (state.voiceControlEnabled) "开启" else "停止"}",
            icon = Icons.Outlined.Security,
            color = if (state.armed) Color(0xFF2E7D32) else Color(0xFFC62828),
        )
    }

    if (samplingDialogVisible) {
        VoiceSamplingDialog(
            state = state,
            onDismiss = {
                samplingDialogVisible = false
                onVoiceSamplingEnabledChange(false)
            },
            onEnabledChange = onVoiceSamplingEnabledChange,
            onVoiceInputChange = onVoiceInputChange,
            onSaveCorrect = { onSaveVoiceSample(true) },
            onSaveFailure = { onSaveVoiceSample(false) },
            onDiscard = onDiscardVoiceSample,
            onNextTarget = onNextVoiceSampleTarget,
        )
    }
}

@Composable
private fun VoiceStatusCard(value: String) {
    val color = Color(0xFF1565C0)
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "实时语音状态",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "实时语音状态",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
            )
            Text(
                value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RealtimeVoiceAgentCard(
    state: ControlUiState,
    settingsState: SettingsUiState,
    onToggleRealtimeVoice: () -> Unit,
    onPushToTalkStart: () -> Unit,
    onPushToTalkStop: () -> Unit,
    onRealtimeTtsModeChange: (RealtimeTtsMode) -> Unit,
    onWakeWordRequiredChange: (Boolean) -> Unit,
    onRealtimeControlEvent: (String) -> Unit,
) {
    val color = if (state.voiceControlEnabled) Color(0xFF2E7D32) else Color(0xFF546E7A)
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceSectionHeader(
                title = "云端实时语音 Agent",
                icon = Icons.Outlined.CloudQueue,
                color = color,
            )
            Text(
                "模式：${state.realtimeVoiceMode.name}；${state.realtimeVoiceMetrics}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "TTS：${if (settingsState.realtimeTtsMode == RealtimeTtsMode.Cloud) "云端" else "本地"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (settingsState.realtimeTtsMode == RealtimeTtsMode.Cloud) {
                            if (settingsState.cloudTtsConfigured) {
                                "云端音色：${settingsState.realtimeVoiceVoice}"
                            } else {
                                "缺少 API Key，将回退本地 TTS"
                            }
                        } else {
                            "使用 Android 系统 TTS，设置页音色不生效"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = settingsState.realtimeTtsMode == RealtimeTtsMode.Cloud,
                    onCheckedChange = {
                        onRealtimeTtsModeChange(if (it) RealtimeTtsMode.Cloud else RealtimeTtsMode.Local)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "唤醒词：${if (state.realtimeWakeWordRequired) "豆包" else "关闭"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.realtimeWakeWordRequired) {
                            "没有“豆包”前缀的语音不会发送给方舟"
                        } else {
                            "检测到人声后直接发送给方舟"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = state.realtimeWakeWordRequired,
                    onCheckedChange = onWakeWordRequiredChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceActionButton(
                    text = if (state.voiceControlEnabled) "关闭实时" else "开启实时",
                    icon = Icons.Outlined.Mic,
                    color = color,
                    enabled = true,
                    onClick = onToggleRealtimeVoice,
                    modifier = Modifier.weight(1f),
                )
                VoiceActionButton(
                    text = "按住说话",
                    icon = Icons.Outlined.Mic,
                    color = Color(0xFF1565C0),
                    enabled = true,
                    onClick = onPushToTalkStart,
                    modifier = Modifier.weight(1f),
                )
                VoiceActionButton(
                    text = "结束",
                    icon = Icons.Outlined.CheckCircle,
                    color = Color(0xFFE65100),
                    enabled = state.voiceControlEnabled,
                    onClick = onPushToTalkStop,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceActionButton(
                    text = "状态",
                    icon = Icons.Outlined.Info,
                    color = Color(0xFF1565C0),
                    enabled = true,
                    onClick = {
                        onRealtimeControlEvent(
                            """{"type":"control_event","action":"explain_status","reason":"手动模拟状态说明"}""",
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                VoiceActionButton(
                    text = "停止",
                    icon = Icons.Outlined.Security,
                    color = Color(0xFFC62828),
                    enabled = true,
                    onClick = {
                        onRealtimeControlEvent(
                            """{"type":"control_event","action":"stop","reason":"手动模拟停止"}""",
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                VoiceActionButton(
                    text = "低速",
                    icon = Icons.Outlined.Terminal,
                    color = Color(0xFF2E7D32),
                    enabled = true,
                    onClick = {
                        onRealtimeControlEvent(
                            """{"type":"control_event","action":"set_limited_power","left_percent":12,"right_percent":12,"duration_ms":1500,"reason":"手动模拟低速推进"}""",
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            VoiceSampleField("实时转写", state.realtimeVoiceTranscript.ifBlank { "等待云端转写" })
            VoiceSampleField("实时回复", state.realtimeVoiceReply.ifBlank { "等待云端音频回复" })
            VoiceSampleField("控制事件", state.realtimeVoiceControlEvent)
        }
    }
}

@Composable
private fun VoiceTranscriptionCard(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceSectionHeader(
                title = "转写文本 / 调试输入",
                icon = Icons.Outlined.Terminal,
                color = Color(0xFF546E7A),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                minLines = 3,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun VoiceSamplingEntryButton(
    state: ControlUiState,
    onClick: () -> Unit,
) {
    val color = if (state.voiceSamplingEnabled) Color(0xFF2E7D32) else Color(0xFFE65100)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                    contentDescription = "采样模式",
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "采样模式",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (state.voiceSamplingEnabled) "采样中，点击查看" else "点击弹出采样窗口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "打开",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun VoiceSamplingDialog(
    state: ControlUiState,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onVoiceInputChange: (String) -> Unit,
    onSaveCorrect: () -> Unit,
    onSaveFailure: () -> Unit,
    onDiscard: () -> Unit,
    onNextTarget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            VoiceSectionHeader(
                title = "采样模式",
                icon = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                color = if (state.voiceSamplingEnabled) Color(0xFF2E7D32) else Color(0xFF546E7A),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        VoiceSectionHeader(
                            title = "采样模式",
                            icon = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                            color = if (state.voiceSamplingEnabled) Color(0xFF2E7D32) else Color(0xFF546E7A),
                        )
                        Text(
                            if (state.voiceSamplingEnabled) "只录音，不发送控制命令" else "关闭时按正常语音控制执行",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.voiceSamplingEnabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                OutlinedTextField(
                    value = state.voiceInputText,
                    onValueChange = onVoiceInputChange,
                label = { Text("转写文本 / 调试输入") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    minLines = 3,
                    maxLines = 5,
                )

                Text(
                    "目标：${state.voiceSampleTargetText}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "标签：${state.voiceSampleTargetLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.voiceSampleExpectedCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )

                VoiceSampleField("本次转写", state.voiceSamplePendingText.ifBlank { "无待保存样本" })
                VoiceSampleField("解析结果", state.voiceSamplePendingCommand)
                VoiceSampleField("保存状态", "${state.voiceSampleLastMessage}；已保存 ${state.voiceSampleSavedCount} 条")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VoiceActionButton(
                        onClick = onSaveCorrect,
                        enabled = state.voiceSamplingEnabled && state.voiceSamplePendingText.isNotBlank(),
                        text = "正确",
                        icon = Icons.Outlined.CheckCircle,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                    )
                    VoiceActionButton(
                        onClick = onSaveFailure,
                        enabled = state.voiceSamplingEnabled && state.voiceSamplePendingText.isNotBlank(),
                        text = "失败",
                        icon = Icons.Outlined.ErrorOutline,
                        color = Color(0xFFC62828),
                        modifier = Modifier.weight(1f),
                    )
                    VoiceActionButton(
                        onClick = onDiscard,
                        enabled = state.voiceSamplingEnabled && state.voiceSamplePendingText.isNotBlank(),
                        text = "重录",
                        icon = Icons.Outlined.Replay,
                        color = Color(0xFFE65100),
                        modifier = Modifier.weight(1f),
                    )
                }
                VoiceActionButton(
                    onClick = onNextTarget,
                    enabled = state.voiceSamplingEnabled,
                    text = "下一条",
                    icon = Icons.Outlined.SkipNext,
                    color = Color(0xFF1565C0),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.voiceSampleDirectory.isNotBlank()) {
                    Text(
                        state.voiceSampleDirectory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("结束采样")
            }
        },
    )
}

@Composable
private fun VoiceSampleField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VoiceCommandListCard() {
    val commands = remember {
        listOf(
            VoiceCommandItem(
                name = "开始声控",
                examples = "开始声控、开启声控、恢复声控",
                command = "本地状态：恢复执行语音控制命令",
            ),
            VoiceCommandItem(
                name = "停止声控",
                examples = "停止声控、关闭声控、暂停声控",
                command = "本地状态：关闭语音控制，不改变推进或锁航",
            ),
            VoiceCommandItem(
                name = "停止 / 空挡",
                examples = "停止、停下、停、空档、空挡",
                command = "SRC=VOICE;ARM=1;L=0;R=0",
            ),
            VoiceCommandItem(
                name = "锁定 / 急停",
                examples = "急停、锁定、锁主控、刹车、制动",
                command = "SRC=VOICE;ARM=0;L=0;R=0",
            ),
            VoiceCommandItem(
                name = "空档",
                examples = "空档、空挡、回空档、回空挡",
                command = "SRC=VOICE;ARM=1;L=0;R=0",
            ),
            VoiceCommandItem(
                name = "前进一档",
                examples = "前进、前进一档、慢速前进、低速前进、向前、往前",
                command = "SRC=VOICE;ARM=1;L=20;R=20",
            ),
            VoiceCommandItem(
                name = "前进二档",
                examples = "前进二档、前进2档、向前二档、往前2档",
                command = "SRC=VOICE;ARM=1;L=30;R=30",
            ),
            VoiceCommandItem(
                name = "前进三档",
                examples = "前进三档、前进3档、向前三档、往前3档",
                command = "SRC=VOICE;ARM=1;L=60;R=60",
            ),
            VoiceCommandItem(
                name = "前进四档",
                examples = "前进四档、前进4档、向前四档、往前4档",
                command = "设置页四档目标，默认 80%，继续受声控功率限制",
            ),
            VoiceCommandItem(
                name = "后退一档",
                examples = "后退、倒车、向后、往后、慢速后退",
                command = "SRC=VOICE;ARM=1;L=-15;R=-15",
            ),
            VoiceCommandItem(
                name = "微调加速",
                examples = "快点、快一点、快一点点、再快一点、加速、速度快点",
                command = "当前左右目标按设置页微调步进加速，默认 3%",
            ),
            VoiceCommandItem(
                name = "微调减速",
                examples = "慢点、慢一点、慢一点点、再慢一点、减速、速度慢点",
                command = "当前左右目标按设置页微调步进减速，向 0% 收敛",
            ),
            VoiceCommandItem(
                name = "左转",
                examples = "左转、左拐、往左、向左",
                command = "SRC=VOICE;ARM=1;L=10;R=25",
            ),
            VoiceCommandItem(
                name = "右转",
                examples = "右转、右拐、往右、向右",
                command = "SRC=VOICE;ARM=1;L=25;R=10",
            ),
            VoiceCommandItem(
                name = "角度转向",
                examples = "左转30度、右转十五度、往左90度",
                command = "App 用手机指南针本地计算目标航向，发送普通 L/R 心跳",
            ),
            VoiceCommandItem(
                name = "保持航向",
                examples = "保持航向、锁定当前航向、方向锁定、开启航向锁定",
                command = "App 记录手机当前航向为目标，发送普通 L/R 心跳",
            ),
            VoiceCommandItem(
                name = "取消航向锁定",
                examples = "取消保持航向、退出方向锁定、取消航向锁定",
                command = "退出航向锁定，回到手动油门/空挡",
            ),
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceSectionHeader(
                title = "可用语音指令",
                icon = Icons.Outlined.Mic,
                color = Color(0xFF1565C0),
            )
            commands.forEach { item ->
                VoiceCommandRow(item)
            }
        }
    }
}

@Composable
private fun VoiceCommandRow(item: VoiceCommandItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            item.examples,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            item.command,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VoiceInfoCard(
    title: String,
    value: String,
    emphasized: Boolean = false,
    icon: ImageVector,
    color: Color,
    maxLines: Int = Int.MAX_VALUE,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VoiceSectionHeader(
                title = title,
                icon = icon,
                color = color,
            )
            Text(
                value,
                style = if (emphasized) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoiceSectionHeader(
    title: String,
    icon: ImageVector,
    color: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VoiceActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actualColor = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = actualColor.copy(alpha = if (enabled) 0.14f else 0.08f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = actualColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text,
                modifier = Modifier.padding(start = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = actualColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class VoiceCommandItem(
    val name: String,
    val examples: String,
    val command: String,
)

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
    }
}
