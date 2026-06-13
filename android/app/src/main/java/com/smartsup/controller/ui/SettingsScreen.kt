package com.smartsup.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.UpdateUiState

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
    onGearThrottleChange: (ThrottleGear, Int) -> Unit,
    onRampLimitChange: (Boolean) -> Unit,
    onLeftEscReversedChange: (Boolean) -> Unit,
    onRightEscReversedChange: (Boolean) -> Unit,
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
            onRampLimitChange = onRampLimitChange,
            onLeftEscReversedChange = onLeftEscReversedChange,
            onRightEscReversedChange = onRightEscReversedChange,
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
            Text("ESP32 经典蓝牙", style = MaterialTheme.typography.titleMedium)
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

            Button(
                onClick = onConnectSaved,
                enabled = settingsState.savedDevice != null &&
                    settingsState.bluetoothEnabled &&
                    controlState.connectionState == ConnectionState.Disconnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("连接已保存设备")
            }

            Button(
                onClick = onScanBluetooth,
                enabled = settingsState.bluetoothEnabled &&
                    settingsState.bluetoothPermissionGranted &&
                    controlState.connectionState == ConnectionState.Disconnected &&
                    !settingsState.discovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (settingsState.discovering) "正在扫描..." else "扫描 SmartSUP 设备")
            }

            OutlinedButton(
                onClick = onRefreshBluetooth,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("刷新设备状态")
            }

            if (controlState.connectionState != ConnectionState.Disconnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("断开 ESP32")
                }
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
                Button(
                    onClick = onConnect,
                    enabled = canConnect,
                ) {
                    Text("连接")
                }
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
            Text("软件更新", style = MaterialTheme.typography.titleMedium)
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

            Button(
                onClick = onCheckUpdates,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (updateState.checking) "正在检查..." else "检查 GitHub 更新")
            }

            Button(
                onClick = onInstallAppUpdate,
                enabled = !busy && updateState.appUpdateAvailable && updateState.appDownloadUrl != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("下载并安装 App 更新")
            }

            Button(
                onClick = onUpdateEsp32FromGitHub,
                enabled = !busy &&
                    controlState.connectionState == ConnectionState.Connected &&
                    updateState.firmwareDownloadUrl != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("从 GitHub 更新 ESP32 固件")
            }

            OutlinedButton(
                onClick = onPickLocalFirmware,
                enabled = !busy && controlState.connectionState == ConnectionState.Connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择本地 ESP32 固件 .bin")
            }
        }
    }
}

@Composable
private fun SafetySettingsCard(
    settingsState: SettingsUiState,
    onAutoReconnectChange: (Boolean) -> Unit,
    onMaxThrottleChange: (Int) -> Unit,
    onRampLimitChange: (Boolean) -> Unit,
    onLeftEscReversedChange: (Boolean) -> Unit,
    onRightEscReversedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("控制安全", style = MaterialTheme.typography.titleMedium)
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
            SettingsRow("上电默认", "锁定")
            SettingsRow("失联处理", "油门回空挡")
            SettingsRow("急停后", "保持锁定")
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
            Text("档位百分比", style = MaterialTheme.typography.titleMedium)
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
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun connectionText(connectionState: ConnectionState): String {
    return when (connectionState) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Connecting -> "连接中"
        ConnectionState.Connected -> "已连接"
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
