package com.smartsup.controller.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Remove
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.VoiceAsrState
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ControlScreen(
    state: ControlUiState,
    maxThrottlePercent: Int,
    fineTuneStepPercent: Int,
    gearPercents: Map<ThrottleGear, Int>,
    leftEscReversed: Boolean,
    rightEscReversed: Boolean,
    modifier: Modifier = Modifier,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onLeftThrottleChange: (Int) -> Unit,
    onRightThrottleChange: (Int) -> Unit,
    onLeftThrottleRelease: () -> Unit,
    onRightThrottleRelease: () -> Unit,
    onGearSelected: (ThrottleGear) -> Unit,
    onFineTuneDecrease: () -> Unit,
    onFineTuneIncrease: () -> Unit,
    onEnableHeadingLock: () -> Unit,
    onDisableHeadingLock: () -> Unit,
    onSetTargetHeading: (Float) -> Unit,
    onConnectBluetooth: () -> Unit,
    onToggleVoiceControl: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    val showingHeadingLockOutput = state.headingLockEnabled
    val leftDisplayThrottle = if (showingHeadingLockOutput) {
        state.appHeadingLeftOutputPercent
            ?: state.telemetry.leftOutputPercent?.toUserFacingThrottle(leftEscReversed)
            ?: state.leftThrottlePercent
    } else {
        state.leftThrottlePercent
    }
    val rightDisplayThrottle = if (showingHeadingLockOutput) {
        state.appHeadingRightOutputPercent
            ?: state.telemetry.rightOutputPercent?.toUserFacingThrottle(rightEscReversed)
            ?: state.rightThrottlePercent
    } else {
        state.rightThrottlePercent
    }
    val visualThrottlePercent = maxOf(
        maxThrottlePercent,
        abs(leftDisplayThrottle),
        abs(rightDisplayThrottle),
        1,
    )
    val headingCorrectionPercent = if (showingHeadingLockOutput) {
        state.appHeadingLockCorrectionPercent
    } else {
        0
    }
    val handleLeftThrottleChange: (Int) -> Unit = { outputPercent ->
        onLeftThrottleChange(outputPercent - headingCorrectionPercent)
    }
    val handleRightThrottleChange: (Int) -> Unit = { outputPercent ->
        onRightThrottleChange(outputPercent + headingCorrectionPercent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactStatusRow(
            state = state,
            maxThrottlePercent = maxThrottlePercent,
            onConnectBluetooth = onConnectBluetooth,
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
                label = "左推进器",
                value = leftDisplayThrottle,
                sentValue = state.appHeadingLeftCommandPercent.takeIf { showingHeadingLockOutput },
                inputMaxThrottlePercent = maxThrottlePercent,
                visualMaxThrottlePercent = visualThrottlePercent,
                enabled = state.canSendThrottle,
                onChange = handleLeftThrottleChange,
                onRelease = onLeftThrottleRelease,
                modifier = Modifier
                    .width(88.dp)
                    .fillMaxHeight(),
            )

            CenterControlPanel(
                state = state,
                fineTuneStepPercent = fineTuneStepPercent,
                gearPercents = gearPercents,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onArm = onArm,
                onDisarm = onDisarm,
                onGearSelected = onGearSelected,
                onFineTuneDecrease = onFineTuneDecrease,
                onFineTuneIncrease = onFineTuneIncrease,
                onEnableHeadingLock = onEnableHeadingLock,
                onDisableHeadingLock = onDisableHeadingLock,
                onSetTargetHeading = onSetTargetHeading,
            )

            VerticalThrottle(
                label = "右推进器",
                value = rightDisplayThrottle,
                sentValue = state.appHeadingRightCommandPercent.takeIf { showingHeadingLockOutput },
                inputMaxThrottlePercent = maxThrottlePercent,
                visualMaxThrottlePercent = visualThrottlePercent,
                enabled = state.canSendThrottle,
                onChange = handleRightThrottleChange,
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
    onConnectBluetooth: () -> Unit,
    onToggleHeadingLock: () -> Unit,
    onToggleVoiceControl: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconStatusChip(
            icon = Icons.Outlined.Bluetooth,
            contentDescription = if (state.connectionState == ConnectionState.Disconnected) {
                "蓝牙未连接，点击连接已保存设备"
            } else {
                "蓝牙连接状态"
            },
            value = connectionText(state.connectionState),
            color = when (state.connectionState) {
                ConnectionState.Connected -> Color(0xFF1976D2)
                ConnectionState.Connecting -> Color(0xFF00897B)
                ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
            enabled = state.connectionState == ConnectionState.Disconnected,
            onClick = onConnectBluetooth,
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
    sentValue: Int?,
    inputMaxThrottlePercent: Int,
    visualMaxThrottlePercent: Int,
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            ThrottleValueBadge(value = value, maxThrottlePercent = visualMaxThrottlePercent, enabled = enabled)
            if (sentValue != null) {
                Text(
                    "下发 ${sentValue.signedPercentText()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sentValue == value) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                VerticalThrottleTrack(
                    value = value,
                    inputMaxThrottlePercent = inputMaxThrottlePercent,
                    visualMaxThrottlePercent = visualMaxThrottlePercent,
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
    inputMaxThrottlePercent: Int,
    visualMaxThrottlePercent: Int,
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
    val activeThrottleColor = throttleHeatColor(value, visualMaxThrottlePercent)
    val thumbOuterColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        value != 0 -> activeThrottleColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val thumbInnerColor = MaterialTheme.colorScheme.surface

    fun yToPercent(y: Float, height: Float): Int {
        if (height <= 0f || inputMaxThrottlePercent <= 0) {
            return 0
        }
        val center = height / 2f
        val halfHeight = center.coerceAtLeast(1f)
        val normalized = ((center - y) / halfHeight).coerceIn(-1f, 1f)
        return (normalized * inputMaxThrottlePercent).toInt()
            .coerceIn(-inputMaxThrottlePercent, inputMaxThrottlePercent)
    }

    Canvas(
        modifier = modifier
            .pointerInput(enabled, inputMaxThrottlePercent) {
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
        val normalized = if (visualMaxThrottlePercent == 0) {
            0f
        } else {
            (value.toFloat() / visualMaxThrottlePercent.toFloat()).coerceIn(-1f, 1f)
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
    fineTuneStepPercent: Int,
    gearPercents: Map<ThrottleGear, Int>,
    modifier: Modifier = Modifier,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onGearSelected: (ThrottleGear) -> Unit,
    onFineTuneDecrease: () -> Unit,
    onFineTuneIncrease: () -> Unit,
    onEnableHeadingLock: () -> Unit,
    onDisableHeadingLock: () -> Unit,
    onSetTargetHeading: (Float) -> Unit,
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

        FineTuneControls(
            state = state,
            fineTuneStepPercent = fineTuneStepPercent,
            onDecrease = onFineTuneDecrease,
            onIncrease = onFineTuneIncrease,
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
                HeadingValueRow(
                    state = state,
                    onEnableHeadingLock = onEnableHeadingLock,
                    onDisableHeadingLock = onDisableHeadingLock,
                )
                TargetHeadingValueRow(
                    state = state,
                    onSetTargetHeading = onSetTargetHeading,
                )
                CompactInfoRow("航向误差", state.appHeadingErrorText())
                CompactInfoRow("手机指南针", state.phoneHeadingStatusText())
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
                CompactInfoRow("主控 IMU", state.ybImuModuleText())
                CompactInfoRow("IMU 航向", state.ybHeadingText())
                CompactInfoRow("横滚 / 俯仰", state.ybRollPitchText())
                CompactInfoRow("Z 角速度", state.ybGyroZText())
                CompactInfoRow("加速度", state.ybAccelText())
                CompactInfoRow("四元数", state.ybQuaternionText())
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
                CompactInfoRow("手机航向", state.headingText())
                CompactInfoRow("App 目标航向", state.targetHeadingText())
                CompactInfoRow("App 修正", state.appHeadingCorrectionText())
                CompactInfoRow("App 下发", state.appHeadingCommandText())
                CompactInfoRow("转向", state.statusTurnText())
                CompactInfoRow("故障", state.statusValue("FAULT"))
                CompactInfoRow("编号", state.statusValue("ID"))
                CompactInfoRow("设备", state.statusValue("BT"))
            }
        }
    }
}

@Composable
private fun HeadingValueRow(
    state: ControlUiState,
    onEnableHeadingLock: () -> Unit,
    onDisableHeadingLock: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val actionEnabled = state.connectionState == ConnectionState.Connected
    val color = Color(0xFFC62828)
    val actionLabel = if (state.headingLockEnabled) "取消航向锁定" else "锁定当前航向"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("手机航向", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (state.headingLockEnabled) {
                    onDisableHeadingLock()
                } else {
                    onEnableHeadingLock()
                }
            },
            enabled = actionEnabled,
            color = Color.Transparent,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.headingText(),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = actionLabel,
                    tint = if (actionEnabled || state.headingLockEnabled) {
                        color
                    } else {
                        color.copy(alpha = 0.38f)
                    },
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TargetHeadingValueRow(
    state: ControlUiState,
    onSetTargetHeading: (Float) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dialogAnchor = remember { mutableStateOf<TargetHeadingDialogAnchor?>(null) }
    val currentHeading = state.phoneHeadingDegrees
    val actionEnabled = state.connectionState == ConnectionState.Connected &&
        state.armed &&
        state.phoneHeadingAvailable &&
        currentHeading != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("目标航向", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentHeading?.let { heading ->
                    dialogAnchor.value = TargetHeadingDialogAnchor(
                        currentHeadingDegrees = heading,
                        initialTargetHeadingDegrees = state.appHeadingLockTargetDegrees,
                    )
                }
            },
            enabled = actionEnabled,
            color = Color.Transparent,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                state.targetHeadingText(),
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                color = if (actionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    val anchor = dialogAnchor.value
    if (anchor != null) {
        TargetHeadingDialog(
            currentHeadingDegrees = anchor.currentHeadingDegrees,
            initialTargetHeadingDegrees = anchor.initialTargetHeadingDegrees,
            onDismiss = { dialogAnchor.value = null },
            onConfirm = { targetHeading ->
                dialogAnchor.value = null
                onSetTargetHeading(targetHeading)
            },
        )
    }
}

private data class TargetHeadingDialogAnchor(
    val currentHeadingDegrees: Float,
    val initialTargetHeadingDegrees: Float?,
)

@Composable
private fun TargetHeadingDialog(
    currentHeadingDegrees: Float,
    initialTargetHeadingDegrees: Float?,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val initialOffset = remember(currentHeadingDegrees, initialTargetHeadingDegrees) {
        shortestHeadingOffset(
            targetDegrees = initialTargetHeadingDegrees ?: currentHeadingDegrees,
            currentDegrees = currentHeadingDegrees,
        ).coerceIn(-TARGET_HEADING_ARC_DEGREES, TARGET_HEADING_ARC_DEGREES)
    }
    val selectedOffset = remember(currentHeadingDegrees, initialTargetHeadingDegrees) {
        mutableStateOf(initialOffset)
    }
    val selectedHeading = normalizedHeadingDegrees(currentHeadingDegrees + selectedOffset.value)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "设定目标航向",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "当前 ${currentHeadingDegrees.headingDisplayText()} · 目标 ${selectedHeading.headingDisplayText()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                HeadingArcPicker(
                    selectedOffsetDegrees = selectedOffset.value,
                    onOffsetChange = { selectedOffset.value = it },
                    onCommit = { committedOffset ->
                        onConfirm(normalizedHeadingDegrees(currentHeadingDegrees + committedOffset))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp),
                )
                Text(
                    "偏移 ${selectedOffset.value.roundToInt().signedDegreesText()} · 松开确认",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun HeadingArcPicker(
    selectedOffsetDegrees: Float,
    onOffsetChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val arcColor = Color(0xFFE53935)
    val targetColor = Color(0xFFC62828)
    val currentColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val arcCenterTopPadding = 16.dp
    val selectedOffsetState = rememberUpdatedState(selectedOffsetDegrees)
    val onOffsetChangeState = rememberUpdatedState(onOffsetChange)
    val onCommitState = rememberUpdatedState(onCommit)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                var latestOffset = selectedOffsetState.value
                fun updateOffset(offset: Offset) {
                    latestOffset = headingOffsetFromArcPointer(
                        pointer = offset,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        centerTopPaddingPx = arcCenterTopPadding.toPx(),
                        previousOffsetDegrees = latestOffset,
                    )
                    onOffsetChangeState.value(latestOffset)
                }

                val down = awaitFirstDown(requireUnconsumed = false)
                updateOffset(down.position)
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                    if (change != null) {
                        if (change.pressed) {
                            updateOffset(change.position)
                        }
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
                onCommitState.value(latestOffset)
            }
        },
    ) {
        val strokeWidth = 15.dp.toPx()
        val markerRadius = 4.dp.toPx()
        val radius = headingArcRadius(size.width, size.height)
        val center = Offset(size.width / 2f, radius + arcCenterTopPadding.toPx())
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val selectedOffset = selectedOffsetDegrees.coerceIn(-TARGET_HEADING_ARC_DEGREES, TARGET_HEADING_ARC_DEGREES)
        val selectedAngle = 270f + selectedOffset
        val selectedRadians = Math.toRadians(selectedAngle.toDouble())
        val selectedPoint = Offset(
            x = center.x + cos(selectedRadians).toFloat() * radius,
            y = center.y + sin(selectedRadians).toFloat() * radius,
        )
        val currentPoint = Offset(center.x, center.y - radius)
        val currentTriangle = Path().apply {
            moveTo(currentPoint.x, currentPoint.y - 17.dp.toPx())
            lineTo(currentPoint.x - 9.dp.toPx(), currentPoint.y - 2.dp.toPx())
            lineTo(currentPoint.x + 9.dp.toPx(), currentPoint.y - 2.dp.toPx())
            close()
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 12.dp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        drawArc(
            color = arcColor.copy(alpha = 0.2f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        if (selectedOffset != 0f) {
            drawArc(
                color = arcColor,
                startAngle = if (selectedOffset < 0f) 270f + selectedOffset else 270f,
                sweepAngle = abs(selectedOffset),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        for (tickOffset in listOf(-90f, -60f, -45f, -30f, 0f, 30f, 45f, 60f, 90f)) {
            val angle = 270f + tickOffset
            val radians = Math.toRadians(angle.toDouble())
            val tickOuterRadius = radius + 10.dp.toPx()
            val tickInnerRadius = radius - 13.dp.toPx()
            val outer = Offset(
                x = center.x + cos(radians).toFloat() * tickOuterRadius,
                y = center.y + sin(radians).toFloat() * tickOuterRadius,
            )
            val inner = Offset(
                x = center.x + cos(radians).toFloat() * tickInnerRadius,
                y = center.y + sin(radians).toFloat() * tickInnerRadius,
            )
            val marker = Offset(
                x = center.x + cos(radians).toFloat() * radius,
                y = center.y + sin(radians).toFloat() * radius,
            )
            drawLine(
                color = currentColor.copy(alpha = if (tickOffset == 0f) 0.78f else 0.42f),
                start = inner,
                end = outer,
                strokeWidth = if (tickOffset == 0f) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = if (tickOffset == 0f) currentColor else arcColor.copy(alpha = 0.62f),
                radius = if (tickOffset == 0f) markerRadius + 1.dp.toPx() else markerRadius,
                center = marker,
            )
            if (tickOffset in listOf(-90f, -45f, 0f, 45f, 90f)) {
                val labelRadius = radius + 29.dp.toPx()
                val labelPoint = Offset(
                    x = center.x + cos(radians).toFloat() * labelRadius,
                    y = center.y + sin(radians).toFloat() * labelRadius,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    tickOffset.roundToInt().signedDegreesText(),
                    labelPoint.x,
                    labelPoint.y + 4.dp.toPx(),
                    labelPaint,
                )
            }
        }
        drawPath(path = currentTriangle, color = currentColor)
        drawLine(
            color = targetColor.copy(alpha = 0.55f),
            start = center,
            end = selectedPoint,
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = targetColor.copy(alpha = 0.18f),
            radius = 25.dp.toPx(),
            center = selectedPoint,
        )
        drawCircle(color = targetColor, radius = 13.dp.toPx(), center = selectedPoint)
        drawCircle(color = surfaceColor, radius = 5.dp.toPx(), center = selectedPoint)
    }
}

private fun headingOffsetFromArcPointer(
    pointer: Offset,
    width: Float,
    height: Float,
    centerTopPaddingPx: Float,
    previousOffsetDegrees: Float,
): Float {
    if (width <= 0f || height <= 0f) {
        return previousOffsetDegrees
    }
    val radius = headingArcRadius(width, height)
    val center = Offset(width / 2f, radius + centerTopPaddingPx)
    val horizontalRatio = ((pointer.x - center.x) / radius).coerceIn(-1f, 1f)
    val degreesFromTop = Math.toDegrees(asin(horizontalRatio).toDouble()).toFloat()
    return degreesFromTop.coerceIn(-TARGET_HEADING_ARC_DEGREES, TARGET_HEADING_ARC_DEGREES)
}

private fun headingArcRadius(width: Float, height: Float): Float {
    return minOf(width * 0.43f, height * 0.72f)
}

private fun Float.headingDisplayText(): String {
    return "${roundToInt()}°"
}

private fun Int.signedDegreesText(): String {
    return if (this > 0) "+${this}°" else "${this}°"
}

private fun normalizedHeadingDegrees(degrees: Float): Float {
    val normalized = degrees % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

private fun shortestHeadingOffset(targetDegrees: Float, currentDegrees: Float): Float {
    var delta = normalizedHeadingDegrees(targetDegrees) - normalizedHeadingDegrees(currentDegrees)
    if (delta > 180f) {
        delta -= 360f
    } else if (delta < -180f) {
        delta += 360f
    }
    return delta
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
                    if (state.connectionState == ConnectionState.Connected) "点击右侧图标切换" else "连接主控后可解锁",
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
private fun FineTuneControls(
    state: ControlUiState,
    fineTuneStepPercent: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val enabled = state.canSendThrottle
    val targetPercent = ((state.leftThrottlePercent + state.rightThrottlePercent) / 2)
        .coerceIn(-100, 100)
    val trimText = state.throttleTrimPercent.signedPercentText()
    val targetText = targetPercent.signedPercentText()
    val color = if (enabled) Color(0xFF00897B) else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "微调 $trimText",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    "目标 $targetText · 步进 ${fineTuneStepPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onDecrease,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = color.copy(alpha = 0.16f),
                        contentColor = color,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = "微调减速")
                }
                FilledTonalIconButton(
                    onClick = onIncrease,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = color.copy(alpha = 0.16f),
                        contentColor = color,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "微调加速")
                }
            }
        }
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

private fun Int.toUserFacingThrottle(reversed: Boolean): Int {
    return if (reversed) -this else this
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

private fun Float?.formatRadPerSecond(): String {
    return this?.let { "%.3f rad/s".format(it) } ?: "--"
}

private const val TARGET_HEADING_ARC_DEGREES = 90f

private fun ControlUiState.headingText(): String {
    return phoneHeadingDegrees.formatDegrees()
}

private fun ControlUiState.targetHeadingText(): String {
    return appHeadingLockTargetDegrees.formatDegrees()
}

private fun ControlUiState.appHeadingErrorText(): String {
    return appHeadingLockErrorDegrees.formatDegrees()
}

private fun ControlUiState.appHeadingCorrectionText(): String {
    return if (headingLockEnabled || appHeadingLockTargetDegrees != null) {
        appHeadingLockCorrectionPercent.signedPercentText()
    } else {
        "--"
    }
}

private fun ControlUiState.appHeadingCommandText(): String {
    val left = appHeadingLeftCommandPercent
    val right = appHeadingRightCommandPercent
    return if ((headingLockEnabled || appHeadingLockTargetDegrees != null) && left != null && right != null) {
        "L ${left.signedPercentText()} / R ${right.signedPercentText()}"
    } else {
        "--"
    }
}

private fun ControlUiState.phoneHeadingStatusText(): String {
    return when {
        phoneHeadingAvailable -> "在线"
        phoneHeadingSensorName.isNotBlank() -> "等待读数"
        else -> "不可用"
    }
}

private fun ControlUiState.ybImuModuleText(): String {
    val quality = telemetry.statusFields["IQUAL"]?.takeIf { it.isNotBlank() }
    return when (telemetry.ybImuAvailable) {
        true -> listOfNotNull("在线", quality).joinToString(" ")
        false -> "离线"
        null -> "--"
    }
}

private fun ControlUiState.ybHeadingText(): String {
    return telemetry.ybYawDegrees.formatDegrees()
}

private fun ControlUiState.ybRollPitchText(): String {
    val roll = telemetry.ybRollDegrees ?: return "--"
    val pitch = telemetry.ybPitchDegrees ?: return "--"
    return "%.1f° / %.1f°".format(roll, pitch)
}

private fun ControlUiState.ybGyroZText(): String {
    return telemetry.ybGyroZRadS.formatRadPerSecond()
}

private fun ControlUiState.ybAccelText(): String {
    val x = telemetry.ybAccelXG ?: return "--"
    val y = telemetry.ybAccelYG ?: return "--"
    val z = telemetry.ybAccelZG ?: return "--"
    return "x %.2fg  y %.2fg  z %.2fg".format(x, y, z)
}

private fun ControlUiState.ybQuaternionText(): String {
    val w = telemetry.ybQuatW ?: return "--"
    val x = telemetry.ybQuatX ?: return "--"
    val y = telemetry.ybQuatY ?: return "--"
    val z = telemetry.ybQuatZ ?: return "--"
    return "w %.3f  x %.3f  y %.3f  z %.3f".format(w, x, y, z)
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
