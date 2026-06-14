/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** TCP client for the KernelSU-managed Mosey bridge. */
public class MoseySocketClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {

    private data class BridgeReply(val status: Int, val data: ByteArray)

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val eventHandler = AtomicReference<MoseyEventHandler?>(null)
    private val connected = AtomicBoolean(false)
    private val readerStarted = AtomicBoolean(false)
    private val pendingReply = AtomicReference<ArrayBlockingQueue<BridgeReply>?>(null)
    private val endpointByName = ConcurrentHashMap<String, String>()
    private val requestLock = Any()

    internal companion object {
        private const val TAG = "MoseySocketClient"
        internal const val DEFAULT_HOST = "127.0.0.1"
        internal const val DEFAULT_PORT = 19539
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 3000
        private const val DEFAULT_READ_TIMEOUT_MS = 30000
        private const val MAX_FRAME_SIZE = 64 * 1024

        private const val FRAME_REQUEST: Byte = 0x01
        private const val FRAME_REPLY: Byte = 0x02
        private const val FRAME_EVENT: Byte = 0x03
        private const val CMD_GET_VERSION: Byte = 0
        private const val CMD_START: Byte = 1
        private const val CMD_STOP: Byte = 2
        private const val CMD_UPDATE: Byte = 3
        private const val CMD_SUBSCRIBE: Byte = 4
        private const val EVENT_AIRDROP_FOUND: Byte = 0x01
        private const val EVENT_AIRDROP_LOST: Byte = 0x02
        private const val EVENT_APPLE_BLE_SEEN: Byte = 0x03
    }

    public fun connect(): Boolean {
        if (connected.get()) return true
        return try {
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            newSocket.soTimeout = readTimeoutMs
            newSocket.tcpNoDelay = true
            socket = newSocket
            outputStream = newSocket.getOutputStream()
            inputStream = newSocket.getInputStream()
            connected.set(true)
            startReader()
            val subscribed = sendRequest(CMD_SUBSCRIBE).status == 0
            if (!subscribed) throw IllegalStateException("Bridge rejected event subscription")
            Log.i(TAG, "Connected and subscribed to mosey_bridge at $host:$port")
            eventHandler.get()?.onConnected()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Bridge connection failed: ${e.message}")
            cleanup()
            false
        }
    }

    public fun isAlive(): Boolean = try {
        getVersion() >= 0
    } catch (_: Exception) {
        false
    }

    public fun getVersion(): Int {
        ensureConnected()
        val reply = sendRequest(CMD_GET_VERSION)
        return if (reply.status == 0 && reply.data.size >= 4) readInt32(reply.data, 0) else -1
    }

    public fun start(mediumFilters: IntArray): Boolean {
        ensureConnected()
        val reply = sendRequest(CMD_START, buildStartParams(mediumFilters))
        return reply.status == 0 && reply.data.size >= 4 && readInt32(reply.data, 0) == 0
    }

    public fun stop(): Boolean {
        if (!connected.get()) return true
        return try {
            val reply = sendRequest(CMD_STOP)
            reply.status == 0 && reply.data.size >= 4 && readInt32(reply.data, 0) == 0
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
            false
        }
    }

    public fun update(countryCode: String): Boolean {
        ensureConnected()
        val reply = sendRequest(CMD_UPDATE, countryCode.encodeToByteArray())
        return reply.status == 0 && reply.data.size >= 4 && readInt32(reply.data, 0) == 0
    }

    public fun setEventHandler(handler: MoseyEventHandler) {
        eventHandler.set(handler)
        if (connected.get()) handler.onConnected()
    }

    public fun clearEventHandler() {
        eventHandler.set(null)
    }

    override fun close() {
        connected.set(false)
        cleanup()
    }

    private fun sendRequest(cmd: Byte, params: ByteArray = ByteArray(0)): BridgeReply {
        synchronized(requestLock) {
            ensureConnected()
            val queue = ArrayBlockingQueue<BridgeReply>(1)
            check(pendingReply.compareAndSet(null, queue)) { "A request is already pending" }
            try {
                val payload = byteArrayOf(cmd) + params
                val out = outputStream ?: error("Not connected")
                out.write(buildFrame(FRAME_REQUEST, payload))
                out.flush()
                return queue.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("Command $cmd timed out after ${readTimeoutMs}ms")
            } finally {
                pendingReply.compareAndSet(queue, null)
            }
        }
    }

    private fun startReader() {
        if (!readerStarted.compareAndSet(false, true)) return
        thread(name = "MoseyBridgeReader", isDaemon = true) {
            var disconnectReason = "Bridge closed the connection"
            try {
                val input = inputStream ?: return@thread
                while (connected.get()) {
                    try {
                        val header = ByteArray(5)
                        readExact(input, header, header.size)
                        val length = readInt32(header, 1)
                        if (length < 0 || length > MAX_FRAME_SIZE) {
                            throw IllegalStateException("Invalid bridge frame length $length")
                        }
                        val payload = ByteArray(length)
                        if (length > 0) readExact(input, payload, length)
                        when (header[0]) {
                            FRAME_REPLY -> dispatchReply(payload)
                            FRAME_EVENT -> handleEvent(payload)
                            else -> Log.w(TAG, "Unknown bridge frame type ${header[0]}")
                        }
                    } catch (_: SocketTimeoutException) {
                        // A quiet connection is healthy; requests have their own timeout.
                    }
                }
            } catch (e: Exception) {
                disconnectReason = e.message ?: e.javaClass.simpleName
                if (connected.get()) Log.w(TAG, "Bridge reader stopped: $disconnectReason")
            } finally {
                val wasConnected = connected.getAndSet(false)
                pendingReply.get()?.offer(BridgeReply(-1, ByteArray(0)))
                readerStarted.set(false)
                if (wasConnected) eventHandler.get()?.onDisconnected(disconnectReason)
            }
        }
    }

    private fun dispatchReply(payload: ByteArray) {
        if (payload.size < 4) {
            Log.w(TAG, "Short bridge reply: ${payload.size}")
            pendingReply.get()?.offer(BridgeReply(-1, ByteArray(0)))
            return
        }
        val reply = BridgeReply(readInt32(payload, 0), payload.copyOfRange(4, payload.size))
        if (pendingReply.get()?.offer(reply) != true) {
            Log.w(TAG, "Orphan bridge reply status=${reply.status}")
        }
    }

    private fun handleEvent(payload: ByteArray) {
        if (payload.size < 2) return
        val eventType = payload[0]
        val jsonText = payload.copyOfRange(1, payload.size).decodeToString()
        try {
            val json = JSONObject(jsonText)
            when (eventType) {
                EVENT_AIRDROP_FOUND -> handleFound(json)
                EVENT_AIRDROP_LOST -> handleLost(json)
                EVENT_APPLE_BLE_SEEN -> Log.d(TAG, "Apple BLE wakeup observed: $jsonText")
                else -> Log.w(TAG, "Unknown Mosey event type=$eventType")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid Mosey event JSON: ${e.message}")
        }
    }

    private fun handleFound(json: JSONObject) {
        val name = json.optString("name").ifBlank { "AirDrop device" }
        val eventHost = json.optString("host").takeIf { it.isNotBlank() }
        val eventPort = json.optInt("port", -1).takeIf { it in 1..65535 }
        if (eventHost == null || eventPort == null) {
            Log.w(TAG, "Ignoring unresolved AirDrop service: $json")
            return
        }
        val endpointId = "airdrop:$eventHost:$eventPort"
        endpointByName[name] = endpointId
        val ipv6 = eventHost.takeIf { it.contains(':') }
        val ipv4 = eventHost.takeUnless { it.contains(':') }
        eventHandler.get()?.onDeviceDiscovered(
            AppleDevice(
                endpointId = endpointId,
                deviceName = name,
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = eventPort,
            ),
        )
    }

    private fun handleLost(json: JSONObject) {
        val name = json.optString("name")
        val endpointId = endpointByName.remove(name) ?: "airdrop:name:$name"
        eventHandler.get()?.onDeviceLost(endpointId)
    }

    private fun cleanup() {
        connected.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
        pendingReply.get()?.offer(BridgeReply(-1, ByteArray(0)))
    }

    private fun ensureConnected() {
        check(connected.get()) { "Not connected to mosey_bridge" }
    }

    private fun buildStartParams(filters: IntArray): ByteArray {
        require(filters.size <= 255) { "Too many filters" }
        val data = ByteArray(1 + filters.size * 4)
        data[0] = filters.size.toByte()
        filters.forEachIndexed { index, value -> writeInt32(data, 1 + index * 4, value) }
        return data
    }

    private fun buildFrame(type: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = type
        writeInt32(frame, 1, payload.size)
        payload.copyInto(frame, 5)
        return frame
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw IllegalStateException("Unexpected end of bridge stream")
            offset += read
        }
    }

    private fun readInt32(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xff) or
            ((buffer[offset + 1].toInt() and 0xff) shl 8) or
            ((buffer[offset + 2].toInt() and 0xff) shl 16) or
            ((buffer[offset + 3].toInt() and 0xff) shl 24)

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
        buffer[offset + 3] = (value ushr 24).toByte()
    }
}
