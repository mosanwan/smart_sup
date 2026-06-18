package com.smartsup.controller.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.smartsup.controller.MainActivity
import com.smartsup.controller.R

class ControlForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var microphoneActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        microphoneActive = intent?.getBooleanExtra(EXTRA_MICROPHONE_ACTIVE, false) == true
        acquireWakeLock()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceTypes(),
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台控制保活",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 Smart SUP 后台蓝牙控制心跳和语音监听"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Smart SUP 控制运行中")
            .setContentText(
                if (microphoneActive) {
                    "保持蓝牙控制心跳，并进行实时语音监听"
                } else {
                    "保持蓝牙控制心跳，防止后台暂停后触发主控失联"
                },
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun foregroundServiceTypes(): Int {
        var types = 0
        if (hasConnectedDevicePermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (microphoneActive) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (types == 0) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartSup:ControlHeartbeat",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun hasConnectedDevicePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        private const val CHANNEL_ID = "smart_sup_background_voice"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.smartsup.controller.service.STOP_BACKGROUND_VOICE"
        private const val EXTRA_MICROPHONE_ACTIVE = "microphone_active"

        fun start(context: Context, microphoneActive: Boolean = false) {
            val intent = Intent(context, ControlForegroundService::class.java)
                .putExtra(EXTRA_MICROPHONE_ACTIVE, microphoneActive)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlForegroundService::class.java))
        }
    }
}
