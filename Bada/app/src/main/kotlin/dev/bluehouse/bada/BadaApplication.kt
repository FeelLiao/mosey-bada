/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada

import android.app.Application
import android.util.Log
import dev.bluehouse.bada.consent.ConsentTrampolineActivity
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.discovery.medium.MoseyBinderClient
import dev.bluehouse.bada.discovery.medium.MoseySocketClient
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService

/**
 * Application bootstrap that wires the `:app`-side activity classes
 * into the `:service-android` library at process start.
 *
 * The service module deliberately keeps no compile-time dependency on
 * `:app` — it would otherwise become a circular reference. Instead
 * the service exposes a pair of `@Volatile` `Class<*>` slots
 * ([ReceiverForegroundService.openAppTarget] and
 * [ReceiverForegroundService.consentTrampolineTarget]) that the host
 * application populates here, before any service `onCreate` runs.
 *
 * The wiring happens in `Application.onCreate`, which Android
 * guarantees to invoke before any other component (`Service`,
 * `BroadcastReceiver`, `Activity`) of the app, so by the time the
 * receiver service first tries to build a notification PendingIntent
 * the targets are already set.
 *
 * It also points [DiagnosticLog]'s on-disk sink at the app's external
 * files dir so BLE/discovery diagnostics persist into the bug report past
 * the 15-minute in-memory ring-buffer window (#201).
 *
 * At startup, it also probes for the mosey_server NDK Binder service
 * to log whether AirDrop (AWDL) transport is available (OnePlus 15
 * GLO ROM or KSU module required).
 */
class BadaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReceiverForegroundService.openAppTarget = MainActivity::class.java
        ReceiverForegroundService.consentTrampolineTarget = ConsentTrampolineActivity::class.java
        // Must match where BugReportCollector reads the log back from
        // (getExternalFilesDir(null)); a filesDir fallback would write logs
        // the collector never picks up.
        getExternalFilesDir(null)?.let { DiagnosticLog.configureFileSink(it) }

        // Probe mosey_server Binder availability (non-blocking)
        probeMoseyServer()
    }

    /**
     * Check if the native mosey_server is available via either transport path.
     *
     * Tries:
     * 1. [MoseySocketClient] — TCP connection to mosey_bridge (primary, runs as ksu root)
     * 2. [MoseyBinderClient] — direct NDK Binder call (fallback, SELinux permitting)
     *
     * Logs the result for debugging — does not start AWDL discovery.
     */
    private fun probeMoseyServer() {
        try {
            android.util.Log.e(TAG_MOSEY, "=== Mosey probe starting ===")

            // Primary: try socket bridge (mosey_bridge runs as ksu root)
            val socketClient = MoseySocketClient()
            val socketConnected = socketClient.connect()
            android.util.Log.e(TAG_MOSEY, "SocketClient.connect() returned: $socketConnected")
            if (socketConnected) {
                val version = socketClient.getVersion()
                android.util.Log.e(TAG_MOSEY, "mosey_server found via socket bridge, version=$version")
                socketClient.close()
                return
            }

            // Fallback: try direct Binder (may be blocked by SELinux)
            val binderClient = MoseyBinderClient()
            val binderConnected = binderClient.connect()
            android.util.Log.e(TAG_MOSEY, "BinderClient.connect() returned: $binderConnected")
            if (binderConnected) {
                val version = binderClient.getVersion()
                android.util.Log.e(TAG_MOSEY, "mosey_server found via direct Binder, version=$version")
            } else {
                android.util.Log.e(TAG_MOSEY, "mosey_server NOT available (socket + binder both failed)")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG_MOSEY, "mosey_server probe failed", e)
        }
    }

    private companion object {
        private const val TAG_MOSEY = "MoseyDiag"
    }
}
