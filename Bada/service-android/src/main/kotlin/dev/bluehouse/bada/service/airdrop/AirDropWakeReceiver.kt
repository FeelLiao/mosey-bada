/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService

/** Explicit cross-package wakeup target used by the privileged Mosey shim. */
public class AirDropWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        ReceiverForegroundService.start(context.applicationContext)
    }

    public companion object {
        public const val ACTION_WAKE: String = "dev.bluehouse.bada.airdrop.WAKE"
    }
}
