/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Debounced wake handler for Apple BLE proximity signals.
 *
 * When [onAppleBleSeen] is called, it checks whether enough time has
 * elapsed since the last wake attempt (default: 60 seconds). If so,
 * it sends [MoseySocketClient.sendWakeBada] to the bridge, which
 * broadcasts to [AirDropWakeReceiver] to start the receiver FGS.
 *
 * This prevents rapid BLE beacon spam from repeatedly waking Bada
 * when multiple Apple devices are nearby.
 *
 * @param socketClient The connected [MoseySocketClient] used to send
 *   the wake command. Must be connected before calling [onAppleBleSeen].
 * @param debounceMs Minimum interval between wake attempts.
 */
public class MoseyWakeHandler(
    private val socketClient: MoseySocketClient,
    private val debounceMs: Long = 60_000L,
) : MoseyEventHandler {

    private val lastWakeTime = AtomicLong(0)
    private var delegate: MoseyEventHandler? = null

    private companion object {
        private const val TAG = "MoseyWakeHandler"
    }

    /**
     * Optionally set a delegate that receives all other events
     * (onDeviceDiscovered, onDeviceLost, etc.) unchanged.
     */
    public fun setDelegate(handler: MoseyEventHandler?) {
        delegate = handler
    }

    override fun onAppleBleSeen(deviceName: String, mac: String?) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastWakeTime.get()
        if (elapsed < debounceMs) {
            Log.d(TAG, "BLE wake debounced: $deviceName (${elapsed}ms < ${debounceMs}ms)")
            return
        }
        lastWakeTime.set(now)
        Log.i(TAG, "BLE wake signal from $deviceName; sending CMD_WAKE_BADA")
        socketClient.sendWakeBada()
    }

    // ── delegate passthrough ──

    override fun onDeviceDiscovered(device: AppleDevice) {
        delegate?.onDeviceDiscovered(device)
    }

    override fun onDeviceLost(endpointId: String) {
        delegate?.onDeviceLost(endpointId)
    }

    override fun onConnected() {
        delegate?.onConnected()
    }

    override fun onDisconnected(reason: String) {
        delegate?.onDisconnected(reason)
    }
}
