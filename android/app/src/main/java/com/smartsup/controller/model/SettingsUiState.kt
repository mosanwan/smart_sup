package com.smartsup.controller.model

data class SettingsUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val pairedDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discoveredDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discovering: Boolean = false,
    val savedDevice: BluetoothDeviceInfo? = null,
    val autoReconnect: Boolean = true,
    val maxThrottlePercent: Int = 30,
    val gearPercents: Map<ThrottleGear, Int> = ThrottleGear.defaultPercents(),
    val leftEscReversed: Boolean = false,
    val rightEscReversed: Boolean = false,
    val rampLimitEnabled: Boolean = true,
    val githubToken: String = "",
    val deviceNamePrefix: String = "SmartSUP-",
    val message: String = "在 App 内扫描并连接 SmartSUP ESP32 设备",
)
