package com.example.smb

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NetBiosHelper {
    fun getNetBiosName(ip: String): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1000
            val address = InetAddress.getByName(ip)
            
            // NetBIOS Name Query Packet (Node Status)
            val transactionId = byteArrayOf(0x00, 0x00)
            val flags = byteArrayOf(0x00, 0x10)
            val questions = byteArrayOf(0x00, 0x01)
            val answerRRs = byteArrayOf(0x00, 0x00)
            val authorityRRs = byteArrayOf(0x00, 0x00)
            val additionalRRs = byteArrayOf(0x00, 0x00)
            
            // Question section: Node Status for *
            val qname = byteArrayOf(
                0x20,
                0x43, 0x4b, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x00
            )
            val qtype = byteArrayOf(0x00, 0x21) // Node Status
            val qclass = byteArrayOf(0x00, 0x01) // Internet
            
            val packetData = transactionId + flags + questions + answerRRs + authorityRRs + additionalRRs + qname + qtype + qclass
            
            val packet = DatagramPacket(packetData, packetData.size, address, 137)
            socket.send(packet)
            
            val receiveData = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)
            socket.receive(receivePacket)
            
            var offset = 12 + 34 + 34 + 10
            if (offset < receivePacket.length) {
                val numNames = receiveData[offset].toInt() and 0xFF
                offset++
                for (i in 0 until numNames) {
                    val nameBytes = receiveData.copyOfRange(offset, offset + 15)
                    val nameType = receiveData[offset + 15].toInt() and 0xFF
                    offset += 18
                    
                    if (nameType == 0x00 || nameType == 0x20) {
                        val name = String(nameBytes, Charsets.US_ASCII).trim()
                        if (name.isNotEmpty() && !name.contains("__MSBROWSE__")) {
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            socket?.close()
        }
        return null
    }
}
