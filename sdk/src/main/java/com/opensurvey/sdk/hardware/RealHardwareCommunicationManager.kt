package com.opensurvey.sdk.hardware

import com.opensurvey.sdk.di.ApplicationScope
import com.opensurvey.sdk.interfaces.BleConnectionManager
import com.opensurvey.sdk.interfaces.HardwareCommunicationManager
import com.opensurvey.sdk.interfaces.HardwareConnectionState
import com.opensurvey.sdk.interfaces.UsbConnectionManager
import com.opensurvey.sdk.interfaces.UsbConnectionState
import com.opensurvey.sdk.models.ConnectableDevice
import com.opensurvey.sdk.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealHardwareCommunicationManager @Inject constructor(
    @ApplicationScope private val externalScope: CoroutineScope,
    private val debugLogger: DebugLogger,
    private val bleConnectionManager: BleConnectionManager,
    private val usbConnectionManager: UsbConnectionManager
) : HardwareCommunicationManager {

    companion object {
        private const val TAG = "HardwareCommManager"
    }

    // Orchestrator keeps its own state, derived from children
    private val _connectionState =
        MutableStateFlow<HardwareConnectionState>(HardwareConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ConnectableDevice>>(emptyList())
    override val scanResults = _scanResults.asStateFlow()

    private val _incomingLines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val incomingLines = _incomingLines.asSharedFlow()

    // Recent device output (for post-mortem when timeouts happen)
    private val lastLines = ArrayDeque<String>(20)

    private var activeProtocol: Protocol = Protocol.NONE

    private enum class Protocol { BLE, USB, NONE }

    init {
        // --- Forward BLE Events ---
        bleConnectionManager.connectionState.onEach { state ->
            if (activeProtocol == Protocol.BLE || activeProtocol == Protocol.NONE) {
                if (state is HardwareConnectionState.Connected) activeProtocol = Protocol.BLE
                if (state is HardwareConnectionState.Disconnected && activeProtocol == Protocol.BLE) activeProtocol = Protocol.NONE

                // Only propagate if USB isn't taking over (rudimentary priority)
                if (activeProtocol == Protocol.BLE || activeProtocol == Protocol.NONE) {
                    _connectionState.value = state
                }
            }
        }.launchIn(externalScope)

        bleConnectionManager.incomingLines.onEach { line ->
            if (activeProtocol == Protocol.BLE) emitLine(line)
        }.launchIn(externalScope)

        bleConnectionManager.scanResults.onEach { results ->
             _scanResults.value = results
        }.launchIn(externalScope)


        // --- Forward USB Events ---
        usbConnectionManager.connectionState.onEach { state ->
            // USB generally takes precedence if connected
            val mappedState = when(state) {
                is UsbConnectionState.Connected -> HardwareConnectionState.Connected
                is UsbConnectionState.Connecting -> HardwareConnectionState.Connecting
                is UsbConnectionState.Disconnected -> HardwareConnectionState.Disconnected
                is UsbConnectionState.Error -> HardwareConnectionState.Error(state.message)
            }

            if (mappedState is HardwareConnectionState.Connected) {
                activeProtocol = Protocol.USB
                _connectionState.value = mappedState
            } else if (mappedState is HardwareConnectionState.Disconnected && activeProtocol == Protocol.USB) {
                activeProtocol = Protocol.NONE
                _connectionState.value = mappedState
            } else if (activeProtocol == Protocol.USB) {
                // Propagate errors/connecting if USB is active
                _connectionState.value = mappedState
            }
        }.launchIn(externalScope)

        usbConnectionManager.incomingLines.onEach { line ->
            if (activeProtocol == Protocol.USB) emitLine(line)
        }.launchIn(externalScope)

        // --- Handshake Logic ---
        connectionState.onEach { state ->
            if (state is HardwareConnectionState.Connected) {
                performConnectionHandshake()
            }
        }.launchIn(externalScope)
    }

    private fun emitLine(line: String) {
        if (lastLines.size >= 20) lastLines.removeFirst()
        lastLines.addLast(line)
        externalScope.launch { _incomingLines.emit(line) }
    }

    override fun startScan() {
        debugLogger.log(TAG, "Hardware scan requested. Delegating to BLE Manager.")
        bleConnectionManager.startScan()
    }

    override fun stopScan() {
        debugLogger.log(TAG, "Stop scan requested.")
        bleConnectionManager.stopScan()
    }

    override fun connect(address: String) {
        debugLogger.log(TAG, "Connect requested for $address. Delegating to BLE Manager.")
        activeProtocol = Protocol.BLE // Assume BLE for this
        bleConnectionManager.connect(address)
    }

    override fun disconnect() {
        debugLogger.log(TAG, "Disconnect all requested.")
        bleConnectionManager.disconnect()
        usbConnectionManager.disconnect()
        activeProtocol = Protocol.NONE
    }

    override fun getUsbDevices(): Map<String, Int> {
        return usbConnectionManager.getUsbDevices()
    }

    override fun startUsbConnection(deviceId: Int) {
         // Disconnect BLE to avoid conflicts? Or just switch protocol?
         // Optimally: disconnect BLE
         bleConnectionManager.disconnect()
         activeProtocol = Protocol.USB
         usbConnectionManager.startConnection(deviceId)
    }

    override suspend fun sendCommand(command: String, timeoutMillis: Long) {
        debugLogger.log(TAG, "Orchestrator: Sending '$command' via $activeProtocol")

        val promptJob = externalScope.async {
            withTimeout(timeoutMillis) {
                incomingLines.first { it.contains('>') }
            }
        }

        try {
            when (activeProtocol) {
                Protocol.BLE -> bleConnectionManager.sendCommand(command)
                Protocol.USB -> usbConnectionManager.sendCommand(command)
                Protocol.NONE -> throw IOException("No active connection")
            }
            val promptLine = promptJob.await()
            debugLogger.log(TAG, "Orchestrator: Prompt received ('$promptLine')")
        } catch (e: Exception) {
            val recent = lastLines.joinToString(" | ")
            throw IOException(
                "Command '$command' failed or timed out. Protocol: $activeProtocol. Recent RX: [$recent]",
                e
            )
        }
    }

    override suspend fun sendCommandAndAwaitResponse(
        command: String,
        responsePrefix: String,
        timeoutMillis: Long
    ): String? {
        debugLogger.log(TAG, "Orchestrator: Sending '$command' expecting '$responsePrefix'")
        val responseJob = externalScope.async {
            withTimeout(timeoutMillis) {
                incomingLines.first { it.startsWith(responsePrefix) }
            }
        }

        try {
             when (activeProtocol) {
                Protocol.BLE -> bleConnectionManager.sendCommand(command)
                Protocol.USB -> usbConnectionManager.sendCommand(command)
                Protocol.NONE -> throw IOException("No active connection")
            }
            return responseJob.await().also {
                debugLogger.log(TAG, "Orchestrator: Response received: '$it'")
            }
        } catch (e: Exception) {
            responseJob.cancel()
             debugLogger.log(TAG, "Orchestrator: Failed awaiting response for '$command': ${e.message}")
            return null
        }
    }

    override suspend fun setupSurveyor() {
        debugLogger.log(TAG, "Setting up surveyor hardware (Orchestrated)...")

        retry(times = 2) {
            sendCommand("exeCopyConfigFile,RxDefault,Current", timeoutMillis = 15_000L)
        }
        sendCommand("setNMEAOutput,Stream1,USB1,GGA+GSA+GST+GSV+RMC,sec1")
        sendCommand("setNMEAOutput,Stream2,USB2,GGA+GSA+GST+GSV+RMC,sec1")
        sendCommand("setNMEAOutput,Stream3,COM1,GGA+GSA+GST+GSV+RMC,sec1")
        sendCommand("setNMEATalkerID,auto")
        sendCommand("setAntennaOffset,Main,,,'AS-ANT3BCAL01 NONE'")
        sendCommand("setSignalTracking,GPSL1CA+GPSL1PY+GPSL2PY+GPSL2C+GPSL5+GLOL1CA+GLOL2P+GLOL2CA+GLOL3+GALL1BC+GALE6BC+GALE5a+GALE5b+GALE5+GEOL1+GEOL5+BDSB1I+BDSB2I+BDSB3I+BDSB1C+BDSB2a+BDSB2b+QZSL1CA+QZSL2C+QZSL5+QZSL1CB+NAVICL5")
        sendCommand("setSignalUsage,GPSL1CA+GPSL1PY+GPSL2PY+GPSL2C+GLOL1CA+GLOL2P+GLOL2CA+GALL1BC+GALE5a+GALE5b+GALE5+GEOL1+GEOL5+BDSB1I+BDSB2I+BDSB3I+QZSL1CA+QZSL2C+QZSL1CB+NAVICL5,GPSL1CA+GLOL1CA+GLOL2CA+GALL1BC+GALE5a+GALE5b+GALE5+GEOL1+BDSB1I+BDSB2I+BDSB3I+QZSL1CA+QZSL1CB+NAVICL5")
        sendCommand("exeCopyConfigFile,Current,Boot")

        debugLogger.log(TAG, "Surveyor hardware setup complete.")
    }

    private fun performConnectionHandshake() {
        externalScope.launch {
            try {
                debugLogger.log(TAG, "Connection established. Performing handshake...")
                // We send an empty command just to get a prompt back
                sendCommand("", timeoutMillis = 5000L)
            } catch (e: Exception) {
                debugLogger.log(TAG, "WARNING: Handshake failed. Error: ${e.message}")
            }
        }
    }

    private suspend fun retry(times: Int, block: suspend () -> Unit) {
        var cause: Exception? = null
        repeat(times) {
             try { block(); return } catch (e: Exception) { cause = e }
        }
        throw cause ?: RuntimeException("Retry failed")
    }
}