/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import android.content.Context
import android.util.Log
import dev.bluehouse.bada.discovery.medium.AppleDevice
import dev.bluehouse.bada.discovery.medium.MoseyEventHandler
import dev.bluehouse.bada.discovery.medium.MoseySocketClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Sender-side AirDrop peer events delivered by the KernelSU Mosey bridge. */
public sealed interface MoseyPeerEvent {
    public data class Resolved(
        val device: AppleDevice,
    ) : MoseyPeerEvent

    public data class Lost(
        val endpointId: String,
    ) : MoseyPeerEvent
}

/**
 * Converts bridge `_airdrop._tcp` events into a lifecycle-aware discovery flow.
 * Radio ownership remains in the KernelSU shim; Bada only subscribes here.
 */
public class MoseyPeerScanner(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val clientFactory: () -> MoseySocketClient = { MoseySocketClient() },
) {
    public fun scan(): Flow<MoseyPeerEvent> = flow {
        var delayMillis = 1_000L
        while (currentCoroutineContext().isActive) {
            emitAll(singleConnection())
            Log.w(TAG, "Mosey bridge unavailable; retrying in ${delayMillis}ms")
            delay(delayMillis)
            delayMillis = (delayMillis * 2).coerceAtMost(30_000L)
        }
    }

    private fun singleConnection(): Flow<MoseyPeerEvent> =
        callbackFlow {
            val client = clientFactory()
            client.setEventHandler(
                object : MoseyEventHandler {
                    override fun onDeviceDiscovered(device: AppleDevice) {
                        trySend(MoseyPeerEvent.Resolved(device))
                    }

                    override fun onDeviceLost(endpointId: String) {
                        trySend(MoseyPeerEvent.Lost(endpointId))
                    }

                    override fun onConnected() {
                        Log.i(TAG, "Subscribed to Mosey AirDrop events")
                    }

                    override fun onDisconnected(reason: String) {
                        Log.w(TAG, "Mosey event connection closed: $reason")
                        close()
                    }

                    override fun onAppleBleSeen(deviceName: String, mac: String?) {
                        Log.d(TAG, "Apple BLE seen during peer scan: $deviceName")
                    }
                },
            )
            if (!client.connect()) {
                close()
            }
            awaitClose {
                client.clearEventHandler()
                client.close()
            }
        }

    private companion object {
        const val TAG: String = "MoseyPeerScanner"
    }
}
