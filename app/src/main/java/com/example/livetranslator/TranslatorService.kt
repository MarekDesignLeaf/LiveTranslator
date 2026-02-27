package com.example.livetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Foreground Service that keeps the process alive while listening.
 *
 * Why this is needed:
 * Android aggressively kills background processes to save memory/battery.
 * A Foreground Service with a visible notification tells the OS
 * "this process is doing something the user cares about – don't kill it".
 *
 * The service itself doesn't run speech recognition – that stays in
 * MainActivity (which needs a Context + UI callbacks).  The service simply
 * keeps the process alive so the Activity is never killed mid-session.
 *
 * Usage from MainActivity:
 *   startAndBind()   – call when Listen is turned ON
 *   stopService()    – call when Listen is turned OFF
 */
class TranslatorService : Service() {

    companion object {
        const val CHANNEL_ID      = "livetranslator_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.livetranslator.START"
        const val ACTION_STOP  = "com.example.livetranslator.STOP"

        fun startIntent(context: Context)  = Intent(context, TranslatorService::class.java).apply { action = ACTION_START }
        fun stopIntent(context: Context)   = Intent(context, TranslatorService::class.java).apply { action = ACTION_STOP }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TranslatorService = this@TranslatorService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        // START_STICKY: if system kills service, recreate it (keeps process priority high)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiveTranslator Listening",
            NotificationManager.IMPORTANCE_LOW          // no sound, low intrusion
        ).apply {
            description = "Shown while speech recognition is active"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification returns to the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action directly from the notification shade
        val stopPendingIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveTranslator")
            .setContentText("🎤 Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)               // cannot be swiped away
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop listening",
                    stopPendingIntent
                ).build()
            )
            .build()
    }
}
