package com.opensurvey.sdk.interfaces

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A sealed interface to represent the specific states of a USB connection.
 */
sealed interface UsbConnectionState {
    data object Disconnected : UsbConnectionState
    data object Connecting : UsbConnectionState
    data object Connected : UsbConnectionState
    data class Error(val message: String) : UsbConnectionState
}

/**
 * An abstraction for managing a USB serial connection.
 */
interface UsbConnectionManager {
    /** Emits the current state of the USB connection. */
    val connectionState: StateFlow<UsbConnectionState>

    /** A flow of all incoming, validated lines of data from the USB device. */
    val incomingLines: Flow<String>

    /** Gets a list of currently attached USB devices. */
    fun getUsbDevices(): Map<String, Int>

    /** Starts the connection process for a specific USB device. */
    fun startConnection(deviceId: Int)

    suspend fun sendCommand(command: String)

    /** Disconnects from the currently connected USB device and releases resources. */
    fun disconnect()
}