package org.localsend.localsend_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/// Foreground service that keeps the headless direct-share process alive and shows
/// the transfer progress. It is driven by the Dart side through [DirectShareHost].
class DirectShareService : Service() {

    companion object {
        private const val CHANNEL_ID = "localsend_direct_share"
        private const val NOTIFICATION_ID = 420
        private const val RESULT_NOTIFICATION_ID = 421
    }

    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        DirectShareHost.service = this

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Direct Share",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val notification = buildNotification("LocalSend", getString(R.string.direct_share_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        if (DirectShareHost.service === this) {
            DirectShareHost.service = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(title: String, text: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    /// Transfer is over: show the outcome, then shut the process down shortly after.
    fun finishWithResult(success: Boolean, message: String?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val text = message ?: getString(
            if (success) R.string.direct_share_success else R.string.direct_share_failure
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("LocalSend")
            .setContentText(text)
            .setAutoCancel(true)
        notificationManager?.notify(RESULT_NOTIFICATION_ID, builder.build())

        stopSelf()

        // Give the Dart isolate a moment to flush its cleanup, then end the process:
        // nothing else is supposed to live in :direct_share.
        android.os.Handler(mainLooper).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 1000)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (launchIntent != null) setContentIntent(launchIntent) }
            .build()
    }
}
