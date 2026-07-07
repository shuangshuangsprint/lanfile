package com.example.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

data class FileItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val fullPath: String,
    val isRemote: Boolean
)

data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val currentFileName: String
) {
    val percentage: Float get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
}

class FileManager {

    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    
    private val connectedShares = mutableMapOf<String, DiskShare>()

    suspend fun connectAndLogin(config: SmbConnectionInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            smbClient = SMBClient()
            connection = smbClient!!.connect(config.ip)
            val authContext = AuthenticationContext(config.username, config.password.toCharArray(), "")
            session = connection!!.authenticate(authContext)
            true
        } catch (e: Exception) {
            Log.e("FileManager", "Failed to connect to SMB", e)
            false
        }
    }
    
    fun disconnect() {
        try {
            connectedShares.values.forEach { it.close() }
            connectedShares.clear()
            session?.close()
            connection?.close()
            smbClient?.close()
        } catch (e: Exception) {
            Log.e("FileManager", "Error during disconnect", e)
        } finally {
            session = null
            connection = null
            smbClient = null
        }
    }

    private fun getDiskShare(shareName: String): DiskShare? {
        val s = session ?: return null
        return connectedShares.getOrPut(shareName) {
            s.connectShare(shareName) as DiskShare
        }
    }

    private fun parseRemotePath(path: String): Pair<String, String> {
        val normalized = path.removePrefix("/")
        val parts = normalized.split("/", limit = 2)
        val shareName = parts.getOrNull(0) ?: ""
        val filePath = parts.getOrNull(1) ?: ""
        return Pair(shareName, filePath)
    }

    suspend fun listShares(): List<FileItem> = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext emptyList()
        try {
            val transport = com.rapid7.client.dcerpc.transport.SMBTransportFactories.SRVSVC.getTransport(currentSession)
            val srvsvc = com.rapid7.client.dcerpc.mssrvs.ServerService(transport)
            val shares = srvsvc.getShares0()
            
            shares.map {
                FileItem(
                    name = it.netName,
                    isDirectory = true,
                    size = 0,
                    fullPath = it.netName, // Just the share name for remote root
                    isRemote = true
                )
            }.filter { !it.name.endsWith("$") && it.name != "IPC$" } // Filter out hidden and IPC shares
        } catch (e: Exception) {
            Log.e("FileManager", "Error listing shares", e)
            emptyList()
        }
    }

    suspend fun listFolder(path: String, isRemote: Boolean): List<FileItem> = withContext(Dispatchers.IO) {
        if (isRemote) {
            val (shareName, filePath) = parseRemotePath(path)
            if (shareName.isEmpty()) {
                // Return empty list if no share is specified
                return@withContext emptyList()
            }
            try {
                val share = getDiskShare(shareName) ?: return@withContext emptyList()
                val list = share.list(filePath)
                
                list.mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == "..") return@mapNotNull null
                    
                    val isDir = (info.fileAttributes and 16L) != 0L // FILE_ATTRIBUTE_DIRECTORY is 0x10 (16)
                    
                    val fullPath = if (filePath.isEmpty()) {
                        "$shareName/$name"
                    } else {
                        "$shareName/$filePath/$name"
                    }
                    
                    FileItem(
                        name = name,
                        isDirectory = isDir,
                        size = info.endOfFile,
                        fullPath = fullPath,
                        isRemote = true
                    )
                }
            } catch (e: Exception) {
                Log.e("FileManager", "Failed to list remote folder: $path", e)
                emptyList()
            }
        } else {
            val f = java.io.File(path)
            f.listFiles()?.map {
                FileItem(
                    name = it.name,
                    isDirectory = it.isDirectory,
                    size = it.length(),
                    fullPath = it.absolutePath,
                    isRemote = false
                )
            } ?: emptyList()
        }
    }

    fun openInputStream(path: String, isRemote: Boolean, offset: Long = 0): InputStream? {
        if (isRemote) {
            val (shareName, filePath) = parseRemotePath(path)
            val share = getDiskShare(shareName) ?: return null
            try {
                val file = share.openFile(
                    filePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                return object : InputStream() {
                    private var currentOffset = offset
                    override fun read(): Int {
                        val b = ByteArray(1)
                        val r = read(b, 0, 1)
                        return if (r == -1) -1 else b[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (currentOffset >= file.fileInformation.standardInformation.endOfFile) return -1
                        return try {
                            val r = file.read(b, currentOffset, off, len)
                            if (r <= 0) -1 else {
                                currentOffset += r
                                r
                            }
                        } catch (e: Exception) {
                            -1
                        }
                    }

                    override fun close() {
                        try { file.close() } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("FileManager", "Error opening remote file", e)
                return null
            }
        } else {
            return try {
                val fis = FileInputStream(java.io.File(path))
                if (offset > 0) {
                    fis.skip(offset)
                }
                fis
            } catch (e: Exception) {
                Log.e("FileManager", "Error opening local file", e)
                null
            }
        }
    }

    suspend fun transferItem(
        source: FileItem,
        targetParentPath: String,
        upload: Boolean
    ): Flow<TransferProgress> = flow {
        val totalBytes = calculateTotalSize(source)
        var transferredBytes = 0L
        val startTime = System.currentTimeMillis()

        suspend fun transferRecursive(src: FileItem, destParent: String) {
            currentCoroutineContext().ensureActive()
            
            if (src.isDirectory) {
                val newDestParent = if (destParent.endsWith("/")) destParent + src.name else destParent + "/" + src.name
                
                createDirectory(newDestParent, isRemote = upload)
                
                val children = listFolder(src.fullPath, isRemote = !upload)
                for (child in children) {
                    transferRecursive(child, newDestParent)
                }
            } else {
                val destFilePath = if (destParent.endsWith("/")) destParent + src.name else destParent + "/" + src.name
                
                var inputStream: InputStream? = null
                var outputStream: OutputStream? = null
                var remoteFile: com.hierynomus.smbj.share.File? = null
                
                try {
                    if (upload) {
                        inputStream = FileInputStream(java.io.File(src.fullPath))
                        val (shareName, remoteFilePath) = parseRemotePath(destFilePath)
                        val remoteShare = getDiskShare(shareName)
                        if (remoteShare != null) {
                            // Ensure parent directories exist
                            val parentDir = remoteFilePath.substringBeforeLast("/", "")
                            if (parentDir.isNotEmpty()) {
                                ensureRemoteDirectoryExists(remoteShare, parentDir)
                            }

                            remoteFile = remoteShare.openFile(
                                remoteFilePath,
                                EnumSet.of(AccessMask.GENERIC_WRITE),
                                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                            )
                            outputStream = remoteFile.outputStream
                        }
                    } else {
                        val (shareName, remoteFilePath) = parseRemotePath(src.fullPath)
                        val remoteShare = getDiskShare(shareName)
                        if (remoteShare != null) {
                            remoteFile = remoteShare.openFile(
                                remoteFilePath,
                                EnumSet.of(AccessMask.GENERIC_READ),
                                null,
                                SMB2ShareAccess.ALL,
                                SMB2CreateDisposition.FILE_OPEN,
                                null
                            )
                            inputStream = remoteFile.inputStream
                        }
                        
                        val destFile = java.io.File(destFilePath)
                        destFile.parentFile?.mkdirs()
                        outputStream = FileOutputStream(destFile)
                    }

                    if (inputStream != null && outputStream != null) {
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            outputStream.write(buffer, 0, bytesRead)
                            transferredBytes += bytesRead
                            
                            val elapsedMillis = System.currentTimeMillis() - startTime
                            val speedBps = if (elapsedMillis > 0) (transferredBytes * 1000) / elapsedMillis else 0L
                            
                            emit(
                                TransferProgress(
                                    bytesTransferred = transferredBytes,
                                    totalBytes = totalBytes,
                                    speedBps = speedBps,
                                    currentFileName = src.name
                                )
                            )
                        }
                        outputStream.flush()
                    }
                } catch (e: Exception) {
                    Log.e("FileManager", "Error transferring file: ${src.name}", e)
                    throw e
                } finally {
                    try { inputStream?.close() } catch (e: Exception) { /* ignore */ }
                    try { outputStream?.close() } catch (e: Exception) { /* ignore */ }
                    try { remoteFile?.close() } catch (e: Exception) { /* ignore */ }
                }
            }
        }
        
        transferRecursive(source, targetParentPath)
    }.flowOn(Dispatchers.IO)

    suspend fun writeTextFile(path: String, content: String, isRemoteMode: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (isRemoteMode) {
            val (shareName, filePath) = parseRemotePath(path)
            try {
                val share = getDiskShare(shareName) ?: return@withContext false
                val file = share.openFile(
                    filePath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
                file.outputStream.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            } catch (e: Exception) {
                Log.e("FileManager", "Error writing remote text file", e)
                false
            }
        } else {
            try {
                val file = java.io.File(path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                true
            } catch (e: Exception) {
                Log.e("FileManager", "Error writing local text file", e)
                false
            }
        }
    }

    suspend fun readTextFile(remotePath: String): String? = withContext(Dispatchers.IO) {
        val (shareName, filePath) = parseRemotePath(remotePath)
        try {
            val share = getDiskShare(shareName) ?: return@withContext null
            if (!share.fileExists(filePath)) return@withContext null
            val file = share.openFile(
                filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            file.inputStream.use { it ->
                it.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Log.e("FileManager", "Error reading text file", e)
            null
        }
    }

    private suspend fun calculateTotalSize(item: FileItem): Long {
        if (!item.isDirectory) return item.size
        
        var total = 0L
        val children = listFolder(item.fullPath, item.isRemote)
        for (child in children) {
            total += calculateTotalSize(child)
        }
        return total
    }

    private fun ensureRemoteDirectoryExists(share: DiskShare, path: String) {
        val parts = path.split("/")
        var currentPath = ""
        for (part in parts) {
            if (part.isEmpty()) continue
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            if (!share.folderExists(currentPath)) {
                try {
                    share.mkdir(currentPath)
                } catch (e: Exception) {
                    Log.e("FileManager", "Failed to create directory $currentPath", e)
                }
            }
        }
    }

    private suspend fun createDirectory(path: String, isRemote: Boolean) = withContext(Dispatchers.IO) {
        if (isRemote) {
            val (shareName, dirPath) = parseRemotePath(path)
            try {
                val share = getDiskShare(shareName)
                if (share != null && dirPath.isNotEmpty()) {
                    ensureRemoteDirectoryExists(share, dirPath)
                }
            } catch (e: Exception) {
                Log.e("FileManager", "Failed to create remote directory: $path", e)
            }
        } else {
            java.io.File(path).mkdirs()
        }
    }
}
