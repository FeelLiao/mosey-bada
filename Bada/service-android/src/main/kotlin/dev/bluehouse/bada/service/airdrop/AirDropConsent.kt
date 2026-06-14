/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class AirDropConsentRequest(
    val id: Long,
    val sender: String,
    val files: List<AirDropFileOffer>,
    private val latch: CountDownLatch = CountDownLatch(1),
) {
    @Volatile var accepted: Boolean = false
    fun decide(value: Boolean) { accepted = value; latch.countDown() }
    fun await(): Boolean = latch.await(120, TimeUnit.SECONDS) && accepted
}

internal object AirDropConsentRegistry {
    private val nextId = AtomicLong(0xAD00)
    private val requests = ConcurrentHashMap<Long, AirDropConsentRequest>()

    fun create(sender: String, files: List<AirDropFileOffer>): AirDropConsentRequest =
        AirDropConsentRequest(nextId.incrementAndGet(), sender, files).also { requests[it.id] = it }

    fun decide(id: Long, accepted: Boolean) { requests.remove(id)?.decide(accepted) }
    fun remove(id: Long) { requests.remove(id) }
}

public class AirDropConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        AirDropConsentRegistry.decide(id, intent.action == ACTION_ACCEPT)
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(id))
    }

    public companion object {
        internal const val ACTION_ACCEPT = "dev.bluehouse.bada.airdrop.ACCEPT"
        internal const val ACTION_REJECT = "dev.bluehouse.bada.airdrop.REJECT"
        internal const val EXTRA_ID = "airdrop.request_id"
        internal fun notificationId(id: Long): Int = 0x4A00_0000 or (id.toInt() and 0x00ff_ffff)
    }
}

internal object AirDropConsentNotification {
    private const val CHANNEL = "incoming_airdrop"

    fun post(context: Context, request: AirDropConsentRequest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Incoming AirDrop files", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val total = request.files.sumOf { it.size }
        val summary = "${request.files.size} file(s), ${formatBytes(total)}"
        fun action(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, AirDropConsentReceiver::class.java).apply {
                    this.action = action
                    putExtra(AirDropConsentReceiver.EXTRA_ID, request.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("${request.sender} wants to share")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.files.joinToString("\n") { it.name }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .addAction(0, "Reject", action(AirDropConsentReceiver.ACTION_REJECT, request.id.toInt() * 2))
            .addAction(0, "Accept", action(AirDropConsentReceiver.ACTION_ACCEPT, request.id.toInt() * 2 + 1))
            .build()
        manager.notify(AirDropConsentReceiver.notificationId(request.id), notification)
    }

    fun dismiss(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(AirDropConsentReceiver.notificationId(id))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
