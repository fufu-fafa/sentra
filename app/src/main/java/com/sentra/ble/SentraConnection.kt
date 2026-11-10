package com.sentra.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATT client for one sleeve (Bab 8), from fall-bracelet's BraceletConnection. Android allows only
 * one outstanding GATT operation at a time, so every read/write goes through [op], which waits for
 * the matching callback.
 *
 * Once [connect] is called the connection is kept alive: after a drop it reconnects with
 * autoConnect, which waits in the background until the sleeve is back in range (the firmware
 * advertises again 300 ms after a disconnect). Turning Bluetooth off kills that pending
 * connection, so it reconnects again when Bluetooth comes back.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before connecting.
class SentraConnection(private val context: Context, private val scope: CoroutineScope) : SleeveLink {

    private val _link = MutableStateFlow(LinkState.DISCONNECTED)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _messages = MutableSharedFlow<SleeveMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<SleeveMessage> = _messages.asSharedFlow()

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var address: String? = null
    private var setupJob: Job? = null

    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null

    /** Bluetooth off drops the link and any pending autoConnect; on again starts a fresh one. */
    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth off")
                    setupJob?.cancel()
                    pending?.complete(null)
                    runCatching { gatt?.close() }
                    gatt = null
                    if (address != null) _link.value = LinkState.CONNECTING
                }
                BluetoothAdapter.STATE_ON -> if (address != null && gatt == null) {
                    Log.i(TAG, "Bluetooth on, reconnecting")
                    open(autoConnect = false)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun connect(address: String) {
        if (this.address == address && gatt != null) return
        disconnect()
        this.address = address
        open(autoConnect = false)
    }

    fun disconnect() {
        address = null
        setupJob?.cancel()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        pending?.complete(null)
        _link.value = LinkState.DISCONNECTED
    }

    private fun open(autoConnect: Boolean) {
        val addr = address ?: return
        _link.value = LinkState.CONNECTING
        // With Bluetooth off connectGatt fails quietly; the adapter receiver opens again on STATE_ON.
        if (adapter?.isEnabled != true) return
        val device: BluetoothDevice = adapter.getRemoteDevice(addr)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Writes the command text to CMD and waits for the sleeve to confirm the write. */
    override suspend fun send(command: SleeveCommand): Boolean {
        if (_link.value != LinkState.CONNECTED) return false
        val ch = characteristic(SentraProtocol.CMD) ?: return false
        return op(WRITE_TIMEOUT_MS) { writeCharacteristic(it, ch, SentraProtocol.encode(command)) } != null
    }

    // --- Setup after connecting -----------------------------------------------------------

    private suspend fun setUp(g: BluetoothGatt) {
        if (op(timeoutMs = 15_000) { g.discoverServices() } == null) return fail(g, "service discovery")
        if (g.getService(SentraProtocol.SERVICE) == null) return fail(g, "SENTRA service not found")
        // Notifications only after the connection is up, the rule App Inventor's RegisterForStrings has too (§8.3).
        if (!subscribe(g, SentraProtocol.STATUS)) return fail(g, "subscribe to STATUS")
        _link.value = LinkState.CONNECTED
    }

    private fun fail(g: BluetoothGatt, what: String) {
        Log.w(TAG, "Setup failed: $what")
        g.disconnect() // onConnectionStateChange schedules the reconnect
    }

    private suspend fun subscribe(g: BluetoothGatt, uuid: java.util.UUID): Boolean {
        val ch = characteristic(uuid) ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(SentraProtocol.CCCD) ?: return false
        return op { writeDescriptor(it, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) } != null
    }

    private fun characteristic(uuid: java.util.UUID): BluetoothGattCharacteristic? =
        gatt?.getService(SentraProtocol.SERVICE)?.getCharacteristic(uuid)

    /** Starts one GATT operation and suspends until its callback; null on failure or timeout. */
    private suspend fun op(timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): ByteArray? =
        opLock.withLock {
            val g = gatt ?: return null
            val done = CompletableDeferred<ByteArray?>()
            pending = done
            try {
                if (!start(g)) return null
                withTimeoutOrNull(timeoutMs) { done.await() }
            } finally {
                pending = null
            }
        }

    private fun complete(status: Int, value: ByteArray = OK) {
        pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
    }

    // --- API 33 changed the write signatures; support both -----------------------------------

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun onValue(uuid: java.util.UUID, value: ByteArray) {
        if (uuid == SentraProtocol.STATUS) _messages.tryEmit(SentraProtocol.parse(value))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setupJob = scope.launch { setUp(g) }
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            Log.i(TAG, "Disconnected, status $status")
            setupJob?.cancel()
            pending?.complete(null)
            g.close()
            gatt = null
            if (address == null) {
                _link.value = LinkState.DISCONNECTED
                return
            }
            // Keep trying: autoConnect waits for the sleeve without draining the battery.
            _link.value = LinkState.CONNECTING
            scope.launch {
                delay(1_000)
                if (address != null && gatt == null) open(autoConnect = true)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            complete(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            onValue(ch.uuid, value)

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onValue(ch.uuid, ch.value ?: return)
        }
    }

    private companion object {
        const val TAG = "SentraConnection"
        const val WRITE_TIMEOUT_MS = 3_000L
        val OK = ByteArray(0)
    }
}
