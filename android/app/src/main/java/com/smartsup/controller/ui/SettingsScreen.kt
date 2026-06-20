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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CloudQueue
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

private data class VoiceOption(
    val id: String,
    val label: String,
)

private const val DEFAULT_REALTIME_VOICE = "zh_female_vv_uranus_bigtts"

private val REALTIME_VOICE_OPTIONS = listOf(
    VoiceOption("zh_female_vv_uranus_bigtts", "温柔桃子 2.0"),
    VoiceOption("zh_female_xiaohe_uranus_bigtts", "温柔小荷 2.0"),
    VoiceOption("zh_male_m191_uranus_bigtts", "云舟男声 2.0"),
    VoiceOption("zh_male_taocheng_uranus_bigtts", "青年小天 2.0"),
    VoiceOption("zh_female_xiaoxue_uranus_bigtts", "故事小雪 2.0"),
    VoiceOption("en_female_dacey_uranus_bigtts", "Dacey 英文女声 2.0"),
    VoiceOption("en_male_tim_uranus_bigtts", "Tim 英文男声 2.0"),
    VoiceOption("zh_female_shuangkuaisisi_moon_bigtts", "爽快思思 1.0"),
    VoiceOption("zh_female_sajiaonvyou_moon_bigtts", "撒娇女友 1.0"),
    VoiceOption("zh_male_aojiaobazong_moon_bigtts", "傲娇霸总 1.0"),
    VoiceOption("zh_female_gaolengyujie_moon_bigtts", "高冷御姐 1.0"),
    VoiceOption("zh_female_gaolengyujie_emo_v2_mars_bigtts", "多情绪御姐 1.0"),
    VoiceOption("en_female_candice_emo_v2_mars_bigtts", "Candice 多情绪英文女声 1.0"),
)

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
    onHeadingLockNeutralPivotMinDifferenceChange: (Int) -> Unit,
    onHeadingLockNeutralPivotMaxDifferenceChange: (Int) -> Unit,
    onAutoNavigationGpsJumpResetChange: (Int) -> Unit,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
    onRealtimeVoiceEndpointChange: (String) -> Unit,
    onRealtimeVoiceAppIdChange: (String) -> Unit,
    onRealtimeVoiceApiKeyChange: (String) -> Unit,
    onRealtimeVoiceModelChange: (String) -> Unit,
    onRealtimeVoiceVoiceChange: (String) -> Unit,
    onStartMagCalibration: () -> Unit,
    onSaveMagCalibration: () -> Unit,
    onClearMagCalibration: () -> Unit,
    onRefreshMagCalibrationStatus: () -> Unit,
    onCheckUpdates: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onUpdateEsp32FromGitHub: () -> Unit,
) {
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
            onHeadingLockNeutralPivotMinDifferenceChange = onHeadingLockNeutralPivotMinDifferenceChange,
            onHeadingLockNeutralPivotMaxDifferenceChange = onHeadingLockNeutralPivotMaxDifferenceChange,
            onAutoNavigationGpsJumpResetChange = onAutoNavigationGpsJumpResetChange,
            onUsePhoneHeadingChange = onUsePhoneHeadingChange,
        )

        RealtimeVoiceSettingsCard(
            settingsState = settingsState,
            onApiKeyChange = onRealtimeVoiceApiKeyChange,
            onVoiceChange = onRealtimeVoiceVoiceChange,
        )

        PhoneHeadingSettingsCard(
            controlState = controlState,
            settingsState = settingsState,
            onUsePhoneHeadingChange = onUsePhoneHeadingChange,
            onStartMagCalibration = onStartMagCalibration,
            onSaveMagCalibration = onSaveMagCalibration,
            onClearMagCalibration = onClearMagCalibration,
            onRefreshMagCalibrationStatus = onRefreshMagCalibrationStatus,
        )

        Esp32ImuObservationCard(controlState = controlState)

        GearPercentSettingsCard(
            settingsState = settingsState,
            onGearThrottleChange = onGearThrottleChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealtimeVoiceSettingsCard(
    settingsState: SettingsUiState,
    onApiKeyChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
) {
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    val voiceOptions = remember(settingsState.realtimeVoiceVoice) {
        val configured = settingsState.realtimeVoiceVoice.ifBlank { DEFAULT_REALTIME_VOICE }
        val options = REALTIME_VOICE_OPTIONS
            .let { list -> if (list.any { it.id == configured }) list else list + VoiceOption(configured, configured) }
        options to options.first { it.id == configured }
    }
    val selectedVoice = voiceOptions.second
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSectionHeader(
                title = "云端实时语音",
                icon = Icons.Outlined.CloudQueue,
                color = Color(0xFF1565C0),
            )
            Text(
                "未配置 API Key 时不会启动麦克风上传。云端 TTS 使用同一个 API Key 或 .env 中的 DOUBAO_API_KEY。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settingsState.realtimeVoiceApiKey,
                onValueChange = onApiKeyChange,
                label = { Text("火山引擎 API Key") },
                placeholder = { Text("ARK_API_KEY") },
                singleLine = true,
                isError = settingsState.realtimeVoiceApiKey.isBlank(),
                supportingText = {
                    Text(
                        if (settingsState.realtimeVoiceApiKey.isBlank()) {
                            "请填写后再开启实时语音"
                        } else {
                            "用于方舟 Chat API，不会显示明文"
                        },
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(
                expanded = voiceMenuExpanded,
                onExpandedChange = { voiceMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedVoice.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("云端 TTS 音色") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = voiceMenuExpanded,
                    onDismissRequest = { voiceMenuExpanded = false },
                ) {
                    voiceOptions.first.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                voiceMenuExpanded = false
                                onVoiceChange(option.id)
                            },
                        )
                    }
                }
            }
            Text(
                "仅在语音页切换为云端 TTS 时生效；本地 TTS 使用 Android 系统音色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                title = "主控蓝牙",
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
                    text = "断开主控",
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
                        "正在扫描附近名称以 ${settingsState.deviceNamePrefix} 开头的主控..."
                    } else {
                        "点击“扫描 SmartSUP 设备”在 App 内发现主控。"
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
) {
    val busy = updateState.checking || updateState.downloading || updateState.esp32Uploading
    val showAppUpdate = updateState.appUpdateAvailable && updateState.appDownloadUrl != null
    val showEsp32Update = updateState.esp32UpdateAvailable &&
        updateState.firmwareDownloadUrl != null &&
        updateState.firmwareManifestName != null
    val showTargetEsp32 = updateState.targetEsp32FirmwareVersion != null &&
        (showEsp32Update || updateState.esp32Uploading)
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
            SettingsRow("当前主控", updateState.currentEsp32FirmwareVersion ?: "--")
            updateState.latestVersionName?.let { SettingsRow("最新 App", it) }
            if (showTargetEsp32) {
                SettingsRow("目标主控", updateState.targetEsp32FirmwareVersion ?: "--")
            }
            Text(updateState.message, color = MaterialTheme.colorScheme.primary)
            if (updateState.progressText.isNotBlank()) {
                Text(updateState.progressText, style = MaterialTheme.typography.bodySmall)
            }

            SettingsActionButton(
                onClick = onCheckUpdates,
                enabled = !busy,
                text = if (updateState.checking) "正在检查..." else "检查更新",
                icon = Icons.Outlined.Refresh,
                color = Color(0xFF1565C0),
                modifier = Modifier.fillMaxWidth(),
            )

            if (showAppUpdate) {
                SettingsActionButton(
                    onClick = onInstallAppUpdate,
                    enabled = !busy,
                    text = "下载并安装 App 更新",
                    icon = Icons.Outlined.Download,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (showEsp32Update) {
                SettingsActionButton(
                    onClick = onUpdateEsp32FromGitHub,
                    enabled = !busy && controlState.connectionState == ConnectionState.Connected,
                    text = "更新主控固件",
                    icon = Icons.Outlined.SystemUpdate,
                    color = Color(0xFFE65100),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
    onHeadingLockNeutralPivotMinDifferenceChange: (Int) -> Unit,
    onHeadingLockNeutralPivotMaxDifferenceChange: (Int) -> Unit,
    onAutoNavigationGpsJumpResetChange: (Int) -> Unit,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
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
                label = "启动后自动连接已保存主控",
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
            SwitchRow(
                label = "航向锁定使用主控 IMU",
                checked = !settingsState.usePhoneHeading,
                onCheckedChange = { useEsp32Imu -> onUsePhoneHeadingChange(!useEsp32Imu) },
            )
            Text(
                "测试开关仅影响航向锁定和角度转向；自动导航仍使用手机指南针。切换时会取消当前锁航并回空挡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                label = "空档最小转向差",
                value = settingsState.headingLockNeutralPivotMinDifferencePercent,
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = onHeadingLockNeutralPivotMinDifferenceChange,
            )
            PercentSliderRow(
                label = "空档最大转向差",
                value = settingsState.headingLockNeutralPivotMaxDifferencePercent,
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = onHeadingLockNeutralPivotMaxDifferenceChange,
            )
            Text(
                "仅空档原地掉头时生效：航向误差越大左右差值越大，默认 10%-60%，实际下发会按左右 ESC 反向设置转换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MeterSliderRow(
                label = "自动导航 GPS 跳变重置",
                value = settingsState.autoNavigationGpsJumpResetMeters,
                valueRange = 3f..30f,
                steps = 26,
                onValueChange = onAutoNavigationGpsJumpResetChange,
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
private fun MeterSliderRow(
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
            Text("${value}m", fontWeight = FontWeight.Medium)
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
private fun PhoneHeadingSettingsCard(
    controlState: ControlUiState,
    settingsState: SettingsUiState,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
    onStartMagCalibration: () -> Unit,
    onSaveMagCalibration: () -> Unit,
    onClearMagCalibration: () -> Unit,
    onRefreshMagCalibrationStatus: () -> Unit,
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
                title = "航向锁定来源",
                icon = Icons.Outlined.Tune,
                color = Color(0xFF00695C),
            )
            SettingsRow("连接", connectionText(controlState.connectionState))
            SettingsRow(
                "当前来源",
                if (settingsState.usePhoneHeading) "手机指南针" else "主控 IMU 测试模式",
            )
            SettingsRow(
                "手机航向",
                controlState.phoneHeadingDegrees?.let { "${it.roundToInt()}°" } ?: "--",
            )
            SettingsRow(
                "IMU 航向",
                controlState.telemetry.ybYawDegrees
                    ?.let { normalizeCompassDegrees(it).roundToInt() }
                    ?.let { "$it°" } ?: "--",
            )
            SettingsRow(
                "手机传感器",
                controlState.phoneHeadingSensorName.ifBlank {
                    if (settingsState.usePhoneHeading) "等待传感器" else "--"
                },
            )
            SettingsRow(
                "App 目标航向",
                controlState.appHeadingLockTargetDegrees?.let { "${it.roundToInt()}°" } ?: "--",
            )
            SettingsRow(
                "App 航向误差",
                controlState.appHeadingLockErrorDegrees?.let { "${it.roundToInt()}°" } ?: "--",
            )
            SettingsRow("App 差速修正", "${controlState.appHeadingLockCorrectionPercent}%")
            SettingsRow("主控航向闭环", "停用，仅执行左右 ESC 功率")
        }
    }
}

@Composable
private fun Esp32ImuObservationCard(
    controlState: ControlUiState,
) {
    val isConnected = controlState.connectionState == ConnectionState.Connected
    val fields = if (isConnected) controlState.telemetry.statusFields else emptyMap()
    val espHeading = if (isConnected) {
        fields["IHDG"]?.toFloatOrNull() ?: controlState.telemetry.headingDegrees
    } else {
        null
    }
    val ybRawYaw = controlState.telemetry.ybYawDegrees
    val ybHeading = ybRawYaw?.let { normalizeCompassDegrees(-it) }
    val imuAvailable = if (isConnected) controlState.telemetry.imuAvailable else null
    val phoneHeading = controlState.phoneHeadingDegrees

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "主控 IMU 观测",
                icon = Icons.Outlined.Settings,
                color = Color(0xFF455A64),
            )
            SettingsRow("连接", connectionText(controlState.connectionState))
            SettingsRow("记录样本", "${controlState.imuTelemetryLogSampleCount} 条")
            SettingsRow("记录文件", controlState.imuTelemetryLogPath.ifBlank { "--" })
            SettingsRow("观测状态", imuObservationStateText(isConnected, fields))
            SettingsRow("IMU", imuAvailableText(fields["IMU"], imuAvailable))
            SettingsRow("磁力计", imuAvailableText(fields["MAG"], null))
            SettingsRow("航向来源", headingSourceText(fields["HSRC"]))
            SettingsRow("融合质量", fields["IQUAL"] ?: "--")
            SettingsRow("九轴 IMU", if (controlState.telemetry.ybImuAvailable == true) "在线" else fields["YBIMU"] ?: "--")
            SettingsRow("手机航向", phoneHeading?.let { "${it.roundToInt()}°" } ?: "--")
            SettingsRow("IMU 航向", ybHeading?.let { "${it.roundToInt()}°" } ?: "--")
            SettingsRow("IMU 原始 YBY", ybRawYaw?.let { "${it.roundToInt()}°" } ?: "--")
            SettingsRow(
                "主控航向",
                espHeading?.let { "${it.roundToInt()}°" } ?: fields.imuValue("IHDG", "°"),
            )
            SettingsRow("手机 - IMU", headingDeltaText(phoneHeading, ybHeading))
            SettingsRow("裸磁航向", fields.imuValue("IMHDG", "°"))
            SettingsRow("Mahony yaw", fields.imuValue("IAHRS", "°"))
            SettingsRow("手机 - 主控", headingDeltaText(phoneHeading, espHeading))
            HorizontalDivider()
            SettingsRow(
                "YB 姿态",
                listOfNotNull(
                    controlState.telemetry.ybRollDegrees?.let { "r=${it.roundToInt()}°" },
                    controlState.telemetry.ybPitchDegrees?.let { "p=${it.roundToInt()}°" },
                    ybHeading?.let { "y=${it.roundToInt()}°" },
                ).joinToString(" ").ifBlank { "--" },
            )
            SettingsRow(
                "YB 加速度",
                listOfNotNull(
                    controlState.telemetry.ybAccelXG?.let { "x=${"%.2f".format(it)}g" },
                    controlState.telemetry.ybAccelYG?.let { "y=${"%.2f".format(it)}g" },
                    controlState.telemetry.ybAccelZG?.let { "z=${"%.2f".format(it)}g" },
                ).joinToString(" ").ifBlank { "--" },
            )
            SettingsRow("YB Z 陀螺", controlState.telemetry.ybGyroZRadS?.let { "%.3f rad/s".format(it) } ?: "--")
            SettingsRow(
                "YB 四元数",
                listOfNotNull(
                    controlState.telemetry.ybQuatW?.let { "w=${"%.3f".format(it)}" },
                    controlState.telemetry.ybQuatX?.let { "x=${"%.3f".format(it)}" },
                    controlState.telemetry.ybQuatY?.let { "y=${"%.3f".format(it)}" },
                    controlState.telemetry.ybQuatZ?.let { "z=${"%.3f".format(it)}" },
                ).joinToString(" ").ifBlank { "--" },
            )
            HorizontalDivider()
            SettingsRow("Mahony 姿态", fields.imuTriplet("IROLL", "IPITCH", "IHDG", "°"))
            SettingsRow("加速度", fields.imuTriplet("IAX", "IAY", "IAZ", "g"))
            SettingsRow("加速度模长", fields.imuValue("IAN", "g"))
            SettingsRow("陀螺仪", fields.imuTriplet("IGX", "IGY", "IGZ", "dps"))
            SettingsRow("陀螺零偏", fields.imuValue("IGZB", "dps"))
            SettingsRow("磁力计原始", fields.imuTriplet("IMX", "IMY", "IMZ", "raw"))
            SettingsRow("磁场强度", fields.imuValue("IMAG", "raw"))
            SettingsRow("磁力计校准", magCalibrationStateText(fields["MCAL"] ?: "UNKNOWN"))
            SettingsRow("校准样本", fields["MCNT"] ?: "--")
            SettingsRow("校准范围", magRangeText(fields["MRX"], fields["MRY"]))
            Text(
                "默认仅用于对比和记录；开启测试开关后，航向锁定可使用 IMU 航向，自动导航仍使用手机指南针。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        "FUSION" -> "融合航向"
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

private fun imuObservationStateText(isConnected: Boolean, fields: Map<String, String>): String {
    return when {
        !isConnected -> "未连接主控"
        fields["YBIMU"] == "1" || fields.containsKey("IAX") -> "观测中"
        fields["IMU"] == "0" -> "IMU 不可用"
        else -> "等待主控新字段"
    }
}

private fun imuAvailableText(rawStatus: String?, fallback: Boolean?): String {
    return when (rawStatus) {
        "1" -> "可用"
        "0" -> "不可用"
        null -> when (fallback) {
            true -> "可用"
            false -> "不可用"
            null -> "--"
        }
        else -> rawStatus
    }
}

private fun Map<String, String>.imuValue(key: String, suffix: String = ""): String {
    val value = this[key] ?: return "--"
    return if (suffix.isBlank()) value else "$value $suffix"
}

private fun Map<String, String>.imuTriplet(
    xKey: String,
    yKey: String,
    zKey: String,
    suffix: String,
): String {
    val x = this[xKey] ?: return "--"
    val y = this[yKey] ?: return "--"
    val z = this[zKey] ?: return "--"
    return "$x / $y / $z $suffix"
}

private fun headingDeltaText(phoneHeading: Float?, espHeading: Float?): String {
    if (phoneHeading == null || espHeading == null) {
        return "--"
    }
    val delta = shortestHeadingDelta(phoneHeading, espHeading)
    return "${delta.roundToInt()}°"
}

private fun normalizeCompassDegrees(degrees: Float): Float {
    return ((degrees % 360f) + 360f) % 360f
}

private fun shortestHeadingDelta(target: Float, current: Float): Float {
    var delta = (target - current) % 360f
    if (delta > 180f) {
        delta -= 360f
    }
    if (delta < -180f) {
        delta += 360f
    }
    return delta
}

private fun magRangeText(rangeX: String?, rangeY: String?): String {
    return when {
        rangeX != null && rangeY != null -> "$rangeX / $rangeY"
        rangeX != null -> rangeX
        rangeY != null -> rangeY
        else -> "--"
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
