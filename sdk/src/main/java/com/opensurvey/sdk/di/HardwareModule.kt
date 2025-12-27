package com.opensurvey.sdk.di

import com.opensurvey.sdk.hardware.RealHardwareCommunicationManager
import com.opensurvey.sdk.interfaces.HardwareCommunicationManager
import com.opensurvey.sdk.interfaces.UsbConnectionManager
import com.opensurvey.sdk.usb.RealUsbConnectionManager
import com.opensurvey.sdk.interfaces.BleConnectionManager
import com.opensurvey.sdk.ble.RealBleConnectionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HardwareModule {

    @Binds
    @Singleton
    abstract fun bindHardwareCommunicationManager(
        impl: RealHardwareCommunicationManager
    ): HardwareCommunicationManager

    @Binds
    @Singleton
    abstract fun bindUsbConnectionManager(
        impl: RealUsbConnectionManager
    ): UsbConnectionManager

    @Binds
    @Singleton
    abstract fun bindBleConnectionManager(
        impl: RealBleConnectionManager
    ): BleConnectionManager

    @Binds
    @Singleton
    abstract fun bindConsoleLogger(
        impl: com.opensurvey.sdk.utils.DebugLogger
    ): com.opensurvey.sdk.interfaces.ConsoleLogger
}
