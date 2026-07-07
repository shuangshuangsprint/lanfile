package com.example.smb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.smb.MainViewModel
import com.example.smb.SmbConnectionInfo
import com.example.smb.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: MainViewModel,
    onNavigateToBrowser: () -> Unit
) {
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val savedConnection by viewModel.savedConnection.collectAsState(initial = null)
    val customNames by viewModel.customNames.collectAsState()

    var showDialogForDevice by remember { mutableStateOf<com.example.smb.SmbDevice?>(null) }
    var showPropertiesForDevice by remember { mutableStateOf<com.example.smb.SmbDevice?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var propertiesName by remember { mutableStateOf("") }
    
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }

    var manualIp by remember { mutableStateOf("") }
    var manualUsername by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var manualRemember by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    LaunchedEffect(connectionState) {
        if (connectionState is UiState.Success) {
            onNavigateToBrowser()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LAN File") },
                actions = {
                    IconButton(onClick = { viewModel.startScan() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isScanning,
            onRefresh = { viewModel.startScan() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
            if (savedConnection != null) {
                item {
                    SectionHeader("Saved Server")
                }
                item {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteSavedConnection()
                                true
                            } else {
                                false
                            }
                        }
                    )
                    
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .background(color, shape = MaterialTheme.shapes.medium),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                            }
                        }
                    ) {
                        DeviceItem(
                            title = savedConnection!!.name,
                            subtitle = savedConnection!!.ip,
                            icon = Icons.Default.Dns,
                            onClick = {
                                viewModel.connectAndSave(savedConnection!!, remember = true)
                            }
                        )
                    }
                }
            }

            val filteredScannedDevices = scannedDevices.filter { it.ip != savedConnection?.ip }

            item {
                SectionHeader("Discovered Devices (LAN)")
                if (isScanning) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (filteredScannedDevices.isEmpty()) {
                    Text(
                        text = "No devices found",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            items(filteredScannedDevices, key = { it.ip }) { device ->
                val displayName = customNames[device.ip] ?: device.name
                DeviceItem(
                    modifier = Modifier.animateItem(),
                    title = displayName,
                    subtitle = device.ip,
                    icon = Icons.Default.Computer,
                    onClick = {
                        username = ""
                        password = ""
                        showDialogForDevice = device
                    },
                    onLongClick = {
                        propertiesName = displayName
                        showPropertiesForDevice = device
                    }
                )
            }
            
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { showAddManualDialog = true },
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Manual Connection",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        }
    }

    if (showAddManualDialog) {
        AlertDialog(
            onDismissRequest = { showAddManualDialog = false },
            title = { Text("Add Manual Connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionState is UiState.Error) {
                        Text(
                            text = (connectionState as UiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP Address (e.g. 192.168.1.100 or IPv6)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualUsername,
                        onValueChange = { manualUsername = it },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualPassword,
                        onValueChange = { manualPassword = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = manualRemember,
                            onCheckedChange = { manualRemember = it }
                        )
                        Text("Remember Server")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualIp.isNotBlank()) {
                            viewModel.connectAndSave(
                                SmbConnectionInfo(
                                    name = manualIp,
                                    ip = manualIp.trim(),
                                    username = manualUsername,
                                    password = manualPassword
                                ),
                                remember = manualRemember
                            )
                            showAddManualDialog = false
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddManualDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPropertiesForDevice != null) {
        val device = showPropertiesForDevice!!
        AlertDialog(
            onDismissRequest = { showPropertiesForDevice = null },
            title = { Text("Device Properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IP Address: ${device.ip}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = propertiesName,
                        onValueChange = { propertiesName = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setCustomDeviceName(device.ip, propertiesName.trim())
                        showPropertiesForDevice = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPropertiesForDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDialogForDevice != null) {
        val device = showDialogForDevice!!
        AlertDialog(
            onDismissRequest = { showDialogForDevice = null },
            title = { Text("Connect to ${device.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionState is UiState.Error) {
                        Text(
                            text = (connectionState as UiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberMe = !rememberMe }
                    ) {
                        Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                        Text("Remember password")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.connectAndSave(
                            SmbConnectionInfo(name = device.name, ip = device.ip, shareName = "", username = username, password = password),
                            remember = rememberMe
                        )
                    }
                ) {
                    if (connectionState is UiState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Connect")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialogForDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DeviceItem(
    modifier: Modifier = Modifier, 
    title: String, 
    subtitle: String? = null, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
            headlineContent = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = subtitle?.let { { Text(text = it, style = MaterialTheme.typography.bodySmall) } },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }
}
