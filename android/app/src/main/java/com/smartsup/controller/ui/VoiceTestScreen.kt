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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState

@Composable
fun VoiceTestScreen(
    state: ControlUiState,
    modifier: Modifier = Modifier,
    onVoiceInputChange: (String) -> Unit,
    onVoiceSamplingEnabledChange: (Boolean) -> Unit,
    onNextVoiceSampleTarget: () -> Unit,
    onSaveVoiceSample: (Boolean) -> Unit,
    onDiscardVoiceSample: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VoiceInfoCard(
            title = "ASR 运行状态",
            value = state.voiceAsrStatus,
            emphasized = true,
            icon = Icons.Outlined.Mic,
            color = Color(0xFF1565C0),
        )

        VoiceSamplingCard(
            state = state,
            onEnabledChange = onVoiceSamplingEnabledChange,
            onSaveCorrect = { onSaveVoiceSample(true) },
            onSaveFailure = { onSaveVoiceSample(false) },
            onDiscard = onDiscardVoiceSample,
            onNextTarget = onNextVoiceSampleTarget,
        )

        VoiceCommandListCard()

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceSectionHeader(
                    title = "ASR 转写文本",
                    icon = Icons.Outlined.Terminal,
                    color = Color(0xFF546E7A),
                )
                OutlinedTextField(
                    value = state.voiceInputText,
                    onValueChange = onVoiceInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp),
                    minLines = 4,
                    maxLines = 8,
                )
            }
        }

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
}

@Composable
private fun VoiceSamplingCard(
    state: ControlUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSaveCorrect: () -> Unit,
    onSaveFailure: () -> Unit,
    onDiscard: () -> Unit,
    onNextTarget: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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

            VoiceSampleField("本次 ASR", state.voiceSamplePendingText.ifBlank { "无待保存样本" })
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
    }
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
                command = "SRC=VOICE;ARM=0;L=0;R=0，然后忽略后续命令",
            ),
            VoiceCommandItem(
                name = "停止 / 锁定",
                examples = "停止、停下、急停、锁定、刹车",
                command = "SRC=VOICE;ARM=0;L=0;R=0",
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
                name = "后退一档",
                examples = "后退、倒车、向后、往后、慢速后退",
                command = "SRC=VOICE;ARM=1;L=-15;R=-15",
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
                command = "SRC=VOICE;ARM=1;MODE=TURN;DIR=LEFT;ANGLE=30;TID=...",
            ),
            VoiceCommandItem(
                name = "保持航向",
                examples = "保持航向、方向锁定、开启航向锁定",
                command = "SRC=VOICE;ARM=1;MODE=HEADING_LOCK;HLOCK=1;BASE=...;HID=...",
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
