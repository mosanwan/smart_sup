package com.smartsup.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.UpdateUiState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    controlState: ControlUiState,
    settingsState: SettingsUiState,
    updateState: UpdateUiState,
    modifier: Modifier = Modifier,
    onRefreshBluetooth: () -> Unit,
    onScanBluetooth: () -> Unit,
    onConnectSaved: () -> Unit,
    onConnectDevice: (BluetoothDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onMaxThrottleChange: (Int) -> Unit,
    onVoicePowerLimitChange: (Int) -> Unit,
    onFineTuneStepChange: (Int) -> Unit,
    onGearThrottleChange: (ThrottleGear, Int) -> Unit,
    onRampLimitChange: (Boolean) -> Unit,
    onLeftEscReversedChange: (Boolean) -> Unit,
    onRightEscReversedChange: (Boolean) -> Unit,
    onHeadingLockToleranceChange: (Int) -> Unit,
    onHeadingLockFullCorrectionChange: (Int) -> Unit,
    onHeadingLockNeutralReverseChange: (Int) -> Unit,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
    onStartMagCalibration: () -> Unit,
    onSaveMagCalibration: () -> Unit,
    onClearMagCalibration: () -> Unit,
    onRefreshMagCalibrationStatus: () -> Unit,
    onCheckUpdates: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onUpdateEsp32FromGitHub: () -> Unit,
    onUploadLocalEsp32Firmware: (android.net.Uri) -> Unit,
) {
    val firmwarePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onUploadLocalEsp32Firmware(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BluetoothCard(
            controlState = controlState,
            settingsState = settingsState,
            onRefreshBluetooth = onRefreshBluetooth,
            onScanBluetooth = onScanBluetooth,
            onConnectSaved = onConnectSaved,
            onConnectDevice = onConnectDevice,
            onDisconnect = onDisconnect,
        )

        UpdateSettingsCard(
            controlState = controlState,
            updateState = updateState,
            onCheckUpdates = onCheckUpdates,
            onInstallAppUpdate = onInstallAppUpdate,
            onUpdateEsp32FromGitHub = onUpdateEsp32FromGitHub,
            onPickLocalFirmware = {
                firmwarePicker.launch(
                    arrayOf(
                        "application/octet-stream",
                        "application/macbinary",
                        "*/*",
                    ),
                )
            },
        )

        SafetySettingsCard(
            settingsState = settingsState,
            onAutoReconnectChange = onAutoReconnectChange,
            onMaxThrottleChange = onMaxThrottleChange,
            onVoicePowerLimitChange = onVoicePowerLimitChange,
            onFineTuneStepChange = onFineTuneStepChange,
            onRampLimitChange = onRampLimitChange,
            onLeftEscReversedChange = onLeftEscReversedChange,
            onRightEscReversedChange = onRightEscReversedChange,
            onHeadingLockToleranceChange = onHeadingLockToleranceChange,
            onHeadingLockFullCorrectionChange = onHeadingLockFullCorrectionChange,
            onHeadingLockNeutralReverseChange = onHeadingLockNeutralReverseChange,
        )

        MagCalibrationSettingsCard(
            controlState = controlState,
            settingsState = settingsState,
            onUsePhoneHeadingChange = onUsePhoneHeadingChange,
            onStartMagCalibration = onStartMagCalibration,
            onSaveMagCalibration = onSaveMagCalibration,
            onClearMagCalibration = onClearMagCalibration,
            onRefreshMagCalibrationStatus = onRefreshMagCalibrationStatus,
        )

        GearPercentSettingsCard(
            settingsState = settingsState,
            onGearThrottleChange = onGearThrottleChange,
        )
    }
}

@Composable
private fun BluetoothCard(
    controlState: ControlUiState,
    settingsState: SettingsUiState,
    onRefreshBluetooth: () -> Unit,
    onScanBluetooth: () -> Unit,
    onConnectSaved: () -> Unit,
    onConnectDevice: (BluetoothDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSectionHeader(
                title = "ESP32 经典蓝牙",
                icon = Icons.Outlined.Bluetooth,
                color = Color(0xFF1565C0),
            )
            SettingsRow("蓝牙", if (settingsState.bluetoothEnabled) "已开启" else "未开启")
            SettingsRow("权限", if (settingsState.bluetoothPermissionGranted) "已授权" else "未授权")
            SettingsRow("过滤前缀", settingsState.deviceNamePrefix)
            SettingsRow("连接", connectionText(controlState.connectionState))
            SettingsRow("已保存", settingsState.savedDevice?.name ?: "无")
            Text(settingsState.message, color = MaterialTheme.colorScheme.primary)
            Text(
                "设备编号是出厂首次刷入时固定的硬件编号，App 只按 ${settingsState.deviceNamePrefix} 前缀发现设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsActionButton(
                onClick = onConnectSaved,
                enabled = settingsState.savedDevice != null &&
                    settingsState.bluetoothEnabled &&
                    controlState.connectionState == ConnectionState.Disconnected,
                text = "连接已保存设备",
                icon = Icons.Outlined.Link,
                color = Color(0xFF2E7D32),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsActionButton(
                onClick = onScanBluetooth,
                enabled = settingsState.bluetoothEnabled &&
                    settingsState.bluetoothPermissionGranted &&
                    controlState.connectionState == ConnectionState.Disconnected &&
                    !settingsState.discovering,
                text = if (settingsState.discovering) "正在扫描..." else "扫描 SmartSUP 设备",
                icon = Icons.Outlined.Search,
                color = Color(0xFF1565C0),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsActionButton(
                onClick = onRefreshBluetooth,
                text = "刷新设备状态",
                icon = Icons.Outlined.Refresh,
                color = Color(0xFF546E7A),
                modifier = Modifier.fillMaxWidth(),
            )

            if (controlState.connectionState != ConnectionState.Disconnected) {
                SettingsActionButton(
                    onClick = onDisconnect,
                    text = "断开 ESP32",
                    icon = Icons.Outlined.LinkOff,
                    color = Color(0xFFC62828),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (settingsState.pairedDevices.isNotEmpty()) {
                HorizontalDivider()
                Text("已配对 SmartSUP 设备", style = MaterialTheme.typography.titleSmall)
                settingsState.pairedDevices.forEach { device ->
                    BluetoothDeviceRow(
                        device = device,
                        isSaved = settingsState.savedDevice?.address == device.address,
                        canConnect = settingsState.bluetoothEnabled &&
                            controlState.connectionState == ConnectionState.Disconnected,
                        onConnect = { onConnectDevice(device) },
                    )
                }
            }

            HorizontalDivider()
            Text("扫描发现", style = MaterialTheme.typography.titleSmall)
            if (settingsState.discoveredDevices.isNotEmpty()) {
                settingsState.discoveredDevices.forEach { device ->
                    BluetoothDeviceRow(
                        device = device,
                        isSaved = settingsState.savedDevice?.address == device.address,
                        canConnect = settingsState.bluetoothEnabled &&
                            controlState.connectionState == ConnectionState.Disconnected,
                        onConnect = { onConnectDevice(device) },
                    )
                }
            } else {
                Text(
                    if (settingsState.discovering) {
                        "正在扫描附近名称以 ${settingsState.deviceNamePrefix} 开头的 ESP32..."
                    } else {
                        "点击“扫描 SmartSUP 设备”在 App 内发现 ESP32。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun BluetoothDeviceRow(
    device: BluetoothDeviceInfo,
    isSaved: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, fontWeight = FontWeight.Medium)
                    if (isSaved) {
                        Text(
                            "已保存",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                SettingsActionButton(
                    onClick = onConnect,
                    enabled = canConnect,
                    text = "连接",
                    icon = Icons.Outlined.Link,
                    color = Color(0xFF2E7D32),
                )
            }
        }
    }
}

@Composable
private fun UpdateSettingsCard(
    controlState: ControlUiState,
    updateState: UpdateUiState,
    onCheckUpdates: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onUpdateEsp32FromGitHub: () -> Unit,
    onPickLocalFirmware: () -> Unit,
) {
    val busy = updateState.checking || updateState.downloading || updateState.esp32Uploading
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSectionHeader(
                title = "软件更新",
                icon = Icons.Outlined.SystemUpdate,
                color = Color(0xFFE65100),
            )
            SettingsRow("当前 App", BuildConfig.VERSION_NAME)
            SettingsRow("GitHub 最新", updateState.latestVersionName ?: "--")
            SettingsRow("App 产物", updateState.appAssetName ?: "--")
            SettingsRow("ESP32 固件", updateState.firmwareAssetName ?: "--")
            SettingsRow("更新源", "GitHub Public Release")
            Text(
                "当前仓库已公开，App 检查更新不需要 Token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(updateState.message, color = MaterialTheme.colorScheme.primary)
            if (updateState.progressText.isNotBlank()) {
                Text(updateState.progressText, style = MaterialTheme.typography.bodySmall)
            }

            SettingsActionButton(
                onClick = onCheckUpdates,
                enabled = !busy,
                text = if (updateState.checking) "正在检查..." else "检查 GitHub 更新",
                icon = Icons.Outlined.Refresh,
                color = Color(0xFF1565C0),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsActionButton(
                onClick = onInstallAppUpdate,
                enabled = !busy && updateState.appUpdateAvailable && updateState.appDownloadUrl != null,
                text = "下载并安装 App 更新",
                icon = Icons.Outlined.Download,
                color = Color(0xFF2E7D32),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsActionButton(
                onClick = onUpdateEsp32FromGitHub,
                enabled = !busy &&
                    controlState.connectionState == ConnectionState.Connected &&
                    updateState.firmwareDownloadUrl != null,
                text = "从 GitHub 更新 ESP32 固件",
                icon = Icons.Outlined.SystemUpdate,
                color = Color(0xFFE65100),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsActionButton(
                onClick = onPickLocalFirmware,
                enabled = !busy && controlState.connectionState == ConnectionState.Connected,
                text = "选择本地 ESP32 固件 .bin",
                icon = Icons.Outlined.UploadFile,
                color = Color(0xFF546E7A),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SafetySettingsCard(
    settingsState: SettingsUiState,
    onAutoReconnectChange: (Boolean) -> Unit,
    onMaxThrottleChange: (Int) -> Unit,
    onVoicePowerLimitChange: (Int) -> Unit,
    onFineTuneStepChange: (Int) -> Unit,
    onRampLimitChange: (Boolean) -> Unit,
    onLeftEscReversedChange: (Boolean) -> Unit,
    onRightEscReversedChange: (Boolean) -> Unit,
    onHeadingLockToleranceChange: (Int) -> Unit,
    onHeadingLockFullCorrectionChange: (Int) -> Unit,
    onHeadingLockNeutralReverseChange: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "控制安全",
                icon = Icons.Outlined.Security,
                color = Color(0xFFC62828),
            )
            SwitchRow(
                label = "启动后自动连接已保存 ESP32",
                checked = settingsState.autoReconnect,
                onCheckedChange = onAutoReconnectChange,
            )
            SwitchRow(
                label = "油门斜率限制",
                checked = settingsState.rampLimitEnabled,
                onCheckedChange = onRampLimitChange,
            )
            SwitchRow(
                label = "左 ESC 方向反转",
                checked = settingsState.leftEscReversed,
                onCheckedChange = onLeftEscReversedChange,
            )
            SwitchRow(
                label = "右 ESC 方向反转",
                checked = settingsState.rightEscReversed,
                onCheckedChange = onRightEscReversedChange,
            )
            Text("正反向油门限幅：+/-${settingsState.maxThrottlePercent}%")
            Slider(
                value = settingsState.maxThrottlePercent.toFloat(),
                onValueChange = { onMaxThrottleChange(it.toInt()) },
                valueRange = 5f..100f,
                steps = 18,
            )
            PercentSliderRow(
                label = "声控功率限制",
                value = settingsState.voicePowerLimitPercent,
                valueRange = 5f..100f,
                steps = 94,
                onValueChange = onVoicePowerLimitChange,
            )
            PercentSliderRow(
                label = "微调步进",
                value = settingsState.fineTuneStepPercent,
                valueRange = 1f..10f,
                steps = 8,
                onValueChange = onFineTuneStepChange,
            )
            DegreeSliderRow(
                label = "航向锁定容差",
                value = settingsState.headingLockToleranceDegrees,
                valueRange = 1f..20f,
                steps = 18,
                onValueChange = onHeadingLockToleranceChange,
            )
            DegreeSliderRow(
                label = "最大转向角度",
                value = settingsState.headingLockFullCorrectionDegrees,
                valueRange = 5f..180f,
                steps = 174,
                onValueChange = onHeadingLockFullCorrectionChange,
            )
            PercentSliderRow(
                label = "空档锁航最大反推",
                value = settingsState.headingLockNeutralReversePercent,
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = onHeadingLockNeutralReverseChange,
            )
            SettingsRow("上电默认", "锁定")
            SettingsRow("失联处理", "油门回空挡")
            SettingsRow("急停后", "保持锁定")
        }
    }
}

@Composable
private fun DegreeSliderRow(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${value}°", fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun PercentSliderRow(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${value}%", fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun MagCalibrationSettingsCard(
    controlState: ControlUiState,
    settingsState: SettingsUiState,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
    onStartMagCalibration: () -> Unit,
    onSaveMagCalibration: () -> Unit,
    onClearMagCalibration: () -> Unit,
    onRefreshMagCalibrationStatus: () -> Unit,
) {
    val statusFields = controlState.telemetry.statusFields
    val connected = controlState.connectionState == ConnectionState.Connected
    val calibrationState = statusFields["MCAL"] ?: "UNKNOWN"
    val calibrationActive = calibrationState == "ACTIVE"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "航向来源与校准",
                icon = Icons.Outlined.Tune,
                color = Color(0xFF00695C),
            )
            SettingsRow("连接", connectionText(controlState.connectionState))
            SettingsRow("航向来源", headingSourceText(statusFields["HSRC"]))
            SettingsRow("当前航向", statusFields["HDG"]?.let { "$it°" } ?: "--")
            SwitchRow(
                label = "使用手机指南针",
                checked = settingsState.usePhoneHeading,
                onCheckedChange = onUsePhoneHeadingChange,
            )
            SettingsRow(
                "手机航向",
                controlState.phoneHeadingDegrees?.let { "${it.roundToInt()}°" } ?: "--",
            )
            SettingsRow(
                "手机传感器",
                controlState.phoneHeadingSensorName.ifBlank {
                    if (settingsState.usePhoneHeading) "等待传感器" else "--"
                },
            )
            SettingsRow("校准状态", magCalibrationStateText(calibrationState))
            SettingsRow("采样数量", statusFields["MCNT"] ?: "0")
            SettingsRow(
                "覆盖范围",
                listOfNotNull(statusFields["MRX"]?.let { "X $it" }, statusFields["MRY"]?.let { "Y $it" })
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ")
                    ?: "--",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsActionButton(
                    onClick = onStartMagCalibration,
                    enabled = connected,
                    text = "开始校准",
                    icon = Icons.Outlined.Tune,
                    color = Color(0xFF00695C),
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    onClick = onSaveMagCalibration,
                    enabled = connected && calibrationActive,
                    text = "保存校准",
                    icon = Icons.Outlined.UploadFile,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsActionButton(
                    onClick = onRefreshMagCalibrationStatus,
                    enabled = connected,
                    text = "刷新状态",
                    icon = Icons.Outlined.Refresh,
                    color = Color(0xFF1565C0),
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    onClick = onClearMagCalibration,
                    enabled = connected && (calibrationActive || calibrationState == "SAVED"),
                    text = "清除校准",
                    icon = Icons.Outlined.LinkOff,
                    color = Color(0xFFC62828),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GearPercentSettingsCard(
    settingsState: SettingsUiState,
    onGearThrottleChange: (ThrottleGear, Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "档位百分比",
                icon = Icons.Outlined.Speed,
                color = Color(0xFF2E7D32),
            )
            ThrottleGear.entries.forEach { gear ->
                if (gear == ThrottleGear.Neutral) {
                    SettingsRow(gear.label, "0%")
                } else {
                    GearSliderRow(
                        gear = gear,
                        value = settingsState.gearPercents[gear] ?: gear.defaultThrottlePercent,
                        onValueChange = { onGearThrottleChange(gear, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GearSliderRow(
    gear: ThrottleGear,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(gear.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.signedPercentText(), fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = gear.valueRange(),
            steps = 18,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSectionHeader(
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = actualColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f),
        )
    }
}

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
    }
}

private fun headingSourceText(source: String?): String {
    return when (source) {
        "PHONE" -> "手机指南针"
        "MAG" -> "磁力计"
        "NONE" -> "无"
        null -> "--"
        else -> source
    }
}

private fun magCalibrationStateText(state: String): String {
    return when (state) {
        "ACTIVE" -> "校准中"
        "SAVED" -> "已保存"
        "NONE" -> "未保存"
        "UNKNOWN" -> "--"
        else -> state
    }
}

private fun Int.signedPercentText(): String {
    return if (this > 0) "+$this%" else "$this%"
}

private fun ThrottleGear.valueRange(): ClosedFloatingPointRange<Float> {
    return when {
        isReverse -> -100f..-5f
        isForward -> 5f..100f
        else -> 0f..0f
    }
}
