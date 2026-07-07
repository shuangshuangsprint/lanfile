package com.example.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

class SmbDeviceScanner(private val context: Context) {

    /**
     * Scans the local subnet for devices listening on port 445 (SMB).
     */
    fun scanLocalNetwork(): Flow<SmbDevice> = flow {
        val ipsToScan = getLocalSubnetIps()
        
        // Scan in batches to avoid too many concurrent threads/sockets
        val batchSize = 50
        ipsToScan.chunked(batchSize).forEach { batch ->
            val foundDevices = coroutineScope {
                batch.map { ip ->
                    async(Dispatchers.IO) {
                        if (isPortOpen(ip, 445, 500)) {
                            var name = NetBiosHelper.getNetBiosName(ip)
                            if (name == null) {
                                name = try {
                                    val hostName = java.net.InetAddress.getByName(ip).hostName
                                    if (hostName != ip) hostName else null
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            
                            val finalName = if (name != null) {
                                if (name.lowercase().startsWith("android-") || name.lowercase() == "android") {
                                    "Android"
                                } else {
                                    name
                                }
                            } else {
                                "Computer"
                            }
                            
                            SmbDevice(ip, finalName)
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            
            foundDevices.forEach {
                emit(it)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getLocalSubnetIps(): List<String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return emptyList()

        val ips = mutableListOf<String>()
        
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                val prefixLength = linkAddress.prefixLength
                val addressBytes = address.address
                
                // Only handle /24 subnets for simplicity in this example
                if (prefixLength == 24) {
                    val baseIp = "${addressBytes[0].toInt() and 0xFF}.${addressBytes[1].toInt() and 0xFF}.${addressBytes[2].toInt() and 0xFF}"
                    // Start from 1 to 254
                    for (i in 1..254) {
                        val currentIp = "$baseIp.$i"
                        if (currentIp != address.hostAddress) {
                            ips.add(currentIp)
                        }
                    }
                }
            }
        }
        return ips
    }
}
