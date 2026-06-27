package com.smartsup.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ControlLogEntry
import com.smartsup.controller.model.ControlLogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ControlLogScreen(
    entries: List<ControlLogEntry>,
    logFilePath: String,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "控制日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "最近 ${entries.size} 条，文件 ${logFilePath.substringAfterLast('/').ifBlank { "--" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "清空控制日志",
                )
                Text("清除所有日志")
            }
        }

        if (entries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "暂无日志。连接主控后，解锁、锁航、故障、退出原因会显示在这里。",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                ControlLogRow(entry = entry)
            }
        }
    }
}

@Composable
private fun ControlLogRow(entry: ControlLogEntry) {
    val color = entry.level.logColor()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.09f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = entry.level.logIcon(),
                contentDescription = entry.level.logLabel(),
                tint = color,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        timeFormatter.format(Date(entry.timestampMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                entry.rawLine?.takeIf { it.isNotBlank() }?.let { raw ->
                    Text(
                        raw,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun ControlLogLevel.logColor(): Color {
    return when (this) {
        ControlLogLevel.Info -> Color(0xFF1565C0)
        ControlLogLevel.Warning -> Color(0xFFE65100)
        ControlLogLevel.Error -> Color(0xFFC62828)
    }
}

private fun ControlLogLevel.logIcon(): ImageVector {
    return when (this) {
        ControlLogLevel.Info -> Icons.Outlined.Info
        ControlLogLevel.Warning -> Icons.Outlined.WarningAmber
        ControlLogLevel.Error -> Icons.Outlined.ErrorOutline
    }
}

private fun ControlLogLevel.logLabel(): String {
    return when (this) {
        ControlLogLevel.Info -> "信息"
        ControlLogLevel.Warning -> "警告"
        ControlLogLevel.Error -> "错误"
    }
}
