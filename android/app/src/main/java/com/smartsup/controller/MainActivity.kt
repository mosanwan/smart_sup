package com.smartsup.controller

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import com.smartsup.controller.control.ControlViewModel
import com.smartsup.controller.ui.AppScreen
import com.smartsup.controller.ui.theme.SmartSupTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ControlViewModel by viewModels()
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            true
        }
        viewModel.setBluetoothPermissionGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissionsIfNeeded()

        setContent {
            SmartSupTheme {
                AppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBluetoothDevices()
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            )
        } else {
            viewModel.setBluetoothPermissionGranted(true)
        }
    }
}
