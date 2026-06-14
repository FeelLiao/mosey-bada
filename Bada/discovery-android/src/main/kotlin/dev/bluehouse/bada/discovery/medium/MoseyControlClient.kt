/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight control-plane client for the mosey_bridge.
 *
 * Unlike [MoseySocketClient], this client does NOT send CMD_SUBSCRIBE,
 * so it won't steal the event subscription. It is intended for the
 * settings/control UI to call enable/disable/status/wakeBada without
 * interfering with the discovery event stream.
 *
 * Each connection is short-lived (connect → send cmd → receive reply → close).
 * For repeated status polling, call [status] and let it reconnect each time;
 * the bridge accepts multiple concurrent control connections.
 */
public class MoseyControlClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 19539,
    private val connectTimeoutMs: Int = 3000,
) {
    private data class BridgeReply(val status: Int, val data: ByteArray)

    public companion object {
        private const val TAG = "MoseyControlClient"

        // Frame protocol (shared with MoseySocketClient)
        private const val FRAME_REQUEST: Byte = 0x01
        private const val FRAME_REPLY: Byte = 0x02
        private const val MAX_FRAME_SIZE = 64 * 1024

        // 命令级超时
        private const val STATUS_TIMEOUT_MS = 5_000
        private const val DISABLE_TIMEOUT_MS = 15_000
        private const val ENABLE_TIMEOUT_MS = 60_000
    }

    /**
     * Enable the full mosey stack: disconnect Wi-Fi, start mosey_server,
     * mosey_bridge, and shim foreground service.
     */
    public fun enable(): Boolean {
        val reply = execCmd(MoseySocketClient.CMD_ENABLE, readTimeoutMs = ENABLE_TIMEOUT_MS)
        return reply != null && reply.status == 0
    }

    /**
     * Disable the mosey stack: stop shim, stop mosey_server/bridge,
     * re-enable Wi-Fi.
     */
    public fun disable(): Boolean {
        val reply = execCmd(MoseySocketClient.CMD_DISABLE, readTimeoutMs = DISABLE_TIMEOUT_MS)
        return reply != null && reply.status == 0
    }

    /**
     * Query current mosey stack status from the bridge.
     * Returns parsed [MoseyStatus] on success, or null if the bridge
     * is unreachable or returns an error.
     */
    public fun status(): MoseyStatus? {
        val reply = execCmd(MoseySocketClient.CMD_STATUS, readTimeoutMs = STATUS_TIMEOUT_MS) ?: return null
        if (reply.status != 0 || reply.data.size < 4) return null
        val jsonLen = readInt32(reply.data, 0)
        if (jsonLen !in 1..4096) return null
        val jsonStr = reply.data.copyOfRange(4, 4 + jsonLen).decodeToString()
        return try {
            MoseyStatus.fromJson(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse status JSON: ${e.message}")
            null
        }
    }

    /**
     * Send a wake-up broadcast to Bada's [AirDropWakeReceiver],
     * which starts [ReceiverForegroundService] to accept incoming AirDrop.
     */
    public fun wakeBada(): Boolean {
        val reply = execCmd(MoseySocketClient.CMD_WAKE_BADA)
        return reply != null && reply.status == 0
    }

    // ── low-level command execution ──

    private fun execCmd(cmd: Byte, params: ByteArray = ByteArray(0), readTimeoutMs: Int = 15000): BridgeReply? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true

            val out: OutputStream = socket.getOutputStream()
            val `in`: InputStream = socket.getInputStream()

            // send request
            val payload = byteArrayOf(cmd) + params
            val frame = buildFrame(payload)
            out.write(frame)
            out.flush()

            // read reply header
            val header = ByteArray(5)
            readExact(`in`, header, header.size)
            if (header[0] != FRAME_REPLY) {
                Log.w(TAG, "Unexpected frame type ${header[0]} for cmd=$cmd")
                return null
            }
            val len = readInt32(header, 1)
            if (len < 0 || len > MAX_FRAME_SIZE) {
                Log.w(TAG, "Invalid reply length $len for cmd=$cmd")
                return null
            }
            val replyData = ByteArray(len)
            if (len > 0) readExact(`in`, replyData, len)
            if (replyData.size < 4) return null
            BridgeReply(readInt32(replyData, 0), replyData.copyOfRange(4, replyData.size))
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Command $cmd timed out")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Command $cmd failed: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun buildFrame(payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = FRAME_REQUEST
        writeInt32(frame, 1, payload.size)
        payload.copyInto(frame, 5)
        return frame
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw IllegalStateException("Unexpected end of stream")
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

/**
 * Parsed status of the mosey stack, returned by [MoseyControlClient.status].
 */
public data class MoseyStatus(
    val enabled: Boolean,
    val nativeRunning: Boolean,
    val bridgeRunning: Boolean,
    val shimRunning: Boolean,
    val wifiConnected: Boolean,
    val mosey0Exists: Boolean,
    val wonderLoaded: Boolean,
    val countryCode: String?,
    val countryMode: String?,
) {
    internal companion object {
        private const val TAG = "MoseyStatus"

        public fun fromJson(json: String): MoseyStatus {
            // Simple JSON parsing without external dependencies
            fun bool(key: String): Boolean = run {
                val idx = json.indexOf("\"$key\"")
                if (idx < 0) return@run false
                val colon = json.indexOf(':', idx + key.length + 2)
                if (colon < 0) return@run false
                val rest = json.substring(colon + 1).trimStart()
                rest.startsWith("true")
            }
            fun string(key: String): String? {
                val idx = json.indexOf("\"$key\"")
                if (idx < 0) return null
                val colon = json.indexOf(':', idx + key.length + 2)
                if (colon < 0) return null
                val rest = json.substring(colon + 1).trimStart()
                // Extract value between quotes after the colon
                val start = rest.indexOf('"')
                if (start < 0) return null
                val end = rest.indexOf('"', start + 1)
                if (end < 0) return null
                return rest.substring(start + 1, end)
            }
            return MoseyStatus(
                enabled = bool("enabled"),
                nativeRunning = bool("native_running"),
                bridgeRunning = bool("bridge_running"),
                shimRunning = bool("shim_running"),
                wifiConnected = bool("wifi_connected"),
                mosey0Exists = bool("mosey0_exists"),
                wonderLoaded = bool("wonder_loaded"),
                countryCode = string("country_code"),
                countryMode = string("country_mode"),
            )
        }
    }
}
