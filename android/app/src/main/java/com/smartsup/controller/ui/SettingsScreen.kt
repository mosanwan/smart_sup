package com.smartsup.controller.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartsup.controller.BuildConfig
import com.smartsup.controller.model.BluetoothDeviceInfo
import com.smartsup.controller.model.ConnectionState
import com.smartsup.controller.model.ControlUiState
import com.smartsup.controller.model.NavigationGpsSource
import com.smartsup.controller.model.SettingsUiState
import com.smartsup.controller.model.ThrottleGear
import com.smartsup.controller.model.UpdateUiState
import com.smartsup.controller.model.ybImuUncalibratedHeadingDegrees
import kotlin.math.atan2
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
    onNavigationGpsSourceChange: (NavigationGpsSource) -> Unit,
    onAutoNavigationGpsJumpResetChange: (Int) -> Unit,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
    onRealtimeVoiceEndpointChange: (String) -> Unit,
    onRealtimeVoiceAppIdChange: (String) -> Unit,
    onRealtimeVoiceApiKeyChange: (String) -> Unit,
    onRealtimeVoiceModelChange: (String) -> Unit,
    onRealtimeVoiceVoiceChange: (String) -> Unit,
    onStartYbImuCalibration: () -> Unit,
    onStartYbMagCalibration: () -> Unit,
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
            controlState = controlState,
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
            onNavigationGpsSourceChange = onNavigationGpsSourceChange,
            onAutoNavigationGpsJumpResetChange = onAutoNavigationGpsJumpResetChange,
            onUsePhoneHeadingChange = onUsePhoneHeadingChange,
        )

        RealtimeVoiceSettingsCard(
            settingsState = settingsState,
            onApiKeyChange = onRealtimeVoiceApiKeyChange,
            onVoiceChange = onRealtimeVoiceVoiceChange,
        )

        Esp32ImuObservationCard(
            controlState = controlState,
            onStartYbImuCalibration = onStartYbImuCalibration,
            onStartYbMagCalibration = onStartYbMagCalibration,
        )

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
    val connected = controlState.connectionState == ConnectionState.Connected
    val canUseBluetooth = settingsState.bluetoothAvailable &&
        settingsState.bluetoothEnabled &&
        settingsState.bluetoothPermissionGranted
    val canConnect = canUseBluetooth && controlState.connectionState == ConnectionState.Disconnected
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    settingsState.savedDevice?.name ?: "SmartSUP 主控",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(
                    text = connectionText(controlState.connectionState),
                    color = when (controlState.connectionState) {
                        ConnectionState.Connected -> Color(0xFF2E7D32)
                        ConnectionState.Connecting -> Color(0xFFE65100)
                        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when {
                !settingsState.bluetoothAvailable -> ProblemBanner("当前手机不支持蓝牙。")
                !settingsState.bluetoothEnabled -> ProblemBanner("请先打开手机蓝牙。")
                !settingsState.bluetoothPermissionGranted -> ProblemBanner("请授予蓝牙扫描和连接权限。")
                !connected && settingsState.message.isNotBlank() -> Text(
                    settingsState.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (connected) {
                SettingsActionButton(
                    onClick = onDisconnect,
                    text = "断开主控",
                    icon = Icons.Outlined.LinkOff,
                    color = Color(0xFFC62828),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (settingsState.savedDevice != null) {
                SettingsActionButton(
                    onClick = onConnectSaved,
                    enabled = canConnect,
                    text = "连接已保存设备",
                    icon = Icons.Outlined.Link,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!connected) {
                SettingsActionButton(
                    onClick = onScanBluetooth,
                    enabled = canConnect && !settingsState.discovering,
                    text = if (settingsState.discovering) "正在扫描..." else "扫描设备",
                    icon = Icons.Outlined.Search,
                    color = Color(0xFF1565C0),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!canUseBluetooth || (!connected && settingsState.savedDevice == null)) {
                SettingsActionButton(
                    onClick = onRefreshBluetooth,
                    text = "刷新状态",
                    icon = Icons.Outlined.Refresh,
                    color = Color(0xFF546E7A),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!connected && settingsState.pairedDevices.isNotEmpty()) {
                HorizontalDivider()
                Text("已配对", style = MaterialTheme.typography.titleSmall)
                settingsState.pairedDevices.forEach { device ->
                    BluetoothDeviceRow(
                        device = device,
                        isSaved = settingsState.savedDevice?.address == device.address,
                        canConnect = canConnect,
                        onConnect = { onConnectDevice(device) },
                    )
                }
            }

            if (!connected && (settingsState.discovering || settingsState.discoveredDevices.isNotEmpty())) {
                HorizontalDivider()
                Text("扫描发现", style = MaterialTheme.typography.titleSmall)
                settingsState.discoveredDevices.forEach { device ->
                    BluetoothDeviceRow(
                        device = device,
                        isSaved = settingsState.savedDevice?.address == device.address,
                        canConnect = canConnect,
                        onConnect = { onConnectDevice(device) },
                    )
                }
                if (settingsState.discovering && settingsState.discoveredDevices.isEmpty()) {
                    Text("正在扫描...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ProblemBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFC62828).copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ReportProblem,
                contentDescription = null,
                tint = Color(0xFFC62828),
                modifier = Modifier.size(18.dp),
            )
            Text(text, color = Color(0xFFC62828), style = MaterialTheme.typography.bodyMedium)
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
    val latestAppIsNewer = updateState.latestVersionName?.let { isVersionNewer(it, BuildConfig.VERSION_NAME) } == true
    val showAppUpdate = updateState.appUpdateAvailable && updateState.appDownloadUrl != null && latestAppIsNewer
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
            VersionSummaryRow(
                appVersion = BuildConfig.VERSION_NAME,
                esp32Version = updateState.currentEsp32FirmwareVersion ?: "--",
            )
            if (showAppUpdate || showTargetEsp32) {
                VersionSummaryRow(
                    appVersion = if (showAppUpdate) updateState.latestVersionName ?: "--" else "--",
                    esp32Version = if (showTargetEsp32) updateState.targetEsp32FirmwareVersion ?: "--" else "--",
                    label = "可更新",
                )
            }
            if (busy || showAppUpdate || showEsp32Update) {
                Text(updateState.message, color = MaterialTheme.colorScheme.primary)
            }
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
private fun VersionSummaryRow(
    appVersion: String,
    esp32Version: String,
    label: String = "当前",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VersionChip(
            label = "$label App",
            value = appVersion,
            color = Color(0xFF1565C0),
            modifier = Modifier.weight(1f),
        )
        VersionChip(
            label = "$label 主控",
            value = esp32Version,
            color = Color(0xFFE65100),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VersionChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.10f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SafetySettingsCard(
    controlState: ControlUiState,
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
    onNavigationGpsSourceChange: (NavigationGpsSource) -> Unit,
    onAutoNavigationGpsJumpResetChange: (Int) -> Unit,
    onUsePhoneHeadingChange: (Boolean) -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "控制",
                icon = Icons.Outlined.Security,
                color = Color(0xFFC62828),
            )
            SettingsRow(
                "航向源",
                if (settingsState.usePhoneHeading) "手机指南针" else "主控 IMU",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImuModeButton(
                    text = "手机指南针",
                    selected = settingsState.usePhoneHeading,
                    onClick = { onUsePhoneHeadingChange(true) },
                    modifier = Modifier.weight(1f),
                )
                ImuModeButton(
                    text = "主控 IMU",
                    selected = !settingsState.usePhoneHeading,
                    onClick = { onUsePhoneHeadingChange(false) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "只影响船头航向、锁航和导航方向。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsRow(
                "导航 GPS 来源",
                when (settingsState.navigationGpsSource) {
                    NavigationGpsSource.Esp32 -> "ESP32 GPS"
                    NavigationGpsSource.Phone -> "手机 GPS"
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ImuModeButton(
                    text = "ESP32 GPS",
                    selected = settingsState.navigationGpsSource == NavigationGpsSource.Esp32,
                    onClick = { onNavigationGpsSourceChange(NavigationGpsSource.Esp32) },
                    modifier = Modifier.weight(1f),
                )
                ImuModeButton(
                    text = "手机 GPS",
                    selected = settingsState.navigationGpsSource == NavigationGpsSource.Phone,
                    onClick = { onNavigationGpsSourceChange(NavigationGpsSource.Phone) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "只影响导航页实时位置和自动导航坐标。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PercentStepperRow(
                label = "油门上限",
                value = settingsState.maxThrottlePercent,
                minValue = 5,
                maxValue = 100,
                step = 5,
                onValueChange = onMaxThrottleChange,
            )
            DegreeStepperRow(
                label = "锁航容差",
                value = settingsState.headingLockToleranceDegrees,
                minValue = 1,
                maxValue = 20,
                step = 1,
                onValueChange = onHeadingLockToleranceChange,
            )

            ExpandToggleRow(
                title = "高级控制参数",
                expanded = showAdvanced,
                onClick = { showAdvanced = !showAdvanced },
            )
            if (showAdvanced) {
                SwitchRow(
                    label = "启动后自动连接",
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
                PercentStepperRow(
                    label = "声控功率限制",
                    value = settingsState.voicePowerLimitPercent,
                    minValue = 5,
                    maxValue = 100,
                    step = 5,
                    onValueChange = onVoicePowerLimitChange,
                )
                PercentStepperRow(
                    label = "微调步进",
                    value = settingsState.fineTuneStepPercent,
                    minValue = 1,
                    maxValue = 10,
                    step = 1,
                    onValueChange = onFineTuneStepChange,
                )
                DegreeStepperRow(
                    label = "满修正角度",
                    value = settingsState.headingLockFullCorrectionDegrees,
                    minValue = 5,
                    maxValue = 180,
                    step = 5,
                    onValueChange = onHeadingLockFullCorrectionChange,
                )
                PercentStepperRow(
                    label = "空档最小转向差",
                    value = settingsState.headingLockNeutralPivotMinDifferencePercent,
                    minValue = 20,
                    maxValue = 100,
                    step = 5,
                    onValueChange = onHeadingLockNeutralPivotMinDifferenceChange,
                )
                PercentStepperRow(
                    label = "空档最大转向差",
                    value = settingsState.headingLockNeutralPivotMaxDifferencePercent,
                    minValue = 0,
                    maxValue = 100,
                    step = 5,
                    onValueChange = onHeadingLockNeutralPivotMaxDifferenceChange,
                )
                MeterStepperRow(
                    label = "GPS 跳变重置",
                    value = settingsState.autoNavigationGpsJumpResetMeters,
                    minValue = 3,
                    maxValue = 30,
                    step = 1,
                    onValueChange = onAutoNavigationGpsJumpResetChange,
                )
                SettingsRow("保护", "上电锁定 · 失联回空挡 · 急停锁定")
            }
        }
    }
}

@Composable
private fun DegreeStepperRow(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    NumericStepperRow(
        label = label,
        value = value,
        valueText = "${value}°",
        minValue = minValue,
        maxValue = maxValue,
        step = step,
        onValueChange = onValueChange,
    )
}

@Composable
private fun PercentStepperRow(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    NumericStepperRow(
        label = label,
        value = value,
        valueText = "${value}%",
        minValue = minValue,
        maxValue = maxValue,
        step = step,
        onValueChange = onValueChange,
    )
}

@Composable
private fun MeterStepperRow(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    NumericStepperRow(
        label = label,
        value = value,
        valueText = "${value}m",
        minValue = minValue,
        maxValue = maxValue,
        step = step,
        onValueChange = onValueChange,
    )
}

@Composable
private fun NumericStepperRow(
    label: String,
    value: Int,
    valueText: String,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    val decreaseValue = (value - step).coerceAtLeast(minValue)
    val increaseValue = (value + step).coerceAtMost(maxValue)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, fontWeight = FontWeight.Medium)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StepperButton(
                text = "-$step",
                enabled = value > minValue,
                onClick = { onValueChange(decreaseValue) },
                modifier = Modifier.weight(1f),
            )
            StepperButton(
                text = "+$step",
                enabled = value < maxValue,
                onClick = { onValueChange(increaseValue) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Esp32ImuObservationCard(
    controlState: ControlUiState,
    onStartYbImuCalibration: () -> Unit,
    onStartYbMagCalibration: () -> Unit,
) {
    if (controlState.connectionState != ConnectionState.Connected) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsSectionHeader(
                    title = "主控 IMU",
                    icon = Icons.Outlined.Settings,
                    color = Color(0xFF455A64),
                )
                ProblemBanner("连接主控后显示 IMU 数据。")
            }
        }
        return
    }

    var showRaw by remember { mutableStateOf(false) }
    val isConnected = controlState.connectionState == ConnectionState.Connected
    val fields = if (isConnected) controlState.telemetry.statusFields else emptyMap()
    val telemetry = controlState.telemetry
    val ybImuOnline = isConnected && telemetry.ybImuAvailable == true

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "主控 IMU",
                icon = Icons.Outlined.Settings,
                color = Color(0xFF455A64),
            )
            if (!ybImuOnline) {
                ProblemBanner("主控未上报 YB IMU。")
                return@Column
            }
            SettingsRow(
                "欧拉角",
                "Yaw ${telemetry.ybYawDegrees.normalizedDegreesText()} · Roll ${telemetry.ybRollDegrees.degreesText()} · Pitch ${telemetry.ybPitchDegrees.degreesText()}",
            )
            SettingsRow(
                "磁力计",
                "${telemetry.ybMagXUt.microTeslaText()} / ${telemetry.ybMagYUt.microTeslaText()} / ${telemetry.ybMagZUt.microTeslaText()}",
            )
            SettingsRow("YB IMU 校准", ybCalibrationStateText(telemetry.ybImuCalibrationState))
            SettingsRow("YB 磁校准", ybCalibrationStateText(telemetry.ybMagCalibrationState))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsActionButton(
                    text = "校准 IMU",
                    icon = Icons.Outlined.Tune,
                    color = Color(0xFF455A64),
                    onClick = onStartYbImuCalibration,
                    enabled = ybImuOnline,
                    modifier = Modifier.weight(1f),
                )
                SettingsActionButton(
                    text = "校准磁力计",
                    icon = Icons.Outlined.Explore,
                    color = Color(0xFF455A64),
                    onClick = onStartYbMagCalibration,
                    enabled = ybImuOnline,
                    modifier = Modifier.weight(1f),
                )
            }
            ExpandToggleRow(
                title = "原始数据",
                expanded = showRaw,
                onClick = { showRaw = !showRaw },
            )
            if (showRaw) {
                SettingsRow("YBY yaw 原始", telemetry.ybYawDegrees.degreesText())
                SettingsRow("YBQ yaw 0-360", quaternionYawText(telemetry.ybQuatW, telemetry.ybQuatX, telemetry.ybQuatY, telemetry.ybQuatZ))
                SettingsRow("加速度", "${telemetry.ybAccelXG.gText()} / ${telemetry.ybAccelYG.gText()} / ${telemetry.ybAccelZG.gText()}")
                SettingsRow("Z 角速度", telemetry.ybGyroZRadS.radPerSecondText())
                SettingsRow("XY 磁向", rawMagneticXyHeadingText(telemetry.ybMagXUt, telemetry.ybMagYUt))
                SettingsRow("校准目标", fields["YBCACT"] ?: "--")
                SettingsRow("校准原始状态", fields["YBCRAW"] ?: "--")
                SettingsRow("校准读失败", fields["YBCFAIL"] ?: "--")
                SettingsRow("四元数", "${telemetry.ybQuatW.quaternionText()} / ${telemetry.ybQuatX.quaternionText()} / ${telemetry.ybQuatY.quaternionText()} / ${telemetry.ybQuatZ.quaternionText()}")
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
            GearPercentMultiSlider(
                gearPercents = settingsState.gearPercents,
                onGearThrottleChange = onGearThrottleChange,
            )
        }
    }
}

@Composable
private fun GearPercentMultiSlider(
    gearPercents: Map<ThrottleGear, Int>,
    onGearThrottleChange: (ThrottleGear, Int) -> Unit,
) {
    val gears = remember { ThrottleGear.entries.filterNot { it == ThrottleGear.Neutral } }
    var activeGear by remember { mutableStateOf<ThrottleGear?>(null) }
    var draftValues by remember { mutableStateOf<Map<ThrottleGear, Int>>(emptyMap()) }
    val displayPercents = gearPercents + draftValues
    val latestDisplayPercents = rememberUpdatedState(displayPercents)
    val latestDraftValues = rememberUpdatedState(draftValues)
    val reverseColor = Color(0xFF1E88E5)
    val forwardColor = Color(0xFF2E7D32)
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val valuePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val cardColor = MaterialTheme.colorScheme.background.toArgb()
    val minPercent = -100f
    val maxPercent = 100f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(gearPercents) {
                var draggingGear: ThrottleGear? = null

                fun valueFromX(x: Float, width: Float): Int {
                    val horizontalPadding = 6.dp.toPx()
                    val trackStart = horizontalPadding
                    val trackEnd = width - horizontalPadding
                    val ratio = ((x - trackStart) / (trackEnd - trackStart)).coerceIn(0f, 1f)
                    return ((minPercent + ratio * (maxPercent - minPercent)) / 5f).roundToInt() * 5
                }

                fun xFromValue(value: Int, width: Float): Float {
                    val horizontalPadding = 6.dp.toPx()
                    val trackStart = horizontalPadding
                    val trackEnd = width - horizontalPadding
                    val ratio = (value - minPercent) / (maxPercent - minPercent)
                    return trackStart + ratio.coerceIn(0f, 1f) * (trackEnd - trackStart)
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        draggingGear = gears.minByOrNull { gear ->
                            val value = latestDisplayPercents.value[gear] ?: gear.defaultThrottlePercent
                            kotlin.math.abs(xFromValue(value, size.width.toFloat()) - offset.x)
                        }
                        activeGear = draggingGear
                    },
                    onDragEnd = {
                        val gear = draggingGear
                        val value = gear?.let { latestDraftValues.value[it] }
                        if (gear != null && value != null) {
                            onGearThrottleChange(gear, value)
                        }
                        draggingGear = null
                        activeGear = null
                        draftValues = emptyMap()
                    },
                    onDragCancel = {
                        draggingGear = null
                        activeGear = null
                        draftValues = emptyMap()
                    },
                    onDrag = { change, _ ->
                        val gear = draggingGear ?: return@detectDragGestures
                        val range = gear.valueRange()
                        val nextValue = valueFromX(change.position.x, size.width.toFloat())
                            .coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt())
                        draftValues = draftValues + (gear to nextValue)
                    },
                )
            },
    ) {
        val horizontalPadding = 6.dp.toPx()
        val trackStart = horizontalPadding
        val trackEnd = size.width - horizontalPadding
        val trackY = 148.dp.toPx()
        val trackHeight = 18.dp.toPx()
        val tickHeight = 26.dp.toPx()
        val handleRadius = 18.dp.toPx()
        val activeRadius = 24.dp.toPx()
        val labelTextSize = 12.sp.toPx()
        val valueTextSize = 14.sp.toPx()
        val activeValueTextSize = 20.sp.toPx()
        val chipTop = 14.dp.toPx()
        val chipHeight = 56.dp.toPx()
        val bubbleTop = 78.dp.toPx()
        val bubbleWidth = 72.dp.toPx()
        val bubbleHeight = 58.dp.toPx()

        fun xFromValue(value: Int): Float {
            val ratio = (value - minPercent) / (maxPercent - minPercent)
            return trackStart + ratio.coerceIn(0f, 1f) * (trackEnd - trackStart)
        }

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(trackStart, trackY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(trackEnd - trackStart, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f),
        )
        drawRoundRect(
            color = reverseColor.copy(alpha = 0.28f),
            topLeft = Offset(trackStart, trackY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(xFromValue(0) - trackStart, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f),
        )
        drawRoundRect(
            color = forwardColor.copy(alpha = 0.28f),
            topLeft = Offset(xFromValue(0), trackY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(trackEnd - xFromValue(0), trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f),
        )
        drawLine(
            color = neutralColor.copy(alpha = 0.55f),
            start = Offset(xFromValue(0), trackY - tickHeight),
            end = Offset(xFromValue(0), trackY + tickHeight),
            strokeWidth = 2.dp.toPx(),
        )

        val chipGap = 4.dp.toPx()
        val chipCount = gears.size
        val chipWidth = (size.width - chipGap * (chipCount - 1)) / chipCount
        labelPaint.textSize = labelTextSize
        valuePaint.textSize = valueTextSize
        gears.forEachIndexed { index, gear ->
            val value = displayPercents[gear] ?: gear.defaultThrottlePercent
            val color = if (gear.isReverse) reverseColor else forwardColor
            val left = index * (chipWidth + chipGap)
            drawRoundRect(
                color = color.copy(alpha = if (activeGear == gear) 0.18f else 0.10f),
                topLeft = Offset(left, chipTop),
                size = androidx.compose.ui.geometry.Size(chipWidth, chipHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.apply {
                val center = left + chipWidth / 2f
                drawText(gear.shortLabel(), center, chipTop + 21.dp.toPx(), labelPaint.apply { this.color = color.toArgb() })
                drawText(value.signedPercentText(), center, chipTop + 45.dp.toPx(), valuePaint.apply { this.color = textColor })
            }
        }

        gears.forEach { gear ->
            val value = displayPercents[gear] ?: gear.defaultThrottlePercent
            val x = xFromValue(value)
            val color = if (gear.isReverse) reverseColor else forwardColor
            val selected = activeGear == gear
            val shortLabel = gear.shortLabel()
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = Offset(x, trackY - tickHeight / 1.4f),
                end = Offset(x, trackY + tickHeight / 1.4f),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = Color.White,
                radius = if (selected) activeRadius + 5.dp.toPx() else handleRadius + 5.dp.toPx(),
                center = Offset(x, trackY),
            )
            drawCircle(
                color = color,
                radius = if (selected) activeRadius else handleRadius,
                center = Offset(x, trackY),
            )
            if (selected) {
                val bubbleLeft = (x - bubbleWidth / 2f).coerceIn(0f, size.width - bubbleWidth)
                drawRoundRect(
                    color = color.copy(alpha = 0.96f),
                    topLeft = Offset(bubbleLeft, bubbleTop),
                    size = androidx.compose.ui.geometry.Size(bubbleWidth, bubbleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                )
                drawContext.canvas.nativeCanvas.apply {
                    drawText(shortLabel, bubbleLeft + bubbleWidth / 2f, bubbleTop + 22.dp.toPx(), labelPaint.apply {
                        textSize = 12.sp.toPx()
                        this.color = surfaceColor
                    })
                    drawText(value.signedPercentText(), bubbleLeft + bubbleWidth / 2f, bubbleTop + 48.dp.toPx(), valuePaint.apply {
                        textSize = activeValueTextSize
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        this.color = surfaceColor
                    })
                    valuePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    valuePaint.textSize = valueTextSize
                    labelPaint.textSize = labelTextSize
                }
            }
        }
        drawContext.canvas.nativeCanvas.apply {
            valuePaint.textSize = 12.sp.toPx()
            valuePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            drawText("-100%", trackStart, 198.dp.toPx(), valuePaint.apply { color = mutedTextColor; textAlign = Paint.Align.LEFT })
            drawText("0%", xFromValue(0), 198.dp.toPx(), valuePaint.apply { color = mutedTextColor; textAlign = Paint.Align.CENTER })
            drawText("+100%", trackEnd, 198.dp.toPx(), valuePaint.apply { color = mutedTextColor; textAlign = Paint.Align.RIGHT })
            valuePaint.textAlign = Paint.Align.CENTER
        }
    }
}

@Composable
private fun ExpandToggleRow(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun GearStepperRow(
    gear: ThrottleGear,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val range = gear.valueRange()
    NumericStepperRow(
        label = gear.label,
        value = value,
        valueText = value.signedPercentText(),
        minValue = range.start.roundToInt(),
        maxValue = range.endInclusive.roundToInt(),
        step = 5,
        onValueChange = onValueChange,
    )
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

private fun Float?.degreesText(): String {
    return this?.let { "%.1f°".format(it) } ?: "--"
}

private fun Float?.normalizedDegreesText(): String {
    return this?.let { "%.1f°".format(normalizeCompassDegrees(it)) } ?: "--"
}

private fun Float?.gText(): String {
    return this?.let { "%.3f g".format(it) } ?: "--"
}

private fun Float?.radPerSecondText(): String {
    return this?.let { "%.4f rad/s".format(it) } ?: "--"
}

private fun Float?.microTeslaText(): String {
    return this?.let { "%.2f uT".format(it) } ?: "--"
}

private fun rawMagneticXyHeadingText(xUt: Float?, yUt: Float?): String {
    if (xUt == null || yUt == null) {
        return "--"
    }
    val heading = normalizeCompassDegrees(Math.toDegrees(atan2(yUt.toDouble(), xUt.toDouble())).toFloat())
    return "%.1f°".format(heading)
}

private fun ybCalibrationStateText(state: Int?): String {
    return when (state) {
        null -> "--"
        1 -> "已校准"
        250 -> "校准中"
        254 -> "校准超时"
        255 -> "本次上电未知"
        0 -> "未完成/失败"
        else -> "失败码 $state"
    }
}

private fun quaternionYawText(w: Float?, x: Float?, y: Float?, z: Float?): String {
    if (w == null || x == null || y == null || z == null) {
        return "--"
    }
    val sinyCosp = 2.0 * (w * z + x * y)
    val cosyCosp = 1.0 - 2.0 * (y * y + z * z)
    val yaw = normalizeCompassDegrees(Math.toDegrees(atan2(sinyCosp, cosyCosp)).toFloat())
    return "%.1f°".format(yaw)
}

private fun Float?.quaternionText(): String {
    return this?.let { "%.4f".format(it) } ?: "--"
}

@Composable
private fun StepperButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (enabled) 0.12f else 0.06f),
        modifier = modifier,
    ) {
        Text(
            text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ImuModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) Color(0xFF00695C) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.16f else 0.06f),
        modifier = modifier,
    ) {
        Text(
            if (selected) "已选 $text" else text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
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

private fun ThrottleGear.shortLabel(): String {
    return when (this) {
        ThrottleGear.Reverse3 -> "后3"
        ThrottleGear.Reverse2 -> "后2"
        ThrottleGear.Reverse1 -> "后1"
        ThrottleGear.Neutral -> "空"
        ThrottleGear.Forward1 -> "前1"
        ThrottleGear.Forward2 -> "前2"
        ThrottleGear.Forward3 -> "前3"
        ThrottleGear.Forward4 -> "前4"
    }
}

private fun isVersionNewer(candidate: String, current: String): Boolean {
    val candidateParts = candidate.versionParts()
    val currentParts = current.versionParts()
    val maxSize = maxOf(candidateParts.size, currentParts.size)
    for (index in 0 until maxSize) {
        val next = candidateParts.getOrElse(index) { 0 }
        val base = currentParts.getOrElse(index) { 0 }
        if (next != base) {
            return next > base
        }
    }
    return false
}

private fun String.versionParts(): List<Int> {
    return trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '_')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
