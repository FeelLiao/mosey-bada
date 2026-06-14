/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumProvider
import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials
import dev.bluehouse.bada.protocol.medium.UpgradedTransport

/**
 * [MediumProvider] for AWDL / Apple AirDrop transport via mosey_server.
 *
 * Uses a **dual-path** connection strategy:
 * 1. **Primary**: [MoseySocketClient] — TCP connection to mosey_bridge
 *    (supports either direct Binder or the preload UNIX proxy backend).
 * 2. **Fallback**: [MoseyBinderClient] — direct NDK Binder call
 *    (for devices where SELinux policy allows `untrusted_app` to find
 *     the service, or when running as a system/privileged app).
 *
 * This provider enables Bada to discover and be discovered by Apple AirDrop
 * peers (macOS, iPhone, iPad) on OnePlus 15 devices (and other devices with
 * `wonder.ko` + `mosey_server`).
 *
 * ## Runtime Requirements
 *
 * 1. **KernelSU module** that mounts `mosey_server` + `libmosey_daemon_ffi.so`
 *    from a GLO ROM (see `module_mosey/` in the workspace root).
 * 2. `mosey_server` reachable through direct Binder or the preload UNIX proxy.
 * 3. `wonder.ko` kernel module loaded (`vendor_dlkm` partition, present in both
 *    GLO and CN ROMs).
 *
 * ## Lifecycle
 *
 * - [isSupported] checks both paths for mosey_server availability.
 * - [prepareUpgrade] starts AWDL advertising on the receiver side.
 * - [adoptUpgrade] configures the AWDL country code.
 * - [cancelPendingUpgrade] stops AWDL discovery.
 *
 * @see MoseySocketClient
 * @see MoseyBinderClient
 * @see MoseyTransport
 */
public class MoseyMediumProvider(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
) : MediumProvider {

    override val medium: Medium = Medium.MOSEY

    /** Primary: TCP socket client for mosey_bridge */
    private val socketClient = MoseySocketClient()

    /** Fallback: direct NDK Binder client */
    private val binderClient = MoseyBinderClient()

    /** Which transport is currently active */
    private var activeTransport: TransportType = TransportType.NONE

    private enum class TransportType {
        NONE,
        SOCKET,
        BINDER,
    }

    internal companion object {
        private const val TAG = "MoseyMediumProvider"

        /** Default country code for regulatory channel selection. */
        internal const val DEFAULT_COUNTRY_CODE = "US"

        /** AWDL medium filter — wire number 13. */
        internal val AWDL_FILTER = intArrayOf(13)
    }

    /**
     * Check if mosey_server is available via either transport path.
     *
     * Tries TCP socket client first (mosey_bridge, runs as ksu root and
     * can access ServiceManager), then falls back to direct Binder call
     * (which may work on devices where SELinux allows untrusted_app to
     * access the service manager).
     */
    override fun isSupported(): Boolean {
        // Primary: try socket bridge (mosey_bridge runs as ksu root)
        if (socketClient.connect() && socketClient.isAlive()) {
            activeTransport = TransportType.SOCKET
            Log.i(TAG, "mosey_server available via socket bridge")
            return true
        }

        // Fallback: try direct Binder (may fail if SELinux blocks untrusted_app)
        if (binderClient.connect() && binderClient.isAlive()) {
            activeTransport = TransportType.BINDER
            Log.i(TAG, "mosey_server available via direct Binder")
            return true
        }

        Log.w(TAG, "mosey_server not available (socket + binder both failed)")
        return false
    }

    /**
     * Start AWDL advertising on the receiver side.
     */
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        return when (activeTransport) {
            TransportType.SOCKET -> prepareViaSocket()
            TransportType.BINDER -> prepareViaBinder()
            TransportType.NONE -> {
                Log.w(TAG, "prepareUpgrade: no active transport")
                null
            }
        }
    }

    /**
     * Adopt AWDL credentials from the receiver side.
     */
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
        if (credentials !is UpgradePathCredentials.Mosey) return null

        return when (activeTransport) {
            TransportType.SOCKET -> adoptViaSocket(credentials)
            TransportType.BINDER -> adoptViaBinder(credentials)
            TransportType.NONE -> null
        }
    }

    /**
     * Accept an incoming AWDL upgrade. Not supported for Mosey.
     */
    override suspend fun acceptUpgrade(): UpgradedTransport? {
        Log.w(TAG, "acceptUpgrade: not supported for Mosey (handled by native mosey_server)")
        return null
    }

    /**
     * Stop AWDL discovery and release radio resources.
     */
    override fun cancelPendingUpgrade() {
        when (activeTransport) {
            TransportType.SOCKET -> {
                try {
                    socketClient.stop()
                    Log.i(TAG, "AWDL discovery stopped (socket)")
                } catch (e: Exception) {
                    Log.w(TAG, "cancelPendingUpgrade (socket): ${e.message}")
                }
            }
            TransportType.BINDER -> {
                try {
                    binderClient.stop()
                    Log.i(TAG, "AWDL discovery stopped (binder)")
                } catch (e: RemoteException) {
                    Log.w(TAG, "cancelPendingUpgrade (binder): ${e.message}")
                }
            }
            TransportType.NONE -> { /* nothing to stop */ }
        }
    }

    /* ── Socket Transport (Primary) ────────────────────────────────────── */

    private suspend fun prepareViaSocket(): UpgradePathCredentials? {
        return try {
            if (!socketClient.start(AWDL_FILTER)) {
                Log.e(TAG, "mosey_bridge rejected AWDL start")
                return null
            }
            Log.i(TAG, "AWDL advertising started via socket bridge")
            UpgradePathCredentials.Mosey(
                countryCode = DEFAULT_COUNTRY_CODE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "prepareViaSocket failed", e)
            null
        }
    }

    private suspend fun adoptViaSocket(credentials: UpgradePathCredentials.Mosey): UpgradedTransport? {
        return try {
            if (!socketClient.update(credentials.countryCode)) {
                Log.e(TAG, "mosey_bridge rejected country update")
                return null
            }
            Log.i(TAG, "AWDL country code updated to ${credentials.countryCode} (socket)")
            MoseyTransport(
                countryCode = credentials.countryCode,
                teardown = { cancelPendingUpgrade() },
            )
        } catch (e: Exception) {
            Log.e(TAG, "adoptViaSocket failed", e)
            null
        }
    }

    /* ── Binder Transport (Fallback) ───────────────────────────────────── */

    private suspend fun prepareViaBinder(): UpgradePathCredentials? {
        return try {
            val callback = createDiscoveryCallback()
            binderClient.start(AWDL_FILTER, callback)
            Log.i(TAG, "AWDL advertising started via binder")
            UpgradePathCredentials.Mosey(
                countryCode = DEFAULT_COUNTRY_CODE,
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "prepareViaBinder failed", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "prepareViaBinder: unexpected error", e)
            null
        }
    }

    private suspend fun adoptViaBinder(credentials: UpgradePathCredentials.Mosey): UpgradedTransport? {
        return try {
            binderClient.update(credentials.countryCode)
            Log.i(TAG, "AWDL country code updated to ${credentials.countryCode} (binder)")
            MoseyTransport(
                countryCode = credentials.countryCode,
                teardown = { cancelPendingUpgrade() },
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "adoptViaBinder failed", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "adoptViaBinder: unexpected error", e)
            null
        }
    }

    /* ── Callback Binder (Binder path only) ────────────────────────────── */

    /**
     * Create a callback Binder stub that receives discovery events from
     * mosey_server (used only by the Binder transport path).
     *
     * The socket path receives events via TCP event frames from
     * mosey_bridge, so no Binder callback is needed there.
     */
    private fun createDiscoveryCallback(): IBinder {
        return object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                Log.d(TAG, "Discovery callback: transact code=$code, flags=$flags, " +
                    "dataSize=${data.dataSize()}")
                return true
            }
        }
    }
}
