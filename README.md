# OpenSurvey Android Application

OpenSurvey is a modular, white-label surveying application framework developed for Android. It provides a robust architecture for communicating with custom hardware via Bluetooth Low Energy (BLE) and USB, enabling field surveyors to capture data efficiently.

The project is structured to separate core hardware communication logic from the user interface, allowing for high reusability and maintainability.

## Key Features

- **Dual Mode Connectivity**: seamless switching between Bluetooth and USB connections.
- **Hardware Abstraction Layer**: Generic interfaces for BLE and USB communication orchestration.
- **Real-time Console**: integrated debug console for monitoring device communication stream.
- **Auto-Connect**: intelligent USB device detection and connection handling.
- **Modern UI**: built entirely with Jetpack Compose and Material 3 design system.
- **Architecture**: follows Clean Architecture principles with MVVM pattern and Dependency Injection.

## Project Structure

The project is divided into two primary modules:

### 1. SDK (`:sdk`)
Contains the core business logic and hardware communication layer. It is independent of the UI and can be reused across different applications.
- **Interfaces**: Defines contracts for `HardwareCommunicationManager`, `BleConnectionManager`, etc.
- **Implementations**: Contains `RealHardwareCommunicationManager` (Orchestrator), `RealBleConnectionManager`, and `RealUsbConnectionManager`.
- **DI**: Hilt modules for providing hardware dependencies.

### 2. App (`:app`) & Feature (`:feature:home`)
Contains the Android application code and UI components.
- **feature:home**: A standalone feature module containing the `HomeScreen`, `HomeViewModel`, and associated UI logic.
- **app**: The application shell responsible for dependency injection setup and navigation hosting.

## Architecture

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Dependency Injection**: Dagger Hilt
- **Asynchrony**: Kotlin Coroutines & Flow
- **Navigation**: Jetpack Compose Navigation

## Getting Started

### Prerequisites
- Android Studio Ladybug or newer.
- JDK 17.
- Physical Android device with BLE and USB OTG support.

### Build and Run
1. Open the project in Android Studio.
2. Sync Gradle dependencies.
3. Select the `app` configuration.
4. Run on a connected Android device.

## License

MIT License - Open Source