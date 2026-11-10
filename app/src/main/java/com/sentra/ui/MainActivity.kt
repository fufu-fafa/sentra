package com.sentra.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.sentra.care
import com.sentra.data.AppMode
import com.sentra.ui.theme.SentraTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.retryConnect()
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.retryConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SentraTheme {
                App(
                    vm,
                    askPermissions = { mode -> permissions.launch(requiredPermissions(mode)) },
                    enableBluetooth = {
                        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
                )
            }
        }
    }

    // SENTRA Care keeps its live connection only while this is on screen; push covers the rest.
    override fun onStart() {
        super.onStart()
        care.setVisible(true)
    }

    override fun onStop() {
        super.onStop()
        care.setVisible(false)
    }

    /** A parent's phone needs neither Bluetooth nor location. */
    private fun requiredPermissions(mode: AppMode): Array<String> = buildList {
        if (mode == AppMode.CHILD) {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
