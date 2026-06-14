/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

/**
 * Data model for an Apple device discovered via AWDL (AirDrop).
 *
 * Instances are created by [MoseySocketClient] when it receives
 * resolved `_airdrop._tcp` JSON events from mosey_bridge.
 *
 * ## Field Availability
 *
 * | Field | Source | Status |
 * |-------|--------|--------|
 * | endpointId | AWDL device hash | ✅ Expected |
 * | deviceName | Apple device name (NSString) | ✅ Expected |
 * | ipv4 | IPv4 address for AirDrop HTTPS | ⚠️ May need extraction |
 * | ipv6 | IPv6 address | ⚠️ May need extraction |
 * | macAddress | AWDL MAC (WiFi Direct fallback) | ⚠️ May need extraction |
 * | signalStrength | RSSI / proximity | ✅ Expected |
 *
 * @property endpointId Unique device endpoint identifier (hash).
 * @property deviceName Human-readable device name (e.g. "iPhone 15").
 * @property ipv4 IPv4 address string, or null if not available.
 * @property ipv6 IPv6 address string, or null if not available.
 * @property macAddress MAC address string, or null if not available.
 * @property signalStrength Signal strength indicator (0-255, higher = closer).
 * @property port Resolved AirDrop service port, or null until NSD resolution.
 */
public data class AppleDevice(
    val endpointId: String,
    val deviceName: String,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val macAddress: String? = null,
    val signalStrength: Int = 0,
    val port: Int? = null,
)
