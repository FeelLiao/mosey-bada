/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import android.content.Context
import android.util.Log
import dev.bluehouse.bada.discovery.medium.MoseyControlClient
import dev.bluehouse.bada.discovery.medium.MoseyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Aggregates both root-side (bridge) and app-side (local) Mosey/AirDrop
 * status into a single observable state.
 *
 * ## Root-side state
 * Polled from [MoseyControlClient.status()] every [POLL_INTERVAL_MS].
 * Reflects whether the mosey_server/bridge/shim are running, Wi-Fi
 * state, and whether the wonder phy / mosey0 interface exist.
 *
 * ## App-side state
 * Updated locally via [updateAppState] — tracks whether
 * [AirDropLocalServer] is running, recent consent/upload results, etc.
 *
 * Usage (in a ViewModel or Fragment):
 * ```kotlin
 * val repo = StatusRepository.getInstance(requireContext())
 * lifecycleScope.launch {
 *     repo.rootState.collect { status -> /* update UI */ }
 * }
 * repo.startPolling(lifecycleScope)
 * ```
 */
public class StatusRepository private constructor(
    private val controlClient: MoseyControlClient,
) {
    public companion object {
        private const val TAG = "StatusRepository"
        private const val POLL_INTERVAL_MS = 5000L

        @Volatile
        private var instance: StatusRepository? = null

        public fun getInstance(context: Context): StatusRepository {
            return instance ?: synchronized(this) {
                instance ?: StatusRepository(
                    MoseyControlClient(),
                ).also { instance = it }
            }
        }

        public fun resetInstance() {
            instance = null
        }
    }

    // ── Root-side state (from bridge CMD_STATUS) ──

    private val _rootState = MutableStateFlow(RootState())
    public val rootState: StateFlow<RootState> = _rootState.asStateFlow()

    // ── App-side state (local, no bridge needed) ──

    private val _appState = MutableStateFlow(AppState())
    public val appState: StateFlow<AppState> = _appState.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * Start periodic polling of the bridge for root-side status.
     * Call from a lifecycle-aware scope; stops automatically when
     * the scope is cancelled.
     */
    public fun startPolling(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(SupervisorJob() + Dispatchers.IO) {
            while (isActive) {
                val moseyStatus = controlClient.status()
                _rootState.value = if (moseyStatus != null) {
                    RootState(
                        enabled = moseyStatus.enabled,
                        nativeRunning = moseyStatus.nativeRunning,
                        bridgeRunning = moseyStatus.bridgeRunning,
                        shimRunning = moseyStatus.shimRunning,
                        wifiConnected = moseyStatus.wifiConnected,
                        mosey0Exists = moseyStatus.mosey0Exists,
                        lastBridgeUpdate = System.currentTimeMillis(),
                    )
                } else {
                    _rootState.value.copy(bridgeReachable = false)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    public fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Update app-side state from local components.
     */
    public fun updateAppState(block: AppState.() -> AppState) {
        _appState.value = _appState.value.block()
    }

    // ── State types ──

    public data class RootState(
        val enabled: Boolean = false,
        val nativeRunning: Boolean = false,
        val bridgeRunning: Boolean = false,
        val shimRunning: Boolean = false,
        val wifiConnected: Boolean = false,
        val mosey0Exists: Boolean = false,
        val bridgeReachable: Boolean = false,
        val lastBridgeUpdate: Long = 0L,
    )

    public data class AppState(
        val receiverServiceRunning: Boolean = false,
        val localServerRunning: Boolean = false,
        val pendingConsentCount: Int = 0,
        val lastAskTimestamp: Long = 0L,
        val lastAskSender: String? = null,
        val lastUploadTimestamp: Long = 0L,
        val lastUploadFiles: Int = 0,
    )
}
