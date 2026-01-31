package com.opensurvey.sdk.interfaces

import com.opensurvey.sdk.models.ConnectableDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the lifecycle and data flow of a BLE connection.
 */
interface BleConnectionManager {
    /** Emits the current state of the BLE connection. */
    val connectionState: StateFlow<HardwareConnectionState>

    /** A flow of all incoming, validated lines of data from the BLE device. */
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

    /**
     * Sends a command to the connected BLE device.
     */
    suspend fun sendCommand(command: String)
}
