package com.opensurvey.sdk.interfaces

import com.opensurvey.sdk.models.ConnectableDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A platform-agnostic interface for discovering and communicating with a BLE Base Station.
 */
interface HardwareCommunicationManager {

    /** Emits the current state of the BLE connection. */
    val connectionState: StateFlow<HardwareConnectionState>

    /** A flow of all incoming, validated lines of data from the Base Station. */
    val incomingLines: Flow<String>

    /** Emits a list of nearby, connectable BLE devices. */
    val scanResults: StateFlow<List<ConnectableDevice>>

    /** Starts scanning for nearby BLE devices. */
    fun startScan()

    /** Stops scanning for nearby BLE devices. */
    fun stopScan()

    /**
     * Connects to a specific device by its address.
     * @param address The MAC address of the device to connect to.
     */
    fun connect(address: String)

    /** Disconnects from the currently connected device. */
    fun disconnect()

    suspend fun setupSurveyor()

    /**
     * Sends a command and waits for the '>' prompt acknowledgement.
     * Use this for configuration commands that don't return specific data.
     */
    suspend fun sendCommand(command: String, timeoutMillis: Long = 5000L)

    /**
     * Sends a command and waits for a specific line of text in the response.
     * Use this for 'get' commands that return a value.
     */
    suspend fun sendCommandAndAwaitResponse(
        command: String,
        responsePrefix: String,
        timeoutMillis: Long = 5000L
    ): String?

    /**
     * Gets a list of currently attached USB devices that might be the surveyor.
     * @return A map of device names to their unique device ID.
     */
    fun getUsbDevices(): Map<String, Int>

    /**
     * Attempts to connect to a USB device by its device ID.
     * This will trigger a system permission dialog if needed.
     * @param deviceId The ID of the device from getUsbDevices().
     */
    fun startUsbConnection(deviceId: Int)
}

/** Represents the various states of a BLE connection. */
sealed interface HardwareConnectionState {
    data object Disconnected : HardwareConnectionState
    data object Scanning : HardwareConnectionState
    data object Connecting : HardwareConnectionState
    data object Connected : HardwareConnectionState
    data class Error(val message: String) : HardwareConnectionState
}