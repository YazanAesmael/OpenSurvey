package com.opensurvey.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensurvey.sdk.interfaces.ConsoleLogger
import com.opensurvey.sdk.interfaces.HardwareCommunicationManager
import com.opensurvey.sdk.interfaces.HardwareConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

enum class ConnectionMode {
    Bluetooth,
    Usb
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val hardwareManager: HardwareCommunicationManager,
    logger: ConsoleLogger
) : ViewModel() {

    private val _viewMode = MutableStateFlow(ConnectionMode.Bluetooth)
    val viewMode = _viewMode.asStateFlow()

    val logs = logger.logs

    val connectionState = hardwareManager.connectionState
    val scanResults = hardwareManager.scanResults
    
    // USB Polling
    private var usbPollJob: Job? = null
    private val _usbStatusMessage = MutableStateFlow("Waiting for USB device...")
    val usbStatusMessage = _usbStatusMessage.asStateFlow()

    init {
        // React to mode changes
        _viewMode.onEach { mode ->
            if (mode == ConnectionMode.Usb) {
                startUsbPolling()
            } else {
                stopUsbPolling()
            }
        }.launchIn(viewModelScope)
        
        // React to connection state to update USB status
        connectionState.onEach { state ->
             if (_viewMode.value == ConnectionMode.Usb) {
                 when(state) {
                     is HardwareConnectionState.Connected -> _usbStatusMessage.value = "Connected via USB"
                     is HardwareConnectionState.Connecting -> _usbStatusMessage.value = "Connecting to USB device..."
                     is HardwareConnectionState.Error -> _usbStatusMessage.value = "Error: ${state.message}"
                     else -> { /* Keep poll status */ }
                 }
             }
        }.launchIn(viewModelScope)
    }

    fun setMode(mode: ConnectionMode) {
        if (_viewMode.value != mode) {
            _viewMode.value = mode
            hardwareManager.disconnect() // Disconnect when switching modes for safety
        }
    }

    fun startScan() {
        if (_viewMode.value == ConnectionMode.Bluetooth) {
            hardwareManager.startScan()
        }
    }

    fun stopScan() {
        hardwareManager.stopScan()
    }

    fun connect(address: String) {
        hardwareManager.connect(address)
    }

    fun disconnect() {
        hardwareManager.disconnect()
    }
    
    private fun startUsbPolling() {
        usbPollJob?.cancel()
        usbPollJob = viewModelScope.launch {
            while (isActive) {
                if (connectionState.value is HardwareConnectionState.Disconnected) {
                    val devices = hardwareManager.getUsbDevices()
                    if (devices.isEmpty()) {
                        _usbStatusMessage.value = "Waiting for USB device..."
                    } else {
                        val (name, id) = devices.entries.first()
                        _usbStatusMessage.value = "Found $name. Connecting..."
                        hardwareManager.startUsbConnection(id)
                        // Delay to allow connection to process before polling again
                        delay(2.seconds)
                    }
                }
                delay(1.seconds)
            }
        }
    }
    
    private fun stopUsbPolling() {
        usbPollJob?.cancel()
        usbPollJob = null
    }
}
