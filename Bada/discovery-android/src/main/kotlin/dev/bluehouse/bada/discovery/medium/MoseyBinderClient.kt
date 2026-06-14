/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log

/**
 * NDK Binder client for the native `mosey_server` daemon.
 *
 * Bypasses GMS and MoseyApp entirely — calls the native mosey_server NDK Binder
 * directly through ServiceManager (via reflection, since `android.os.ServiceManager`
 * is a hidden API). The service is registered at boot by `mosey_server` (Rust binary)
 * and is reachable via the well-known service name
 * `com.google.android.moseyservice.IMoseyService/default`.
 *
 * ## Transaction Codes (reverse-engineered from MoseyApp APK v13120)
 *
 * | TR_CODE | Hex       | Method       | Params                    | Returns |
 * |---------|-----------|--------------|---------------------------|---------|
 * | 16777215| `0xFFFFFF`| `getVersion()`| (none)                   | `Int`   |
 * | 1       | `0x01`    | `start(bps)` | `int[] + IBinder + int`  | `void`  |
 * | 2       | `0x02`    | `stop(bpt)`  | (empty)                  | `void`  |
 * | 3       | `0x03`    | `update(bpu)`| `String`                 | `void`  |
 *
 * All transactions are synchronous Binder calls (mode `0` — no oneway).
 * The interface token is always `"com.google.android.moseyservice.IMoseyService"`.
 *
 * @see `bpq.java` / `bpr.java` in MoseyApp decompile output
 * @see `docs/research/mosey-airdrop-integration.md`
 */
public class MoseyBinderClient {

    /** Connected Binder proxy, null if not yet connected or service unavailable. */
    private var binder: IBinder? = null

    /** Cached version code from [getVersion]; -1 until resolved. */
    private var cachedVersion: Int = -1

    public companion object {
        private const val TAG = "MoseyBinderClient"

        /** Binder service name registered by mosey_server. */
        @JvmStatic
        public val SERVICE_NAME: String =
            "com.google.android.moseyservice.IMoseyService/default"

        /** Binder interface descriptor token. */
        private const val DESCRIPTOR =
            "com.google.android.moseyservice.IMoseyService"

        // Transaction codes matching the native AIDL interface
        private const val TR_GET_VERSION = 16777215 // 0xFFFFFF
        private const val TR_START = 1
        private const val TR_STOP = 2
        private const val TR_UPDATE = 3
    }

    /**
     * Look up the mosey_server Binder service via ServiceManager (reflection).
     *
     * `android.os.ServiceManager` is a hidden API; we access it through
     * reflection to avoid compile-time and runtime restrictions on non-
     * platform builds.
     *
     * @return true if the service was found and we have a live Binder proxy.
     */
    fun connect(): Boolean {
        if (binder != null) return true
        binder = getService(SERVICE_NAME)
        if (binder == null) {
            Log.w(TAG, "Service $SERVICE_NAME not found — is mosey_server running?")
            return false
        }
        Log.i(TAG, "Connected to $SERVICE_NAME")
        return true
    }

    /** Reflectively call `ServiceManager.getService(name)`. */
    private fun getService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val method = smClass.getMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to call ServiceManager.getService via reflection", e)
            null
        }
    }

    /**
     * Check if the native `getVersion()` call succeeds.
     * Lightweight health-check — does not require [connect] to have been
     * called first.
     */
    fun isAlive(): Boolean {
        return try {
            getVersion() >= 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * TR_CODE 0xFFFFFF: Query the native mosey_server version code.
     *
     * The result is cached after the first successful call because the
     * version is immutable for the lifetime of the process.
     *
     * @return Version code (expected: `20250923` for the 2025-09-23 build).
     * @throws RemoteException if the Binder call fails.
     * @throws IllegalStateException if not connected.
     */
    @Throws(RemoteException::class)
    fun getVersion(): Int {
        if (cachedVersion >= 0) return cachedVersion
        val b = binder ?: throw IllegalStateException("Not connected to mosey_server")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TR_GET_VERSION, data, reply, 0 /* flags: synchronous */)
            cachedVersion = reply.readInt()
            return cachedVersion
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * TR_CODE 1: Start AWDL discovery via mosey_server.
     *
     * The native daemon will open a PF_PACKET socket, send NL80211 commands
     * to cfg80211/wonder.ko, and begin sending/receiving AWDL 802.11 frames.
     *
     * @param mediumFilters Bitmask array for medium types (e.g. `intArrayOf(13)` for AWDL only).
     * @param callback Binder stub that receives discovery events from the native daemon.
     * @throws RemoteException if the Binder call fails.
     * @throws IllegalStateException if not connected.
     */
    @Throws(RemoteException::class)
    fun start(mediumFilters: IntArray, callback: IBinder) {
        val b = binder ?: throw IllegalStateException("Not connected to mosey_server")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            // bps Parcelable wrapper: hasValue=1 (non-null), then int[] + IBinder + int
            data.writeInt(1) // hasValue: bps is non-null
            data.writeIntArray(mediumFilters)
            data.writeStrongBinder(callback)
            data.writeInt(Integer.MAX_VALUE) // stability = MAX_VALUE (matches bmz.java)
            b.transact(TR_START, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * TR_CODE 2: Stop AWDL discovery.
     *
     * The native daemon will tear down the AWDL radio interface and release
     * the PF_PACKET socket.
     *
     * @throws RemoteException if the Binder call fails.
     * @throws IllegalStateException if not connected.
     */
    @Throws(RemoteException::class)
    fun stop() {
        val b = binder ?: throw IllegalStateException("Not connected to mosey_server")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            // bpt Parcelable wrapper: hasValue=1 (non-null), no fields
            data.writeInt(1) // hasValue: bpt is non-null
            b.transact(TR_STOP, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * TR_CODE 3: Update mosey configuration — typically the country code
     * for regulatory channel selection.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code (e.g. "US", "CN", "JP").
     * @throws RemoteException if the Binder call fails.
     * @throws IllegalStateException if not connected.
     */
    @Throws(RemoteException::class)
    fun update(countryCode: String) {
        val b = binder ?: throw IllegalStateException("Not connected to mosey_server")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            // bpu Parcelable wrapper: hasValue=1 (non-null), then String field
            data.writeInt(1) // hasValue: bpu is non-null
            data.writeString(countryCode)
            b.transact(TR_UPDATE, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
