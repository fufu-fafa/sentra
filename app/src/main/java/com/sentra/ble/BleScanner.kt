package com.sentra.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/**
 * Finds sleeves: anything advertising the SENTRA service or a name starting with SENTRA (§9.3,
 * ConnectMatchingName). [exactName] narrows it to one sleeve, e.g. SENTRA-01 at the competition
 * where several are switched on in one room.
 */
@SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_SCAN before scanning.
class BleScanner(private val context: Context) {

    fun scan(exactName: String = ""): Flow<FoundDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            close(IllegalStateException("Bluetooth mati"))
            return@callbackFlow
        }
        val service = ParcelUuid(SentraProtocol.SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (matches(name, result.scanRecord?.serviceUuids?.contains(service) == true, exactName)) {
                    trySend(FoundDevice(result.device.address, name ?: SentraProtocol.DEVICE_NAME_PREFIX, result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Pencarian gagal ($errorCode)"))
            }
        }
        // No ScanFilter: the name sits in the scan response and a filter can only match it exactly.
        // The scan runs for a few seconds after a button press, so an unfiltered one is fine.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        fun matches(name: String?, hasService: Boolean, exactName: String): Boolean {
            val wanted = exactName.trim()
            if (wanted.isNotEmpty()) return name.equals(wanted, ignoreCase = true)
            return hasService || name?.startsWith(SentraProtocol.DEVICE_NAME_PREFIX, ignoreCase = true) == true
        }
    }
}
