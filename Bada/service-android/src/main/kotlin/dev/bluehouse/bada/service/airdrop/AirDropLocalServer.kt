/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import android.content.Context
import android.os.Build
import android.util.Log
import com.dd.plist.NSDictionary
import dev.bluehouse.bada.service.downloads.DownloadsWriterFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

public class AirDropLocalServer(context: Context) : Closeable {
    private val app = context.applicationContext
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var acceptedOffer: List<AirDropFileOffer>? = null

    public fun start() {
        if (!running.compareAndSet(false, true)) return
        server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        workers.execute {
            while (running.get()) {
                try {
                    val socket = server?.accept() ?: break
                    workers.execute { handle(socket) }
                } catch (t: Throwable) {
                    if (running.get()) Log.w(TAG, "AirDrop local accept failed", t)
                }
            }
        }
        Log.i(TAG, "Bada AirDrop transfer server listening on 127.0.0.1:$PORT")
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 130_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val request = readRequest(input)
                when (request.path) {
                    "/Ask" -> handleAsk(request, output)
                    "/Upload" -> handleUpload(request, input, output)
                    else -> respond(output, 404, ByteArray(0))
                }
            }.onFailure { Log.w(TAG, "AirDrop local request failed", it) }
        }
    }

    private fun handleAsk(request: Request, output: BufferedOutputStream) {
        val body = request.readBody()
        val plist = AirDropPlist.decode(body)
        val sender = AirDropPlist.string(plist, "SenderComputerName") ?: "Apple device"
        val offers = AirDropPlist.fileOffers(plist)
        if (offers.isEmpty()) {
            respond(output, 400, ByteArray(0))
            return
        }
        val consent = AirDropConsentRegistry.create(sender, offers)
        AirDropConsentNotification.post(app, consent)
        val accepted = try { consent.await() } finally {
            AirDropConsentRegistry.remove(consent.id)
            AirDropConsentNotification.dismiss(app, consent.id)
        }
        if (!accepted) {
            respond(output, 403, ByteArray(0))
            return
        }
        acceptedOffer = offers
        val response = NSDictionary().apply {
            put("ReceiverComputerName", plistString(Build.MODEL))
            put("ReceiverModelName", plistString(Build.MODEL))
        }
        respond(output, 200, AirDropPlist.encode(response))
    }

    private fun handleUpload(request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        val offers = acceptedOffer
        if (offers == null) {
            respond(output, 409, ByteArray(0))
            return
        }
        val contentType = request.headers["content-type"].orEmpty().lowercase()
        if (contentType != "application/x-dvzip" && contentType != "application/x-cpio") {
            respond(output, 415, ByteArray(0))
            return
        }
        val body = request.bodyStream(input)
        var received = 0
        AirDropArchive.extract(body) { path, _, entryInput ->
            val name = path.substringAfterLast('/')
            val parent = path.substringBeforeLast('/', "")
            val offer = offers.firstOrNull { it.bomPath.removePrefix("./") == path || it.name == name }
            DownloadsWriterFactory.saveAirDropFile(
                context = app,
                fileName = name,
                mimeType = airDropMimeType(offer?.uti ?: "public.data", name),
                parentFolder = parent,
                input = entryInput,
            )
            received++
        }
        acceptedOffer = null
        if (received == 0) respond(output, 400, ByteArray(0)) else respond(output, 200, ByteArray(0))
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        workers.shutdownNow()
    }

    private data class Request(
        val path: String,
        val headers: Map<String, String>,
        val input: BufferedInputStream,
    ) {
        fun readBody(): ByteArray = bodyStream(input).readBytes()
        fun bodyStream(source: BufferedInputStream): InputStream {
            val length = headers["content-length"]?.toLongOrNull()
            return when {
                headers["transfer-encoding"]?.contains("chunked", true) == true -> ChunkedInputStream(source)
                length != null -> LimitedInputStream(source, length)
                else -> LimitedInputStream(source, 0)
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): Request {
        val header = readHeader(input)
        val lines = header.split("\r\n")
        val path = lines.first().split(' ').getOrNull(1) ?: error("Invalid request line")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        return Request(path, headers, input)
    }

    private fun readHeader(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n".toByteArray()
        while (output.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) error("EOF before request headers")
            output.write(value)
            matched = if (value == end[matched].toInt()) matched + 1 else if (value == '\r'.code) 1 else 0
            if (matched == end.size) return output.toString(Charsets.US_ASCII.name())
        }
        error("Request headers too large")
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 415 -> "Unsupported Media Type"; else -> "Error" }
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining <= 0) -1 else source.read().also { if (it >= 0) remaining-- }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val read = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }

    private class ChunkedInputStream(private val source: BufferedInputStream) : InputStream() {
        private var remaining = 0
        private var finished = false
        override fun read(): Int { val one = ByteArray(1); return if (read(one) < 0) -1 else one[0].toInt() and 0xff }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (finished) return -1
            if (remaining == 0) {
                val line = readLine(source)
                remaining = line.substringBefore(';').trim().toInt(16)
                if (remaining == 0) { readLine(source); finished = true; return -1 }
            }
            val read = source.read(buffer, offset, minOf(length, remaining))
            if (read < 0) error("EOF inside HTTP chunk")
            remaining -= read
            if (remaining == 0) readLine(source)
            return read
        }
        private fun readLine(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) { val b = input.read(); if (b < 0) error("EOF in chunk header"); if (b == '\n'.code) break; if (b != '\r'.code) out.write(b) }
            return out.toString(Charsets.US_ASCII.name())
        }
    }

    private companion object { const val TAG = "BadaAirDrop"; const val PORT = 19541 }
}
