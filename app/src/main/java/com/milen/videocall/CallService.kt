package com.milen.videocall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * A minimal foreground service whose only job is to keep this app alive and at
 * foreground priority while a call is active.
 *
 * Without this, the moment you switch away to another app mid-call (e.g. to
 * check a phone setting), Android - and Samsung's battery management in
 * particular - can suspend or kill the audio/video connection the same way it
 * would for any other background activity. A foreground service with a
 * visible notification is what apps like Viber/WhatsApp use to be allowed to
 * keep running in that situation; this does the same thing here.
 *
 * It doesn't touch WebRTC itself - [MainActivity] and [WebRTCClient] keep
 * doing that work exactly as before. This service is just there to hold a
 * "call in progress" notification for as long as the call lasts.
 */
class CallService : Service() {

    companion object {
        private const val CHANNEL_ID = "call_in_progress"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Видео разговор",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показва се докато тече разговор, за да не се прекъсва връзката на заден фон."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Видео разговор")
            .setContentText("Разговорът тече - докосни, за да се върнеш в приложението.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
