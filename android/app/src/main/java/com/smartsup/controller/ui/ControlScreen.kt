package com.smartsup.controller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.VoiceAsrState
import kotlin.math.abs

@Composable
fun ControlScreen(
    state: ControlUiState,
    maxThrottlePercent: Int,
    gearPercents: Map<ThrottleGear, Int>,
    modifier: Modifier = Modifier,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onLeftThrottleChange: (Int) -> Unit,
    onRightThrottleChange: (Int) -> Unit,
    onLeftThrottleRelease: () -> Unit,
    onRightThrottleRelease: () -> Unit,
    onGearSelected: (ThrottleGear) -> Unit,
    onEnableHeadingLock: () -> Unit,
    onDisableHeadingLock: () -> Unit,
    onToggleVoiceControl: () -> Unit,
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
            onToggleHeadingLock = {
                if (state.headingLockEnabled) {
                    onDisableHeadingLock()
                } else {
                    onEnableHeadingLock()
                }
            },
            onToggleVoiceControl = onToggleVoiceControl,
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
                    .width(88.dp)
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
                    .width(88.dp)
                    .fillMaxHeight(),
            )
        }

        EmergencyStopButton(
            onClick = onEmergencyStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        )
    }
}

@Composable
private fun CompactStatusRow(
    state: ControlUiState,
    maxThrottlePercent: Int,
    onToggleHeadingLock: () -> Unit,
    onToggleVoiceControl: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconStatusChip(
            icon = Icons.Outlined.Bluetooth,
            contentDescription = "蓝牙连接状态",
            value = connectionText(state.connectionState),
            color = when (state.connectionState) {
                ConnectionState.Connected -> Color(0xFF1976D2)
                ConnectionState.Connecting -> Color(0xFF00897B)
                ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        IconStatusChip(
            icon = Icons.Outlined.Mic,
            contentDescription = "声控状态",
            value = state.voiceStatusText(),
            color = state.voiceStatusColor(),
            modifier = Modifier.weight(1f),
            enabled = true,
            onClick = onToggleVoiceControl,
        )
        IconStatusChip(
            icon = Icons.Outlined.Speed,
            contentDescription = "油门限幅",
            value = "$maxThrottlePercent%",
            color = Color(0xFFE65100),
            modifier = Modifier.weight(1f),
        )
        IconStatusChip(
            icon = Icons.Outlined.Explore,
            contentDescription = "航向锁定状态，点击切换",
            value = if (state.headingLockEnabled) "锁航" else "未锁",
            color = if (state.headingLockEnabled) Color(0xFF1565C0) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            enabled = true,
            onClick = onToggleHeadingLock,
        )
    }
}

@Composable
private fun IconStatusChip(
    icon: ImageVector,
    contentDescription: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val containerColor = color.copy(alpha = if (enabled) 0.16f else 0.1f)
    val borderColor = color.copy(alpha = if (enabled) 0.45f else 0.22f)
    Surface(
        onClick = onClick ?: {},
        enabled = enabled,
        shape = shape,
        color = containerColor,
        modifier = modifier
            .height(50.dp)
            .border(1.dp, borderColor, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            ThrottleValueBadge(value = value, maxThrottlePercent = maxThrottlePercent, enabled = enabled)
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
        }
    }
}

@Composable
private fun ThrottleValueBadge(value: Int, maxThrottlePercent: Int, enabled: Boolean) {
    val activeColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        value != 0 -> throttleHeatColor(value, maxThrottlePercent)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = activeColor.copy(alpha = if (enabled) 0.14f else 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            value.signedPercentText(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = activeColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val forwardColor = if (enabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val reverseColor = if (enabled) Color(0xFF1565C0) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val railColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.16f else 0.1f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.42f else 0.18f)
    val centerLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.28f)
    val neutralBarColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val activeThrottleColor = throttleHeatColor(value, maxThrottlePercent)
    val thumbOuterColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        value != 0 -> activeThrottleColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val thumbInnerColor = MaterialTheme.colorScheme.surface

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
        val trackWidth = 30.dp.toPx()
        val innerTrackWidth = 16.dp.toPx()
        val thumbRadius = 15.dp.toPx()
        val thumbInnerRadius = 8.dp.toPx()
        val horizontalCenter = size.width / 2f
        val top = thumbRadius + 3.dp.toPx()
        val bottom = size.height - thumbRadius - 3.dp.toPx()
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
        val segmentCount = 8
        val segmentGap = 4.dp.toPx()
        val segmentHeight = (trackHeight - segmentGap * (segmentCount - 1)) / segmentCount

        drawRoundRect(
            color = railColor,
            topLeft = Offset(horizontalCenter - trackWidth / 2f, top),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )

        for (index in 0 until segmentCount) {
            val y = top + index * (segmentHeight + segmentGap)
            val segmentCenter = y + segmentHeight / 2f
            val inForwardHalf = segmentCenter < centerY
            val color = if (inForwardHalf) forwardColor else reverseColor
            drawRoundRect(
                color = color.copy(alpha = if (enabled) 0.14f else 0.06f),
                topLeft = Offset(horizontalCenter - innerTrackWidth / 2f, y),
                size = Size(innerTrackWidth, segmentHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
        }

        drawRoundRect(
            color = activeThrottleColor,
            topLeft = Offset(horizontalCenter - trackWidth / 2f, activeTop),
            size = Size(trackWidth, activeHeight),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )

        for (step in -2..2) {
            if (step == 0) {
                continue
            }
            val tickY = centerY - (trackHeight / 2f) * (step / 2f)
            val tickWidth = if (abs(step) == 2) 18.dp.toPx() else 12.dp.toPx()
            drawLine(
                color = tickColor,
                start = Offset(horizontalCenter - trackWidth / 2f - tickWidth, tickY),
                end = Offset(horizontalCenter - trackWidth / 2f - 5.dp.toPx(), tickY),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = tickColor,
                start = Offset(horizontalCenter + trackWidth / 2f + 5.dp.toPx(), tickY),
                end = Offset(horizontalCenter + trackWidth / 2f + tickWidth, tickY),
                strokeWidth = 2.dp.toPx(),
            )
        }

        drawLine(
            color = centerLineColor,
            start = Offset(horizontalCenter - 28.dp.toPx(), centerY),
            end = Offset(horizontalCenter + 28.dp.toPx(), centerY),
            strokeWidth = 3.dp.toPx(),
        )
        drawRoundRect(
            color = neutralBarColor,
            topLeft = Offset(horizontalCenter - 14.dp.toPx(), centerY - 3.dp.toPx()),
            size = Size(28.dp.toPx(), 6.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        drawCircle(
            color = thumbOuterColor.copy(alpha = if (enabled) 0.18f else 0.08f),
            radius = thumbRadius + 7.dp.toPx(),
            center = Offset(horizontalCenter, thumbY),
        )
        drawCircle(
            color = thumbOuterColor,
            radius = thumbRadius,
            center = Offset(horizontalCenter, thumbY),
        )
        drawCircle(
            color = thumbInnerColor,
            radius = thumbInnerRadius,
            center = Offset(horizontalCenter, thumbY),
        )
        drawCircle(
            color = thumbOuterColor.copy(alpha = 0.72f),
            radius = 3.dp.toPx(),
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
        ArmToggleButton(
            state = state,
            onArm = onArm,
            onDisarm = onDisarm,
        )

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
                CompactInfoRow("左电流", state.telemetry.leftCurrent.format("A"))
                CompactInfoRow("右电流", state.telemetry.rightCurrent.format("A"))
                CompactInfoRow("当前航向", state.headingText())
                CompactInfoRow("目标航向", state.targetHeadingText())
                CompactInfoRow("IMU", state.telemetry.imuAvailable.imuStatusText())
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CompactInfoRow("GPS 模块", state.gpsModuleText())
                CompactInfoRow("GPS 定位", state.gpsFixText())
                CompactInfoRow("卫星数量", state.gpsSatelliteText())
                CompactInfoRow("纬度", state.gpsCoordinateText("GPS_LAT"))
                CompactInfoRow("经度", state.gpsCoordinateText("GPS_LON"))
                CompactInfoRow("PPS", state.gpsPpsText())
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
                CompactInfoRow("锁状态", state.statusArmedText())
                CompactInfoRow("左目标", state.statusPercentText("L"))
                CompactInfoRow("右目标", state.statusPercentText("R"))
                CompactInfoRow("左 PWM", state.statusUnitText("LPWM", "us"))
                CompactInfoRow("右 PWM", state.statusUnitText("RPWM", "us"))
                CompactInfoRow("命令源", state.statusSourceText())
                CompactInfoRow("模式", state.statusModeText())
                CompactInfoRow("当前航向", state.headingText())
                CompactInfoRow("目标航向", state.targetHeadingText())
                CompactInfoRow("转向", state.statusTurnText())
                CompactInfoRow("故障", state.statusValue("FAULT"))
                CompactInfoRow("编号", state.statusValue("ID"))
                CompactInfoRow("设备", state.statusValue("BT"))
            }
        }
    }
}

@Composable
private fun ArmToggleButton(
    state: ControlUiState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val enabled = state.connectionState == ConnectionState.Connected
    val color = if (state.armed) Color(0xFF2E7D32) else Color(0xFFC62828)
    val label = if (state.armed) "锁定推进" else "解锁推进"
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (enabled) 0.14f else 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state.armed) "推进器已解锁" else "推进器已经锁定",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.connectionState == ConnectionState.Connected) "点击右侧图标切换" else "连接 ESP32 后可解锁",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(
                onClick = { if (state.armed) onDisarm() else onArm() },
                enabled = enabled,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = color.copy(alpha = 0.2f),
                    contentColor = color,
                ),
            ) {
                Icon(
                    imageVector = if (state.armed) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    contentDescription = label,
                )
            }
        }
    }
}

@Composable
private fun EmergencyStopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = Color(0xFFC62828)
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = color.copy(alpha = 0.14f),
        modifier = modifier.border(1.dp, color.copy(alpha = 0.38f), shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "急停 / 回空挡",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text(
                    "立即撤销推进输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ReportProblem,
                contentDescription = "急停并回空挡",
                tint = color,
                modifier = Modifier.size(24.dp),
            )
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

private fun Int.signedPercentText(): String {
    return if (this > 0) "+$this%" else "$this%"
}

private fun throttleHeatColor(value: Int, maxThrottlePercent: Int): Color {
    if (value == 0 || maxThrottlePercent <= 0) {
        return Color(0xFF6B7280)
    }
    val baseColor = if (value > 0) Color(0xFF2E7D32) else Color(0xFF1565C0)
    val hotColor = Color(0xFFD32F2F)
    val ratio = (abs(value).toFloat() / maxThrottlePercent.toFloat()).coerceIn(0f, 1f)
    val heat = ((ratio - 0.2f) / 0.65f).coerceIn(0f, 1f)
    return blendColor(baseColor, hotColor, heat)
}

private fun blendColor(start: Color, end: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t,
    )
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

private fun Float?.formatDegrees(): String {
    return this?.let { "%.1f°".format(it) } ?: "--"
}

private fun Boolean?.imuStatusText(): String {
    return when (this) {
        true -> "在线"
        false -> "不可用"
        null -> "--"
    }
}

private fun ControlUiState.headingText(): String {
    return if (telemetry.imuAvailable == false) "--" else telemetry.headingDegrees.formatDegrees()
}

private fun ControlUiState.targetHeadingText(): String {
    return if (telemetry.imuAvailable == false) "--" else telemetry.targetHeadingDegrees.formatDegrees()
}

private fun ControlUiState.statusValue(key: String): String {
    return telemetry.statusFields[key]?.ifBlank { null } ?: "--"
}

private fun ControlUiState.statusPercentText(key: String): String {
    return telemetry.statusFields[key]?.toIntOrNull()?.signedPercentText() ?: "--"
}

private fun ControlUiState.statusUnitText(key: String, unit: String): String {
    return telemetry.statusFields[key]?.let { "$it $unit" } ?: "--"
}

private fun ControlUiState.statusArmedText(): String {
    return when (telemetry.statusFields["ARMED"]) {
        "1" -> "已解锁"
        "0" -> "锁定"
        else -> "--"
    }
}

private fun ControlUiState.statusSourceText(): String {
    return when (telemetry.statusFields["CMD_SRC"]) {
        "APP" -> "App"
        "VOICE" -> "语音"
        else -> statusValue("CMD_SRC")
    }
}

private fun ControlUiState.statusModeText(): String {
    return when (telemetry.statusFields["MODE"]) {
        "THROTTLE" -> "油门"
        "TURN" -> "角度转向"
        "HEADING_LOCK" -> "航向锁定"
        else -> statusValue("MODE")
    }
}

private fun ControlUiState.statusHeadingLockText(): String {
    val lock = telemetry.statusFields["HLOCK"] ?: return if (headingLockEnabled) "等待状态" else "未开启"
    val lockText = when (lock) {
        "ACTIVE" -> "执行中"
        else -> lock
    }
    val requestId = telemetry.statusFields["HID"]?.let { "#$it" }
    val error = telemetry.statusFields["HERR"]?.let { "误差 ${it}°" }
    return listOfNotNull(lockText, requestId, error).joinToString(" ")
}

private fun ControlUiState.statusTurnText(): String {
    val turn = telemetry.statusFields["TURN"] ?: return "--"
    val turnText = when (turn) {
        "ACTIVE" -> "执行中"
        "DONE" -> "完成"
        "TIMEOUT" -> "超时"
        "START" -> "开始"
        else -> turn
    }
    val requestId = telemetry.statusFields["TID"]?.let { "#$it" }
    val error = telemetry.statusFields["TERR"]?.let { "误差 ${it}°" }
    return listOfNotNull(turnText, requestId, error).joinToString(" ")
}

private fun ControlUiState.gpsModuleText(): String {
    val module = when (telemetry.statusFields["GPS"]) {
        "1" -> "在线"
        "0" -> "无数据"
        else -> "--"
    }
    val baud = telemetry.statusFields["GPS_BAUD"]?.takeIf { it.isNotBlank() }
    return if (baud == null || module == "--") module else "$module $baud"
}

private fun ControlUiState.gpsFixText(): String {
    return when (telemetry.statusFields["GPS_FIX"]) {
        "1" -> "已定位"
        "0" -> "未定位"
        else -> "--"
    }
}

private fun ControlUiState.gpsSatelliteText(): String {
    return telemetry.statusFields["GPS_SAT"]?.toIntOrNull()?.let { "$it 颗" } ?: "--"
}

private fun ControlUiState.gpsCoordinateText(key: String): String {
    val coordinate = telemetry.statusFields[key]?.toDoubleOrNull() ?: return "--"
    return "%.6f°".format(coordinate)
}

private fun ControlUiState.gpsPpsText(): String {
    return when (telemetry.statusFields["PPS"]) {
        "1" -> "有脉冲"
        "0" -> "无脉冲"
        else -> "--"
    }
}

private fun ControlUiState.voiceStatusText(): String {
    return when {
        voiceSamplingEnabled -> "采样"
        !voiceControlEnabled -> "声控停"
        voiceAsrState == VoiceAsrState.Ready -> "声控开"
        else -> "开启中"
    }
}

@Composable
private fun ControlUiState.voiceStatusColor(): Color {
    return when {
        voiceSamplingEnabled -> Color(0xFFE65100)
        !voiceControlEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
        voiceAsrState == VoiceAsrState.Ready -> Color(0xFF2E7D32)
        else -> Color(0xFFE65100)
    }
}

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
    }
}
