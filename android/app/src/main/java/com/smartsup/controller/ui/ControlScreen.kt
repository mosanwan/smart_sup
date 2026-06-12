package com.smartsup.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.ThrottleGear
import kotlin.math.abs

@Composable
fun ControlScreen(
    state: ControlUiState,
    maxThrottlePercent: Int,
    gearPercents: Map<ThrottleGear, Int>,
    rampLimitEnabled: Boolean,
    modifier: Modifier = Modifier,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onLeftThrottleChange: (Int) -> Unit,
    onRightThrottleChange: (Int) -> Unit,
    onLeftThrottleRelease: () -> Unit,
    onRightThrottleRelease: () -> Unit,
    onGearSelected: (ThrottleGear) -> Unit,
    onEmergencyStop: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactStatusRow(
            state = state,
            maxThrottlePercent = maxThrottlePercent,
            rampLimitEnabled = rampLimitEnabled,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VerticalThrottle(
                label = "左 ESC",
                value = state.leftThrottlePercent,
                maxThrottlePercent = maxThrottlePercent,
                enabled = state.canSendThrottle,
                onChange = onLeftThrottleChange,
                onRelease = onLeftThrottleRelease,
                modifier = Modifier
                    .width(76.dp)
                    .fillMaxHeight(),
            )

            CenterControlPanel(
                state = state,
                gearPercents = gearPercents,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onArm = onArm,
                onDisarm = onDisarm,
                onGearSelected = onGearSelected,
            )

            VerticalThrottle(
                label = "右 ESC",
                value = state.rightThrottlePercent,
                maxThrottlePercent = maxThrottlePercent,
                enabled = state.canSendThrottle,
                onChange = onRightThrottleChange,
                onRelease = onRightThrottleRelease,
                modifier = Modifier
                    .width(76.dp)
                    .fillMaxHeight(),
            )
        }

        Button(
            onClick = onEmergencyStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("急停 / 回空挡")
        }
    }
}

@Composable
private fun CompactStatusRow(
    state: ControlUiState,
    maxThrottlePercent: Int,
    rampLimitEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusChip("连接", connectionText(state.connectionState), Modifier.weight(1f))
        StatusChip("锁", if (state.armed) "已解锁" else "锁定", Modifier.weight(1f))
        StatusChip("限幅", "$maxThrottlePercent%", Modifier.weight(1f))
        StatusChip("斜率", if (rampLimitEnabled) "开" else "关", Modifier.weight(1f))
    }
}

@Composable
private fun StatusChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VerticalThrottle(
    label: String,
    value: Int,
    maxThrottlePercent: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            Text(
                value.signedPercentText(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                VerticalThrottleTrack(
                    value = value,
                    maxThrottlePercent = maxThrottlePercent,
                    enabled = enabled,
                    onChange = onChange,
                    onRelease = onRelease,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                "-$maxThrottlePercent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerticalThrottleTrack(
    value: Int,
    maxThrottlePercent: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    }
    val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val thumbColor = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.55f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)

    fun yToPercent(y: Float, height: Float): Int {
        if (height <= 0f || maxThrottlePercent <= 0) {
            return 0
        }
        val center = height / 2f
        val halfHeight = center.coerceAtLeast(1f)
        val normalized = ((center - y) / halfHeight).coerceIn(-1f, 1f)
        return (normalized * maxThrottlePercent).toInt().coerceIn(-maxThrottlePercent, maxThrottlePercent)
    }

    Canvas(
        modifier = modifier
            .pointerInput(enabled, maxThrottlePercent) {
                if (!enabled) {
                    return@pointerInput
                }
                detectDragGestures(
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                ) { change, _ ->
                    onChange(yToPercent(change.position.y, size.height.toFloat()))
                    change.consume()
                }
            },
    ) {
        val trackWidth = 10.dp.toPx()
        val thumbRadius = 12.dp.toPx()
        val horizontalCenter = size.width / 2f
        val top = thumbRadius
        val bottom = size.height - thumbRadius
        val trackHeight = (bottom - top).coerceAtLeast(1f)
        val centerY = top + trackHeight / 2f
        val normalized = if (maxThrottlePercent == 0) {
            0f
        } else {
            (value.toFloat() / maxThrottlePercent.toFloat()).coerceIn(-1f, 1f)
        }
        val thumbY = centerY - (trackHeight / 2f) * normalized
        val activeTop = minOf(centerY, thumbY)
        val activeHeight = abs(centerY - thumbY).coerceAtLeast(1f)

        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(horizontalCenter - trackWidth / 2f, top),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f),
        )
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(horizontalCenter - trackWidth / 2f, activeTop),
            size = Size(trackWidth, activeHeight),
            cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f),
        )
        drawLine(
            color = zeroLineColor,
            start = Offset(horizontalCenter - 20.dp.toPx(), centerY),
            end = Offset(horizontalCenter + 20.dp.toPx(), centerY),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(
            color = thumbColor,
            radius = thumbRadius,
            center = Offset(horizontalCenter, thumbY),
        )
    }
}

@Composable
private fun CenterControlPanel(
    state: ControlUiState,
    gearPercents: Map<ThrottleGear, Int>,
    modifier: Modifier = Modifier,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onGearSelected: (ThrottleGear) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onArm,
                enabled = state.connectionState == ConnectionState.Connected && !state.armed,
                modifier = Modifier.weight(1f),
            ) {
                Text("解锁")
            }
            OutlinedButton(
                onClick = onDisarm,
                enabled = state.armed,
                modifier = Modifier.weight(1f),
            ) {
                Text("锁定")
            }
        }

        GearSelector(
            selectedGear = state.selectedGear,
            gearPercents = gearPercents,
            onGearSelected = onGearSelected,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CompactInfoRow("电压", state.telemetry.batteryVoltage.format("V"))
                CompactInfoRow("左电流", state.telemetry.leftCurrent.format("A"))
                CompactInfoRow("右电流", state.telemetry.rightCurrent.format("A"))
                CompactInfoRow("温度", state.telemetry.escTemperature.format("C"))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                CompactInfoRow("发送", state.telemetry.lastSentCommand)
                MultiLineInfo("回包", state.telemetry.lastReceivedStatus)
            }
        }
    }
}

@Composable
private fun GearSelector(
    selectedGear: ThrottleGear,
    gearPercents: Map<ThrottleGear, Int>,
    onGearSelected: (ThrottleGear) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        GearStrip(
            selectedGear = selectedGear,
            gearPercents = gearPercents,
            onGearSelected = onGearSelected,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GearStrip(
    selectedGear: ThrottleGear,
    gearPercents: Map<ThrottleGear, Int>,
    onGearSelected: (ThrottleGear) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gears = ThrottleGear.entries
    val indicatorColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                if (size.width <= 0 || gears.isEmpty()) {
                    return@detectTapGestures
                }
                val gearIndex = ((offset.x / size.width) * gears.size)
                    .toInt()
                    .coerceIn(0, gears.lastIndex)
                onGearSelected(gears[gearIndex])
            }
        },
    ) {
        val gap = 3.dp.toPx()
        val triangleHeight = 7.dp.toPx()
        val triangleWidth = 10.dp.toPx()
        val barTop = triangleHeight + 3.dp.toPx()
        val barHeight = size.height - barTop
        val barWidth = (size.width - gap * (gears.size - 1)) / gears.size

        gears.forEachIndexed { index, gear ->
            val percent = gearPercents[gear] ?: gear.defaultThrottlePercent
            val speedAlpha = 0.22f + 0.58f * (abs(percent) / 100f)
            val selected = gear == selectedGear
            val color = if (selected) {
                gear.color()
            } else {
                gear.color().copy(alpha = speedAlpha.coerceIn(0.22f, 0.8f))
            }
            val x = index * (barWidth + gap)

            drawRoundRect(
                color = color,
                topLeft = Offset(x, barTop),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )

            if (selected) {
                val centerX = x + barWidth / 2f
                val path = Path().apply {
                    moveTo(centerX, 0f)
                    lineTo(centerX - triangleWidth / 2f, triangleHeight)
                    lineTo(centerX + triangleWidth / 2f, triangleHeight)
                    close()
                }
                drawPath(path = path, color = indicatorColor)
            }
        }
    }
}

@Composable
private fun CompactInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MultiLineInfo(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Int.signedPercentText(): String {
    return if (this > 0) "+$this%" else "$this%"
}

private fun ThrottleGear.color(): Color {
    return when (this) {
        ThrottleGear.Reverse3 -> Color(0xFF08306B)
        ThrottleGear.Reverse2 -> Color(0xFF0D47A1)
        ThrottleGear.Reverse1 -> Color(0xFF1976D2)
        ThrottleGear.Neutral -> Color(0xFF546E7A)
        ThrottleGear.Forward1 -> Color(0xFF2E7D32)
        ThrottleGear.Forward2 -> Color(0xFF00897B)
        ThrottleGear.Forward3 -> Color(0xFFE65100)
        ThrottleGear.Forward4 -> Color(0xFFBF360C)
    }
}

private fun Float?.format(unit: String): String {
    return this?.let { "%.1f %s".format(it, unit) } ?: "--"
}

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
    }
}
