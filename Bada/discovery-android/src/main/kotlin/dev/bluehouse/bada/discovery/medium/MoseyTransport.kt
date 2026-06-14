/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.UpgradedTransport
import java.io.InputStream
import java.io.OutputStream

/**
 * [UpgradedTransport] subtype for the Mosey (AWDL/AirDrop) medium.
 *
 * Unlike [WifiDirectTransport] or [BleL2capUpgradedTransport], this
 * transport does **not** carry a live socket. The actual AWDL radio I/O
 * is handled entirely in-kernel by `mosey_server` (Rust daemon) through
 * `wonder.ko` / nl80211. The credentials simply tell the daemon which
 * regulatory domain to use.
 *
 * Bada's role here is limited to telling `mosey_server` when to start
 * and stop AWDL discovery — the actual frame exchange and peer detection
 * happens inside the native binary.
 *
 * @property medium Always [Medium.MOSEY].
 * @property credentials The mosey transport parameters (country code).
 * @property teardown Optional cleanup hook (e.g. stop AWDL discovery).
 */
public data class MoseyTransport(
    private val transportMedium: Medium = Medium.MOSEY,
    val countryCode: String = "US",
    private val teardown: (() -> Unit)? = null,
) : UpgradedTransport {
    override val medium: Medium get() = transportMedium

    /**
     * No input stream: payload I/O is managed by the native mosey_server.
     * Bada receives/sends files through the native daemon, not through
     * this transport.
     */
    override val inputStream: InputStream
        get() = error("Mosey transport I/O is handled by native mosey_server, not by Bada's socket layer")

    /**
     * No output stream: same rationale as [inputStream].
     */
    override val outputStream: OutputStream
        get() = error("Mosey transport I/O is handled by native mosey_server, not by Bada's socket layer")

    /**
     * Close the transport. Invokes the optional teardown callback
     * (typically [MoseyMediumProvider.cancelPendingUpgrade]) to stop
     * AWDL discovery.
     */
    override fun close() {
        teardown?.invoke()
    }
}
