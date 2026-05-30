package com.coursemapper.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.coursemapper.MainActivity
import com.coursemapper.R

/**
 * Keeps the process alive while recording, nothing else.
 * [com.coursemapper.ui.record.RecordingViewModel] is the only thing collecting
 * and storing fixes - two writers duplicate every point.
 *
 * [START_NOT_STICKY] since nothing would be recording after a restart, Home
 * offers recovery for interrupted recordings instead.
 */
class RecordingForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START     = "com.coursemapper.recording.START"
        const val ACTION_STOP      = "com.coursemapper.recording.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val CHANNEL_ID       = "recording_channel"
        const val NOTIFICATION_ID  = 1001

        fun startIntent(context: Context, sessionId: Long): Intent =
            Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startForegroundNotification()
            ACTION_STOP  -> stopSelf()
            // Null intent (system restart) - nothing is recording; shut down.
            null         -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_recording_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
