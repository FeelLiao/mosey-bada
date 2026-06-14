/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import android.content.Context
import android.os.Build
import dev.bluehouse.bada.protocol.connection.FileSource
import com.dd.plist.NSDictionary
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

public sealed interface AirDropSendResult {
    public data object Completed : AirDropSendResult
    public data object Rejected : AirDropSendResult
    public data class Failed(val reason: String) : AirDropSendResult
}

public class AirDropClient(private val context: Context) {
    public fun send(
        host: String,
        port: Int,
        files: List<FileSource>,
    ): AirDropSendResult =
        runCatching {
            val discover = post(host, port, "/Discover", AirDropPlist.encode(NSDictionary()), "application/octet-stream")
            if (discover.status !in 200..299) error("Discover failed with HTTP ${discover.status}")
            val receiver = AirDropPlist.decode(discover.body)
            val receiverName = AirDropPlist.string(receiver, "ReceiverComputerName") ?: "AirDrop receiver"

            val ask = buildAsk(files)
            val askResponse = post(host, port, "/Ask", AirDropPlist.encode(ask), "application/octet-stream")
            if (askResponse.status !in 200..299) return@runCatching AirDropSendResult.Rejected

            val archive = AirDropArchive.create(context.cacheDir, files)
            try {
                val upload = archive.inputStream().buffered().use { body ->
                    post(
                        host,
                        port,
                        "/Upload",
                        body,
                        archive.length(),
                        "application/x-dvzip",
                        mapOf("TotalBytes" to archive.length().toString()),
                    )
                }
                if (upload.status !in 200..299) error("Upload to $receiverName failed with HTTP ${upload.status}")
            } finally {
                archive.delete()
            }
            AirDropSendResult.Completed
        }.getOrElse { AirDropSendResult.Failed(it.message ?: it.javaClass.simpleName) }

    private fun buildAsk(files: List<FileSource>): NSDictionary =
        NSDictionary().apply {
            put("SenderID", plistString("bada-${java.util.UUID.randomUUID()}"))
            put("SenderComputerName", plistString(Build.MODEL))
            put("SenderModelName", plistString(Build.MODEL))
            put("BundleID", plistString("com.apple.finder"))
            put("ConvertMediaFormats", plistBoolean(false))
            put("Files", plistArray(files.map { source ->
                NSDictionary().apply {
                    put("ConvertMediaFormats", plistLong(0))
                    put("FileIsDirectory", plistBoolean(false))
                    put("FileName", plistString(source.name))
                    put("FileType", plistString(utiFor(source)))
                    put("FileSize", plistLong(source.size))
                    val relative = listOf(source.parentFolder, source.name).filter { it.isNotBlank() }.joinToString("/")
                    put("FileBomPath", plistString("./$relative"))
                    put("ShouldConvertMediaFormats", plistBoolean(false))
                }
            }))
        }

    private fun utiFor(source: FileSource): String =
        when {
            source.mimeType.startsWith("image/") -> "public.image"
            source.mimeType.startsWith("video/") -> "public.movie"
            source.mimeType == "application/pdf" -> "com.adobe.pdf"
            source.mimeType.startsWith("text/") -> "public.plain-text"
            else -> "public.data"
        }

    private data class Response(val status: Int, val body: ByteArray)

    private fun post(
        host: String,
        port: Int,
        path: String,
        body: ByteArray,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response = body.inputStream().use { post(host, port, path, it, body.size.toLong(), contentType, extraHeaders) }

    private fun post(
        host: String,
        port: Int,
        path: String,
        body: java.io.InputStream,
        bodyLength: Long,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val socket = sslContext.socketFactory.createSocket(InetAddress.getByName(host), port) as SSLSocket
        socket.soTimeout = 120_000
        socket.use { ssl ->
            ssl.startHandshake()
            val output = BufferedOutputStream(ssl.outputStream)
            output.write("POST $path HTTP/1.1\r\n".toByteArray())
            output.write("Host: [$host]:$port\r\n".toByteArray())
            output.write("User-Agent: AirDrop/1.0\r\nConnection: close\r\n".toByteArray())
            output.write("Content-Type: $contentType\r\nContent-Length: $bodyLength\r\n".toByteArray())
            extraHeaders.forEach { (name, value) -> output.write("$name: $value\r\n".toByteArray()) }
            output.write("\r\n".toByteArray())
            body.copyTo(output)
            output.flush()

            val input = BufferedInputStream(ssl.inputStream)
            val header = readHeader(input)
            val lines = header.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP response")
            val length = lines.firstNotNullOfOrNull { line ->
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", true)) {
                    line.substring(colon + 1).trim().toIntOrNull()
                } else null
            } ?: 0
            return Response(status, input.readNBytes(length))
        }
    }

    private fun readHeader(input: BufferedInputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) error("EOF before HTTP headers")
            output.write(value)
            matched = if (value == HEADER_END[matched].toInt()) matched + 1 else if (value == '\r'.code) 1 else 0
            if (matched == HEADER_END.size) return output.toString(Charsets.US_ASCII.name())
        }
        error("HTTP headers too large")
    }

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray()
        val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            }), SecureRandom())
        }
    }
}
