package com.opensurvey.sdk.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.opensurvey.sdk.di.ApplicationScope
import com.opensurvey.sdk.interfaces.UsbConnectionManager
import com.opensurvey.sdk.interfaces.UsbConnectionState
import com.opensurvey.sdk.utils.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("UnspecifiedRegisterReceiverFlag")
@Singleton
class RealUsbConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val debugLogger: DebugLogger
) : UsbConnectionManager {

    companion object {
        private const val TAG = "UsbConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.opensurvey.USB_PERMISSION"
        private const val PERMISSION_TIMEOUT_MS = 15_000L
    }

    private val usbManager: UsbManager by lazy { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val _incomingLines = MutableSharedFlow<String>()
    override val incomingLines = _incomingLines.asSharedFlow()

    private var connectionJob: Job? = null
    private var dataStreamJob: Job? = null
    private var usbPermissionDeferred: CompletableDeferred<Boolean>? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbCommInterface: UsbInterface? = null
    private var usbDataInterface: UsbInterface? = null
    private var usbInEndpoint: UsbEndpoint? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    debugLogger.log(TAG, "USB permission broadcast received. Granted: $granted")
                    usbPermissionDeferred?.complete(granted)
                }
            }
        }
    }

    init {
        debugLogger.log(TAG, "Manager initialized.")
    }

    override fun getUsbDevices(): Map<String, Int> {
        debugLogger.log(TAG, "getUsbDevices called.")
        val devices = usbManager.deviceList.values.associate { device ->
            (device.productName ?: "USB Device ${device.deviceId}") to device.deviceId
        }
        debugLogger.log(TAG, "Found ${devices.size} USB devices: $devices")
        return devices
    }

    override fun startConnection(deviceId: Int) {
        if (_connectionState.value is UsbConnectionState.Connecting || _connectionState.value is UsbConnectionState.Connected) return
        connectionJob?.cancel()

        connectionJob = externalScope.launch {
            _connectionState.value = UsbConnectionState.Connecting
            debugLogger.log(TAG, "startConnection called for deviceId: $deviceId")

            val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            if (device == null) {
                _connectionState.value = UsbConnectionState.Error("USB device not found.")
                return@launch
            }
            debugLogger.log(TAG, "Device found: ${device.deviceName}")

            try {
                if (!requestUsbPermission(device)) {
                    throw IOException("USB permission was denied by the user or timed out.")
                }

                debugLogger.log(TAG, "USB permission granted. Finding and claiming interfaces...")
                findAndClaimUsbInterface(device)

                debugLogger.log(TAG, "Setting serial line coding (Baud Rate: 115200, 8N1)...")
                val baudRate = 115200
                val stopBits: Byte = 0
                val parity: Byte = 0
                val dataBits: Byte = 8

                val payload = ByteBuffer.allocate(7).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(baudRate)
                    put(stopBits)
                    put(parity)
                    put(dataBits)
                }.array()

                val result = usbConnection?.controlTransfer(
                    0x21, // requestType: Host-to-Device, Class, Interface
                    0x20, // request: SET_LINE_CODING
                    0,    // value
                    usbCommInterface?.id ?: 0, // index: USE THE COMM INTERFACE ID
                    payload,
                    payload.size,
                    500
                )
                if (result == null || result < 0) {
                    throw IOException("Failed to set USB line coding (baud rate). Result: $result")
                }
                debugLogger.log(TAG, "Successfully set serial line coding.")

                debugLogger.log(TAG, "Sending control transfers to set parameters...")
                usbConnection?.controlTransfer(0x21, 0x22, 0x1, 0, null, 0, 0) // DTR=1
                usbConnection?.controlTransfer(0x21, 0x22, 0x2, 0, null, 0, 0) // RTS=1
                debugLogger.log(TAG, "Control transfers sent.")

                _connectionState.value = UsbConnectionState.Connected
                debugLogger.log(TAG, "State set to Connected. Starting data stream reader.")
                dataStreamJob = launch { readUsbDataStream() }

            } catch (e: Exception) {
                debugLogger.log(TAG, "ERROR during USB connection setup: ${e.message}")
                _connectionState.value = UsbConnectionState.Error("USB connection failed: ${e.message}")
                disconnect()
            }
        }
    }

    override suspend fun sendCommand(command: String) {
        val connection = usbConnection ?: throw IOException("USB connection not available for write.")
        val currentInterface = usbDataInterface ?: throw IOException("USB data interface not claimed for write.")

        var outEndpoint: UsbEndpoint? = null

        // Find the OUT endpoint on the claimed interface
        for (i in 0 until currentInterface.endpointCount) {
            val endpoint = currentInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                outEndpoint = endpoint
                break
            }
        }

        if (outEndpoint == null) {
            throw IOException("No suitable USB OUT endpoint found.")
        }

        val fullCommand = (command + "\r").toByteArray(StandardCharsets.US_ASCII)

        withContext(Dispatchers.IO) {
            val bytesSent = connection.bulkTransfer(outEndpoint, fullCommand, fullCommand.size, 5000)
            if (bytesSent < 0) {
                throw IOException("USB bulk transfer for write failed.")
            }
            debugLogger.log(TAG, "USB TX --> $bytesSent bytes: '$command'")
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        dataStreamJob?.cancel()
        try {
            // --- Use the correct interface variable ---
            usbDataInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            debugLogger.log(TAG, "Error closing USB connection: ${e.message}")
        }
        usbConnection = null
        // --- Clear all interface variables ---
        usbCommInterface = null
        usbDataInterface = null
        usbInEndpoint = null
        _connectionState.value = UsbConnectionState.Disconnected
        debugLogger.log(TAG, "USB resources released.")
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            debugLogger.log(TAG, "USB permission already granted.")
            return true
        }

        val usbPermissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, usbPermissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbPermissionReceiver, usbPermissionFilter)
        }

        return try {
            debugLogger.log(TAG, "Requesting USB permission from user...")
            usbPermissionDeferred = CompletableDeferred()

            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }

            val flags =
                PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)

            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
                usbPermissionDeferred?.await()
            } ?: false
        } finally {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
                debugLogger.log(TAG, "USB permission receiver unregistered.")
            } catch (_: IllegalArgumentException) {
                // Ignore if it was already unregistered or never registered.
            }
        }
    }

    private fun findAndClaimUsbInterface(device: UsbDevice) {
        debugLogger.log(TAG, "Device has ${device.interfaceCount} interfaces. Searching for COMM and DATA.")
        var commIntf: UsbInterface? = null
        var dataIntf: UsbInterface? = null

        // First pass: Identify the Communications and Data interfaces
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> {
                    commIntf = intf
                    debugLogger.log(TAG, "Found COMM interface #${intf.id}")
                }
                UsbConstants.USB_CLASS_CDC_DATA -> {
                    dataIntf = intf
                    debugLogger.log(TAG, "Found DATA interface #${intf.id}")
                }
            }
        }

        if (commIntf == null) throw IOException("Required USB Communications interface not found.")
        if (dataIntf == null) throw IOException("Required USB Data interface not found.")

        // Second pass: Find the bulk IN endpoint on the Data interface
        var inEp: UsbEndpoint? = null
        for (j in 0 until dataIntf.endpointCount) {
            val endpoint = dataIntf.getEndpoint(j)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_IN) {
                inEp = endpoint
                debugLogger.log(TAG, "Found Bulk IN endpoint on interface #${dataIntf.id}")
                break
            }
        }
        if (inEp == null) throw IOException("Required Bulk IN endpoint not found on data interface.")

        // Now, open the connection and claim the DATA interface for I/O
        val connection = usbManager.openDevice(device) ?: throw IOException("Failed to open USB device.")
        debugLogger.log(TAG, "Claiming DATA interface #${dataIntf.id} for transfer.")
        connection.claimInterface(dataIntf, true)

        // Store all the discovered components
        usbConnection = connection
        usbCommInterface = commIntf
        usbDataInterface = dataIntf
        usbInEndpoint = inEp
    }

    private suspend fun readUsbDataStream() {
        val connection = usbConnection ?: return
        val endpoint = usbInEndpoint ?: return
        val buffer = ByteArray(endpoint.maxPacketSize)
        val lineBuilder = StringBuilder()

        debugLogger.log(TAG, "USB Data stream reader started.")
        try {
            withContext(Dispatchers.IO) {
                while (currentCoroutineContext().isActive) {
                    // Using a timeout prevents this call from blocking forever.
                    val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, 5000)

                    if (bytesRead > 0) {
                        debugLogger.log(TAG, "USB RX <-- $bytesRead bytes")
                        val chunk = String(buffer, 0, bytesRead, StandardCharsets.US_ASCII)
                        debugLogger.log(TAG, "USB RX <-- $bytesRead bytes: \"${chunk.replace("\r", "\\r").replace("\n", "\\n")}\"")
                        for (char in chunk) {
                            if (char == '\n') {
                                val line = lineBuilder.toString().trim()
                                if (line.isNotEmpty()) {
                                    _incomingLines.emit(line)
                                }
                                lineBuilder.clear()
                            } else {
                                lineBuilder.append(char)
                            }
                        }
                    } else if (bytesRead == 0) {
                        // This means the timeout was hit, but it's not an error. The device might just be idle.
                         debugLogger.log(TAG, "USB bulkTransfer timed out (0 bytes read), continuing loop.")
                    }
                }
            }
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                debugLogger.log(TAG, "ERROR: USB stream broke with IOException: ${e.message}")
                _connectionState.value = UsbConnectionState.Error("Connection lost.")
            }
        } finally {
            debugLogger.log(TAG, "USB Data stream reader finished.")
        }
    }
}