package com.smartsup.controller.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.smartsup.controller.control.ControlViewModel
import com.smartsup.controller.model.AppTab

@Composable
fun AppScreen(viewModel: ControlViewModel) {
    val controlState by viewModel.uiState.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Control) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.Navigation -> Icons.Outlined.Explore
                                    AppTab.Control -> Icons.Outlined.Tune
                                    AppTab.Settings -> Icons.Outlined.Settings
                                },
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { paddingValues ->
        val modifier = Modifier.padding(paddingValues)
        when (selectedTab) {
            AppTab.Navigation -> NavigationScreen(modifier = modifier)
            AppTab.Control -> ControlScreen(
                state = controlState,
                maxThrottlePercent = settingsState.maxThrottlePercent,
                gearPercents = settingsState.gearPercents,
                rampLimitEnabled = settingsState.rampLimitEnabled,
                modifier = modifier,
                onArm = { viewModel.setArmed(true) },
                onDisarm = { viewModel.setArmed(false) },
                onLeftThrottleChange = viewModel::setLeftThrottle,
                onRightThrottleChange = viewModel::setRightThrottle,
                onLeftThrottleRelease = viewModel::returnLeftThrottleToGear,
                onRightThrottleRelease = viewModel::returnRightThrottleToGear,
                onGearSelected = viewModel::setThrottleGear,
                onEmergencyStop = viewModel::emergencyStop,
            )
            AppTab.Settings -> SettingsScreen(
                controlState = controlState,
                settingsState = settingsState,
                updateState = updateState,
                modifier = modifier,
                onRefreshBluetooth = viewModel::refreshBluetoothDevices,
                onScanBluetooth = viewModel::startBluetoothDiscovery,
                onConnectSaved = viewModel::connectSavedBluetooth,
                onConnectDevice = viewModel::connectBluetooth,
                onDisconnect = viewModel::disconnectBluetooth,
                onAutoReconnectChange = viewModel::setAutoReconnect,
                onMaxThrottleChange = viewModel::setMaxThrottlePercent,
                onGearThrottleChange = viewModel::setGearThrottlePercent,
                onRampLimitChange = viewModel::setRampLimitEnabled,
                onLeftEscReversedChange = viewModel::setLeftEscReversed,
                onRightEscReversedChange = viewModel::setRightEscReversed,
                onCheckUpdates = viewModel::checkForUpdates,
                onInstallAppUpdate = viewModel::installLatestAppUpdate,
                onUpdateEsp32FromGitHub = viewModel::downloadAndUploadLatestEsp32Firmware,
                onUploadLocalEsp32Firmware = viewModel::uploadLocalEsp32Firmware,
            )
        }
    }
}
