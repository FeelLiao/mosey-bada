/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

/**
 * Event handler interface for mosey_server discovery callbacks.
 *
 * Implementations receive device discovery/lost events forwarded by
 * [MoseySocketClient] from the mosey_bridge daemon. Discovery is emitted
 * only after the shim resolves an `_airdrop._tcp` service.
 *
 * ## Event Flow
 *
 * ```
 * Android NSD shim
 *   → loopback JSON event
 *   → mosey_bridge
 *   → TCP event frame (type=0x03)
 *   → MoseySocketClient (Kotlin)
 *   → MoseyEventHandler.onDeviceDiscovered/onDeviceLost
 * ```
 *
 * @see MoseySocketClient
 * @see AppleDevice
 */
public interface MoseyEventHandler {
    /**
     * Called when an Apple AirDrop device is discovered via AWDL.
     *
     * @param device The discovered Apple device with available metadata.
     */
    public fun onDeviceDiscovered(device: AppleDevice)

    /**
     * Called when a previously discovered device is no longer visible.
     *
     * @param endpointId The unique endpoint identifier of the lost device.
     */
    public fun onDeviceLost(endpointId: String)

    /**
     * Called when the connection to mosey_bridge is established.
     */
    public fun onConnected()

    /**
     * Called when the connection to mosey_bridge is lost.
     *
     * @param reason A human-readable description of the disconnection cause.
     */
    public fun onDisconnected(reason: String)
}
