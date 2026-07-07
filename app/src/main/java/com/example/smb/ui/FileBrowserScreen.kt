package com.example.smb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smb.FileItem
import com.example.smb.MainViewModel
import com.example.smb.TransferProgress
import com.example.smb.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val filesState by viewModel.filesState.collectAsState()
    val isRemote by viewModel.isRemote.collectAsState()
    val currentPathFlow = viewModel.currentPath
    val currentPath by currentPathFlow.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val currentServerName by viewModel.currentServerName.collectAsState()

    LaunchedEffect(Unit) {
        if (currentPath.isEmpty() && isRemote) {
            viewModel.loadFiles("", true)
        } else {
            viewModel.loadFiles(currentPath, isRemote)
        }
    }

    val context = LocalContext.current

    var isNavigatingBack by remember { mutableStateOf(false) }

    val handleBack = {
        if (!isNavigatingBack) {
            val parentPath = currentPath.substringBeforeLast("/", "")
            if (currentPath.isEmpty()) {
                isNavigatingBack = true
                viewModel.resetConnectionState()
                onNavigateBack()
            } else {
                viewModel.loadFiles(parentPath, isRemote)
            }
        }
    }

    androidx.activity.compose.BackHandler(onBack = handleBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (currentPath.isEmpty()) {
                            if (isRemote) currentServerName ?: "SMB Root" else "Local Files"
                        } else {
                            currentPath.substringAfterLast('/')
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    var showNewFileDialog by remember { mutableStateOf(false) }
                    var newFileName by remember { mutableStateOf("") }
                    var newFileContent by remember { mutableStateOf("") }

                    IconButton(onClick = { showNewFileDialog = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New Text File")
                    }

                    if (showNewFileDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewFileDialog = false },
                            title = { Text("New Text File") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newFileName,
                                        onValueChange = { newFileName = it },
                                        label = { Text("File Name (e.g., note.txt)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = newFileContent,
                                        onValueChange = { newFileContent = it },
                                        label = { Text("Content") },
                                        modifier = Modifier.fillMaxWidth().height(150.dp)
                                    )
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    if (newFileName.isNotBlank()) {
                                        viewModel.createNewTextFile(currentPath, newFileName, newFileContent, isRemote) { success ->
                                            if (success) {
                                                android.widget.Toast.makeText(context, "File created", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "Failed to create file", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showNewFileDialog = false
                                        newFileName = ""
                                        newFileContent = ""
                                    }
                                }) {
                                    Text("Create")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    showNewFileDialog = false 
                                    newFileName = ""
                                    newFileContent = ""
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    IconButton(onClick = { viewModel.toggleRemoteLocal() }) {
                        Icon(
                            imageVector = if (isRemote) Icons.Default.Cloud else Icons.Default.Smartphone,
                            contentDescription = "Toggle Remote/Local"
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = filesState is UiState.Loading,
            onRefresh = { viewModel.loadFiles(currentPath, isRemote) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            when (val state = filesState) {
                is UiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is UiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Empty folder",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.data, key = { it.fullPath }) { file ->
                                FileListItem(
                                    modifier = Modifier.animateItem(),
                                    file = file,
                                    onClick = {
                                        if (file.isDirectory) {
                                            viewModel.loadFiles(file.fullPath, isRemote)
                                        } else if (isRemote) {
                                            val url = viewModel.streamFile(file)
                                            val mime = viewModel.getMimeType(file.name)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(Uri.parse(url), mime)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            context.startActivity(intent)
                                        } else {
                                            // Local files can be opened if needed
                                            val mime = viewModel.getMimeType(file.name)
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(Uri.parse("file://${file.fullPath}"), mime)
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                // Handle no activity found
                                            }
                                        }
                                    },
                                    onAction = {
                                        val targetParent = if (isRemote) viewModel.localPath.value else viewModel.remotePath.value
                                        val upload = !isRemote
                                        viewModel.transferItem(file, targetParent, upload)
                                    },
                                    isRemote = isRemote
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        }
    }

    if (transferProgress != null) {
        TransferBottomSheet(
            progress = transferProgress!!,
            onCancel = { viewModel.cancelTransfer() }
        )
    }
}

@Composable
fun FileListItem(
    modifier: Modifier = Modifier,
    file: FileItem,
    onClick: () -> Unit,
    onAction: () -> Unit,
    isRemote: Boolean
) {
    val icon = when {
        file.isDirectory -> Icons.Default.Folder
        file.name.endsWith(".mp4", ignoreCase = true) || file.name.endsWith(".mkv", ignoreCase = true) -> Icons.Default.VideoFile
        file.name.endsWith(".mp3", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) -> Icons.Default.AudioFile
        file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }

    Column(modifier = modifier) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (!file.isDirectory) {
            { Text(text = "${file.size / 1024} KB", style = MaterialTheme.typography.bodySmall) }
        } else null,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = {
            IconButton(onClick = onAction) {
                Icon(
                    imageVector = if (isRemote) Icons.Default.Download else Icons.Default.Upload,
                    contentDescription = if (isRemote) "Download" else "Upload",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferBottomSheet(
    progress: TransferProgress,
    onCancel: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Transferring: ${progress.currentFileName}", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress.percentage },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "${progress.bytesTransferred / 1024} KB / ${progress.totalBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                Text(text = "${progress.speedBps / 1024} KB/s", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
