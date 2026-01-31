package com.opensurvey.feature.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.opensurvey.sdk.interfaces.HardwareConnectionState
import com.opensurvey.sdk.models.ConnectableDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val usbStatus by viewModel.usbStatusMessage.collectAsStateWithLifecycle()

    // Permissions
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        launcher.launch(permissions.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenSurvey") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Card
            StatusCard(connectionState)

            Spacer(modifier = Modifier.height(16.dp))

            // Mode Selector tabs
            TabRow(
                selectedTabIndex = viewMode.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                ConnectionMode.entries.forEachIndexed { index, mode ->
                    Tab(
                        selected = viewMode.ordinal == index,
                        onClick = { viewModel.setMode(mode) },
                        text = { Text(mode.name) },
                        icon = { 
                            Icon(
                                if(mode == ConnectionMode.Bluetooth) Icons.Filled.Bluetooth else Icons.Filled.Usb,
                                contentDescription = null 
                            ) 
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Body Content based on Mode
            if (viewMode == ConnectionMode.Bluetooth) {
                BluetoothContent(
                    scanResults = scanResults,
                    connectionState = connectionState,
                    onStartScan = { viewModel.startScan() },
                    onStopScan = { viewModel.stopScan() },
                    onConnect = { viewModel.connect(it) },
                    onDisconnect = { viewModel.disconnect() }
                )
            } else {
                UsbContent(
                    usbStatus = usbStatus,
                    onDisconnect = { viewModel.disconnect() },
                    isConnected = connectionState is HardwareConnectionState.Connected
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Debug Console
            val logs by viewModel.logs.collectAsStateWithLifecycle()
            DebugConsole(logs = logs)
        }
    }
}

@Composable
fun BluetoothContent(
    scanResults: List<ConnectableDevice>,
    connectionState: HardwareConnectionState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onStartScan,
                enabled = connectionState !is HardwareConnectionState.Scanning
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start Scan")
            }

            Button(
                onClick = onStopScan,
                enabled = connectionState is HardwareConnectionState.Scanning,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                 Text("Stop Scan")
            }
        }
        
        if (connectionState is HardwareConnectionState.Connected) {
             Spacer(modifier = Modifier.height(16.dp))
             Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                 Text("Disconnect")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Device List
        Text(
            "Available Devices (${scanResults.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(scanResults) { device ->
                DeviceItem(device) {
                    onConnect(device.address)
                }
            }
        }
    }
}

@Composable
fun UsbContent(
    usbStatus: String,
    onDisconnect: () -> Unit,
    isConnected: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
    ) {
        if (isConnected) {
            Icon(
                Icons.Filled.Cable, 
                contentDescription = null, 
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Connected via USB",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                 Text("Disconnect USB")
            }
        } else {
             CircularProgressIndicator()
             Spacer(modifier = Modifier.height(24.dp))
             Text(
                usbStatus,
                style = MaterialTheme.typography.bodyLarge
            )
             Spacer(modifier = Modifier.height(8.dp))
             Text(
                "Please enable OTG if required",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun StatusCard(state: HardwareConnectionState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when(state) {
                is HardwareConnectionState.Connected -> Color(0xFFE8F5E9) // Light Green
                is HardwareConnectionState.Error -> Color(0xFFFFEBEE) // Light Red
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when(state) {
                    is HardwareConnectionState.Connected -> Icons.Filled.CheckCircle
                    is HardwareConnectionState.Error -> Icons.Filled.Warning
                    else -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = when(state) {
                    is HardwareConnectionState.Connected -> Color(0xFF2E7D32)
                    is HardwareConnectionState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Connection Status", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = state.toString().substringAfterLast("."), // Simple implementation
                    style = MaterialTheme.typography.titleMedium
                )
             }
        }
    }
}

@Composable
fun DeviceItem(device: ConnectableDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(device.address, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
    }
}

@Composable
fun DebugConsole(logs: List<String>) {
    var height by remember { mutableStateOf(150.dp) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        // The Console Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .align(Alignment.BottomCenter),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp).padding(top = 0.dp)) { 
                Text(
                    "Debug Console", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
                            ),
                            color = Color(0xFF00E676)
                        )
                    }
                }
            }
        }

        // The Drag Handle (Circle with Arrow)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (-12).dp.roundToPx()) }
                .zIndex(1f) 
                .clip(CircleShape)
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val deltaDp = with(density) { delta.toDp() }
                        height = (height - deltaDp).coerceIn(100.dp, 400.dp)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = "Resize Console",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(90f)
                )
            }
        }
    }
}
