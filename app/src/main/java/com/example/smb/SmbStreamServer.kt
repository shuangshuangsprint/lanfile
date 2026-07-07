package com.example.smb

import fi.iki.elonen.NanoHTTPD

class SmbStreamServer(
    private val fileManager: FileManager,
    private val path: String,
    private val isRemote: Boolean,
    private val fileSize: Long,
    private val mimeType: String
) : NanoHTTPD(0) { // 0 binds to a random available port

    override fun serve(session: IHTTPSession): Response {
        var startFrom: Long = 0
        var endAt: Long = fileSize - 1

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring("bytes=".length)
            val minus = range.indexOf('-')
            try {
                if (minus > 0) {
                    startFrom = range.substring(0, minus).toLong()
                    val endStr = range.substring(minus + 1)
                    if (endStr.isNotEmpty()) {
                        endAt = endStr.toLong()
                    }
                } else if (minus == 0) {
                    val suffix = range.substring(1).toLong()
                    startFrom = fileSize - suffix
                } else {
                    startFrom = range.toLong()
                }
            } catch (ignored: NumberFormatException) {
            }
        }

        if (endAt >= fileSize) {
            endAt = fileSize - 1
        }
        var dataLen = endAt - startFrom + 1
        if (dataLen < 0) dataLen = 0

        val inputStream = fileManager.openInputStream(path, isRemote, startFrom)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File not found")

        val res = newFixedLengthResponse(
            if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mimeType,
            inputStream,
            dataLen
        )

        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Length", "$dataLen")
        if (rangeHeader != null) {
            res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileSize")
        }
        return res
    }
}
