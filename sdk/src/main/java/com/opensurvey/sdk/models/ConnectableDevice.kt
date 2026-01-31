package com.opensurvey.sdk.models

/**
 * A pure, platform-agnostic representation of a device that the app can connect to.
 * This lives in the domain layer and has no Android-specific dependencies.
 */
data class ConnectableDevice(
    val name: String,
    val address: String // The unique MAC address
)