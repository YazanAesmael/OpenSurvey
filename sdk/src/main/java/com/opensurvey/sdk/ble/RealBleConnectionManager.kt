package com.opensurvey.sdk.ble

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
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.opensurvey.sdk.di.ApplicationScope
import com.opensurvey.sdk.interfaces.BleConnectionManager
import com.opensurvey.sdk.interfaces.HardwareConnectionState
import com.opensurvey.sdk.models.ConnectableDevice
import com.opensurvey.sdk.utils.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("MissingPermission")
@Singleton
class RealBleConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val debugLogger: DebugLogger
) : BleConnectionManager {

    companion object Companion {
        private const val TAG = "BleConnectionManager"
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val CONNECT_DISCOVERY_DELAY_MS = 150L
        private const val POST_CCCD_NO_BYTES_FALLBACK_MS = 2_000L
        private const val BT_WARM_UP_MS = 1_500L
        private const val EARLY_133_RETRY_WINDOW_MS = 3_000L
        private const val MAX_LINE_BUFFER = 8192

        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bm = context.getSystemService(BluetoothManager::class.java)
        bm?.adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var fallbackJob: Job? = null
    private var mtuFallbackJob: Job? = null

    private var writeDeferred: CompletableDeferred<Boolean>? = null

    // MTU book-keeping
    private var currentMtu: Int = 23
    private var bytesSinceCccd: Int = 0

    // Bluetooth state tracking
    private var lastBtTurnedOnAt: Long = 0L
    private var pendingWarmupRetry = false
    private var connectAttemptCount = 0
    private var lastConnectAddress: String? = null

    private enum class NotifyMode { NONE, NOTIFY, INDICATE }
    private var lastNotifyMode: NotifyMode = NotifyMode.NONE

    private val _scanResults = MutableStateFlow<List<ConnectableDevice>>(emptyList())
    override val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow<HardwareConnectionState>(HardwareConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val lineBuilder = StringBuilder()
    private val _incomingLines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val incomingLines = _incomingLines.asSharedFlow()

    // Correlate sessions
    private var connSeq = 0
    private var connTag = ""

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            debugLogger.log(
                TAG,
                "onConnectionStateChange: Address=${gatt.device.address}, Status=$status (${gattStatusToString(status)}), NewState=$newState (${profileStateToString(newState)})"
            )

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (status == 133 && withinEarlyWarmupWindow() && connectAttemptCount == 1) {
                    debugLogger.log(TAG, "Early GATT 133 during warm-up; will auto-retry once.")
                    scheduleWarmReconnect("early-133")
                    return
                }
                _connectionState.value = HardwareConnectionState.Error("Connection failed with status: $status")
                closeConnection()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val tag = newConnTag()
                debugLogger.log(TAG, "$tag CONNECTED to ${gatt.device.address}")

                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Exception) {}

                _connectionState.value = HardwareConnectionState.Connecting

                mainHandler.postDelayed({
                    debugLogger.log(TAG, "Discovering services...")
                    gatt.discoverServices()
                }, CONNECT_DISCOVERY_DELAY_MS)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                debugLogger.log(TAG, "$connTag DISCONNECTED")
                closeConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            debugLogger.log(TAG, "onServicesDiscovered: Status=$status (${gattStatusToString(status)})")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (status == 133 && withinEarlyWarmupWindow() && connectAttemptCount <= 1) {
                    debugLogger.log(TAG, "Service discovery 133 during warm-up; auto-retry once.")
                    scheduleWarmReconnect("services-133")
                    return
                }
                _connectionState.value = HardwareConnectionState.Error("Service discovery failed.")
                closeConnection()
                return
            }

            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                _connectionState.value = HardwareConnectionState.Error("Nordic UART Service not found.")
                closeConnection(); return
            }
            debugLogger.log(TAG, "Found NUS Service: ${service.uuid}")

            val tx = service.getCharacteristic(NUS_TX_UUID)
                ?: service.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                }
            val rx = service.getCharacteristic(NUS_RX_UUID)
                ?: service.characteristics.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }

            if (tx == null || rx == null) {
                _connectionState.value = HardwareConnectionState.Error("NUS characteristics not found.")
                closeConnection(); return
            }
            txCharacteristic = tx
            rxCharacteristic = rx

            val notify = (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val indicate = (tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            debugLogger.log(
                TAG,
                "TX=${tx.uuid} props=${tx.properties} (notify=$notify, indicate=$indicate), RX=${rx.uuid} props=${rx.properties}"
            )

            try {
                val ok = gatt.requestMtu(247)
                debugLogger.log(TAG, "requestMtu(247) -> $ok")
                if (ok) {
                    mtuFallbackJob?.cancel()
                    mtuFallbackJob = externalScope.launch(Dispatchers.Main) {
                        delay(800)
                        val stillConnecting = _connectionState.value is HardwareConnectionState.Connecting
                        val sameGatt = this@RealBleConnectionManager.gatt === gatt
                        if (isActive && stillConnecting && sameGatt) {
                            debugLogger.log(TAG, "MTU callback not received; proceeding to enable notifications.")
                            debugLogger.log(TAG, "$connTag MTU fallback firing (no onMtuChanged in 800ms)")
                            enableNotificationsSequenced(gatt)
                        }
                    }
                } else {
                    enableNotificationsSequenced(gatt)
                }
            } catch (e: Exception) {
                debugLogger.log(TAG, "requestMtu failed: ${e.message}")
                enableNotificationsSequenced(gatt)
            }

        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuFallbackJob?.cancel()
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            debugLogger.log(TAG, "onMtuChanged: mtu=$mtu, status=${gattStatusToString(status)} -> currentMtu=$currentMtu")
            enableNotificationsSequenced(gatt)
        }

        private fun enableNotificationsSequenced(gatt: BluetoothGatt) {
            val tx = txCharacteristic ?: run {
                _connectionState.value = HardwareConnectionState.Error("TX characteristic missing.")
                closeConnection(); return
            }
            val okSet = gatt.setCharacteristicNotification(tx, true)
            debugLogger.log(TAG, "setCharacteristicNotification(tx, true) -> $okSet")
            if (!okSet) {
                _connectionState.value = HardwareConnectionState.Error("Failed to setCharacteristicNotification.")
                closeConnection(); return
            }

            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                _connectionState.value = HardwareConnectionState.Error("CCCD not found on TX.")
                closeConnection(); return
            }

            val (value, mode) = chooseCccdValue(tx)
            lastNotifyMode = mode
            writeCccd(gatt, cccd, value)
            debugLogger.log(TAG, "CCCD write requested with mode=$mode")

            bytesSinceCccd = 0
            fallbackJob?.cancel()
            fallbackJob = externalScope.launch(Dispatchers.IO) {
                delay(POST_CCCD_NO_BYTES_FALLBACK_MS)
                if (bytesSinceCccd == 0) {
                    if (withinEarlyWarmupWindow() && connectAttemptCount <= 1) {
                        debugLogger.log(TAG, "$connTag No bytes after CCCD during warm-up; reconnecting instead of swapping mode.")
                        scheduleWarmReconnect("no-bytes-after-cccd")
                        return@launch
                    }
                    val alt = if (lastNotifyMode == NotifyMode.NOTIFY) NotifyMode.INDICATE else NotifyMode.NOTIFY
                    val altValue = when (alt) {
                        NotifyMode.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        NotifyMode.NOTIFY   -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    debugLogger.log(TAG, "$connTag CCCD no-bytes fallback after ${POST_CCCD_NO_BYTES_FALLBACK_MS}ms (lastMode=$lastNotifyMode) → swap to $alt")
                    writeCccd(gatt, cccd, altValue)
                    lastNotifyMode = alt
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            debugLogger.log(TAG, "onDescriptorWrite: ${descriptor.uuid}, status=${gattStatusToString(status)}")
            if (descriptor.uuid != CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                debugLogger.log(TAG, "Notification descriptor written successfully. CONNECTION IS NOW READY.")
                pendingWarmupRetry = false
                connectAttemptCount = 0
                _connectionState.value = HardwareConnectionState.Connected
            } else {
                if (status == 133 && withinEarlyWarmupWindow() && connectAttemptCount <= 1) {
                    debugLogger.log(TAG, "CCCD write failed with 133 during warm-up; auto-retry once.")
                    scheduleWarmReconnect("cccd-133")
                    return
                }
                _connectionState.value = HardwareConnectionState.Error("Failed to enable notifications.")
                closeConnection()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == rxCharacteristic?.uuid) {
                debugLogger.log(TAG, "$connTag onCharacteristicWrite status=${gattStatusToString(status)}")
                writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                writeDeferred = null
            }
        }

        // Modern
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != txCharacteristic?.uuid) return
            // mark that bytes arrived after CCCD setup
            if (bytesSinceCccd == 0) {
                debugLogger.log(TAG, "$connTag First ${value.size} byte(s) after CCCD")
            }
            bytesSinceCccd += value.size
            appendAndEmitLines(value)
        }

        // Legacy (<33)
        @Deprecated("Used for Android versions prior to 13 (Tiramisu)")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = characteristic.value ?: return
            onCharacteristicChanged(gatt, characteristic, v)
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_ON) {
                        lastBtTurnedOnAt = System.currentTimeMillis()
                        pendingWarmupRetry = false
                        debugLogger.log(TAG, "Bluetooth STATE_ON; starting warm-up window ($BT_WARM_UP_MS ms)")
                        externalScope.launch {
                            delay(BT_WARM_UP_MS)
                            debugLogger.log(TAG, "Warm-up window elapsed.")
                        }
                    }
                }
            }
        }, filter)
    }

    // ===== Public API =====

    override fun startScan() {
        if (bleScanner == null || bluetoothAdapter?.isEnabled == false) {
            if (withinEarlyWarmupWindow()) {
                debugLogger.log(TAG, "Bluetooth just turned on; delaying scan start (warm-up).")
                externalScope.launch { delay(BT_WARM_UP_MS); startScan() }
                return
            }
            val errorMsg = "Bluetooth is not available. Please turn it on."
            debugLogger.log(TAG, "Cannot start scan: $errorMsg")
            _connectionState.value = HardwareConnectionState.Error(errorMsg)
            return
        }
        if (_connectionState.value == HardwareConnectionState.Scanning) return

        scanJob?.cancel()
        scanJob = externalScope.launch {
            debugLogger.log(TAG, "Starting BLE scan for ${SCAN_TIMEOUT_MS / 1000} seconds...")
            _connectionState.value = HardwareConnectionState.Scanning
            _scanResults.value = emptyList()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(null, scanSettings, scanCallback)
            delay(SCAN_TIMEOUT_MS)
            if (isActive) stopScanInternal()
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        stopScanInternal()
    }

    override fun connect(address: String) {
        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled == false) {
            if (withinEarlyWarmupWindow()) {
                debugLogger.log(TAG, "Bluetooth just turned on; delaying connect (warm-up).")
                externalScope.launch(Dispatchers.Main) { delay(BT_WARM_UP_MS); connect(address) }
                return
            }
            _connectionState.value = HardwareConnectionState.Error("Bluetooth is not available.")
            return
        }
        if (_connectionState.value == HardwareConnectionState.Connecting || _connectionState.value == HardwareConnectionState.Connected) {
            return
        }

        stopScan()

        connectJob?.cancel()
        connectJob = externalScope.launch(Dispatchers.Main) {
            _connectionState.value = HardwareConnectionState.Connecting
            try {
                lastConnectAddress = address
                connectAttemptCount++
                val device = bluetoothAdapter!!.getRemoteDevice(address)
                debugLogger.log(TAG, "Attempting to connect to GATT server for device: ${device.name} ($address)")

                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                debugLogger.log(TAG, "Using modern connectGatt with TRANSPORT_LE.")
            } catch (e: Exception) {
                debugLogger.log(TAG, "Connection failed with exception: ${e.message}")
                _connectionState.value = HardwareConnectionState.Error("Failed to connect: ${e.message}")
                closeConnection()
            }
        }
    }

    override fun disconnect() {
        debugLogger.log(TAG, "Disconnect requested for BLE connection.")
        closeConnection()
    }

    override suspend fun sendCommand(command: String) {
        val fullCommand = (command + "\r").toByteArray(StandardCharsets.US_ASCII)
        val rx = rxCharacteristic ?: throw IOException("BLE RX characteristic not available.")
        val gatt = this.gatt ?: throw IOException("BLE GATT not available.")
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rx, fullCommand, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.writeType = writeType
                @Suppress("DEPRECATION")
                rx.value = fullCommand
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rx)
            }
            if (!ok) throw IOException("writeCharacteristic call failed.")

            if (withTimeoutOrNull(3000L) { deferred.await() } != true) {
                throw IOException("GATT write timed out or failed.")
            }
        } finally {
            if (writeDeferred === deferred) {
                writeDeferred = null
            }
        }
    }

    // ===== Internals =====

    private fun appendAndEmitLines(value: ByteArray) {
        val str = String(value, StandardCharsets.US_ASCII)
        for (ch in str) {
            when (ch) {
                '\r', '\n' -> {
                    val line = lineBuilder.toString().trim()
                    if (line.isNotEmpty()) {
                        debugLogger.log(TAG, "RX LINE: $line")
                        externalScope.launch { _incomingLines.tryEmit(line) }
                    }
                    lineBuilder.clear()
                }
                '>' -> {
                    val pending = lineBuilder.toString().trim()
                    if (pending.isNotEmpty()) {
                        debugLogger.log(TAG, "RX LINE: $pending")
                        externalScope.launch { _incomingLines.tryEmit(pending) }
                    }
                    lineBuilder.clear()

                    debugLogger.log(TAG, "RX PROMPT: >")
                    externalScope.launch { _incomingLines.tryEmit(">") }
                }
                else -> {
                    if (lineBuilder.length < MAX_LINE_BUFFER) {
                        lineBuilder.append(ch)
                    }
                }
            }
        }
    }

    private fun chooseCccdValue(tx: BluetoothGattCharacteristic): Pair<ByteArray, NotifyMode> {
        val canNotify = (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val canIndicate = (tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        return when {
            canNotify   -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to NotifyMode.NOTIFY
            canIndicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE  to NotifyMode.INDICATE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to NotifyMode.NOTIFY
        }
    }

    private fun writeCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        debugLogger.log(TAG, "CCCD write sent (value=${value.joinToString { String.format("%02X", it) }})")
    }

    private fun stopScanInternal() {
        if (bleScanner == null || bluetoothAdapter?.isEnabled == false) return
        debugLogger.log(TAG, "Stopping BLE scan action.")
        bleScanner?.stopScan(scanCallback)

        val state = _connectionState.value
        if (state is HardwareConnectionState.Connecting || state is HardwareConnectionState.Connected) {
            debugLogger.log(TAG, "Scan finished while $state — leaving state unchanged.")
            return
        }

        if (state is HardwareConnectionState.Scanning) {
            debugLogger.log(TAG, "Scan stopped. Resetting state to Disconnected.")
            _connectionState.value = HardwareConnectionState.Disconnected
        }

        if (_scanResults.value.isEmpty()) {
            debugLogger.log(TAG, "Scan finished with no compatible devices found.")
        } else {
            debugLogger.log(TAG, "Scan finished with ${_scanResults.value.size} result(s).")
        }
    }

    private fun closeConnection() {
        scanJob?.cancel()
        connectJob?.cancel()
        fallbackJob?.cancel()
        mtuFallbackJob?.cancel()
        try { gatt?.disconnect() } catch (e: Exception) { debugLogger.log(TAG, "Error disconnecting GATT: ${e.message}") }
        try { gatt?.close() } catch (e: Exception) { debugLogger.log(TAG, "Error closing GATT: ${e.message}") }
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null

        _connectionState.value = HardwareConnectionState.Disconnected
        debugLogger.log(TAG, "BLE resources released.")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name
            val deviceAddress = result.device.address
            debugLogger.log(TAG, "Scan found device: Name='${deviceName ?: "N/A"}', Address=$deviceAddress")
            if (!deviceName.isNullOrBlank() && (deviceName.contains("BT+BLE_Bridge") || deviceName.contains("Jarbly"))) {
                val newDevice = ConnectableDevice(name = deviceName, address = deviceAddress)
                _scanResults.update { list ->
                    if (list.any { it.address == newDevice.address }) list
                    else {
                        debugLogger.log(TAG, "Adding compatible device to list: ${newDevice.name}")
                        list + newDevice
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == 1) return
            debugLogger.log(TAG, "BLE scan failed with error code: $errorCode (${scanErrorToString(errorCode)})")
            _connectionState.value = HardwareConnectionState.Error("Scan failed with code: $errorCode")
        }
    }

    private fun gattStatusToString(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
        BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
        133 -> "GATT_ERROR_133"
        else -> status.toString()
    }

    private fun profileStateToString(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> state.toString()
    }

    private fun scanErrorToString(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "UNKNOWN_SCAN_ERROR"
    }

    // ===== Helpers =====

    private fun withinEarlyWarmupWindow(): Boolean {
        val sinceOn = System.currentTimeMillis() - lastBtTurnedOnAt
        return lastBtTurnedOnAt != 0L && sinceOn in 0..EARLY_133_RETRY_WINDOW_MS
    }

    private fun scheduleWarmReconnect(reason: String) {
        val addr = lastConnectAddress ?: return
        if (pendingWarmupRetry) return
        pendingWarmupRetry = true
        debugLogger.log(TAG, "Scheduling warm reconnect ($reason) in 500ms for $addr")
        externalScope.launch(Dispatchers.Main) {
            delay(500)
            closeConnection()
            connect(addr)
        }
    }

    private fun newConnTag(): String {
        connSeq = (connSeq + 1) % 10_000
        connTag = "#C$connSeq"
        bytesSinceCccd = 0
        return connTag
    }
}
