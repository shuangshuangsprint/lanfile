package com.example.smb

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionStorage = ConnectionStorage(application)
    val savedConnection = connectionStorage.connectionFlow
    
    val fileManager = FileManager()
    private val scanner = SmbDeviceScanner(application)
    
    private val customNamesPrefs = application.getSharedPreferences("device_names", android.content.Context.MODE_PRIVATE)
    private val _customNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val customNames = _customNames.asStateFlow()

    init {
        updateCustomNames()
    }

    private fun updateCustomNames() {
        val names = customNamesPrefs.all.mapNotNull { 
            if (it.value is String) it.key to it.value as String else null 
        }.toMap()
        _customNames.value = names
    }

    fun setCustomDeviceName(ip: String, name: String) {
        if (name.isBlank()) {
            customNamesPrefs.edit().remove(ip).apply()
        } else {
            customNamesPrefs.edit().putString(ip, name).apply()
        }
        updateCustomNames()
    }

    private val _scannedDevices = MutableStateFlow<List<SmbDevice>>(emptyList())
    val scannedDevices: StateFlow<List<SmbDevice>> = _scannedDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _connectionState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val connectionState = _connectionState.asStateFlow()
    
    private val _currentServerName = MutableStateFlow<String?>(null)
    val currentServerName = _currentServerName.asStateFlow()

    private val _filesState = MutableStateFlow<UiState<List<FileItem>>>(UiState.Idle)
    val filesState = _filesState.asStateFlow()
    
    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress = _transferProgress.asStateFlow()
    
    private var scanJob: Job? = null
    private var transferJob: Job? = null
    private var streamServer: SmbStreamServer? = null
    
    var remotePath = MutableStateFlow("")
    var localPath = MutableStateFlow(application.getExternalFilesDir(null)?.absolutePath ?: application.filesDir.absolutePath)
    var isRemote = MutableStateFlow(true)
    
    val currentPath: StateFlow<String> get() = if (isRemote.value) remotePath.asStateFlow() else localPath.asStateFlow()
    
    fun toggleRemoteLocal() {
        isRemote.value = !isRemote.value
        loadFiles(if (isRemote.value) remotePath.value else localPath.value, isRemote.value)
    }

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _scannedDevices.value = emptyList()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scanLocalNetwork()
                .catch { e ->
                    Log.e("MainViewModel", "Scan error", e)
                }
                .collect { device ->
                    val list = _scannedDevices.value.toMutableList()
                    if (list.none { it.ip == device.ip }) {
                        list.add(device)
                        _scannedDevices.value = list
                    }
                }
            _isScanning.value = false
        }
    }
    
    fun resetConnectionState() {
        _connectionState.value = UiState.Idle
    }

    fun connectAndSave(config: SmbConnectionInfo, remember: Boolean) {
        viewModelScope.launch {
            _connectionState.value = UiState.Loading
            val success = fileManager.connectAndLogin(config)
            if (success) {
                if (remember) {
                    connectionStorage.saveConnection(config)
                }
                _currentServerName.value = config.name
                remotePath.value = config.shareName
                _connectionState.value = UiState.Success(Unit)
            } else {
                _connectionState.value = UiState.Error("Connection failed or invalid credentials")
            }
        }
    }

    fun deleteSavedConnection() {
        viewModelScope.launch {
            connectionStorage.clearConnection()
        }
    }

    fun createNewTextFile(currentPath: String, fileName: String, content: String = "", isRemoteMode: Boolean, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (currentPath.isEmpty()) {
                withContext(Dispatchers.Main) { onComplete(false) }
                return@launch
            }
            val safeName = if (fileName.endsWith(".txt")) fileName else "$fileName.txt"
            val fullPath = if (currentPath.endsWith("/")) "$currentPath$safeName" else "$currentPath/$safeName"
            
            val success = fileManager.writeTextFile(fullPath, content, isRemoteMode)
            if (success) {
                loadFiles(currentPath, isRemoteMode)
            }
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }
    
    fun loadFiles(path: String, remote: Boolean) {
        viewModelScope.launch {
            _filesState.value = UiState.Loading
            try {
                if (remote) {
                    remotePath.value = path
                } else {
                    localPath.value = path
                }
                isRemote.value = remote
                
                val files = if (remote && path.isEmpty()) {
                    fileManager.listShares()
                } else {
                    fileManager.listFolder(path, remote)
                }
                _filesState.value = UiState.Success(files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
            } catch (e: Exception) {
                _filesState.value = UiState.Error(e.message ?: "Failed to list files")
            }
        }
    }
    
    fun transferItem(source: FileItem, targetParentPath: String, upload: Boolean) {
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            _transferProgress.value = TransferProgress(0, 0, 0, source.name)
            try {
                fileManager.transferItem(source, targetParentPath, upload)
                    .catch { e ->
                        Log.e("MainViewModel", "Transfer error", e)
                        _transferProgress.value = null
                    }
                    .collect { progress ->
                        _transferProgress.value = progress
                    }
                // Transfer complete
                _transferProgress.value = null
                // Reload current directory if we are in the target directory
                // For simplicity, just reload
                loadFiles(currentPath.value, isRemote.value)
            } catch (e: Exception) {
                 Log.e("MainViewModel", "Transfer error", e)
                _transferProgress.value = null
            }
        }
    }
    
    fun cancelTransfer() {
        transferJob?.cancel()
        _transferProgress.value = null
    }

    fun streamFile(file: FileItem): String {
        streamServer?.stop()
        
        val mimeType = getMimeType(file.name)
        streamServer = SmbStreamServer(fileManager, file.fullPath, file.isRemote, file.size, mimeType).apply {
            start()
        }
        return "http://localhost:${streamServer!!.listeningPort}/stream"
    }

    fun getMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamServer?.stop()
        fileManager.disconnect()
    }
}
