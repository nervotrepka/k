package io.github.nervotrepka.pitouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.nervotrepka.pitouch.bt.LinkState
import io.github.nervotrepka.pitouch.tv.SamsungRemote

/** Foreground service: keeps the process (and the Bluetooth/Wi-Fi links) alive in the background. */
class ConnectionService : Service(), Core.Listener {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        Core.listeners += this
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Подключение", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, build())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            Core.exit()
            return START_NOT_STICKY
        }
        // Restarted by the system after the process was killed: reconnect.
        if (intent == null && Core.bluetoothReady) Core.current?.let { Core.connect(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        Core.listeners -= this
        super.onDestroy()
    }

    override fun onCoreChanged() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
    }

    private fun build(): Notification {
        val name = Core.current?.name ?: "устройство не выбрано"
        val parts = mutableListOf<String>()
        if (Core.current?.btAddress != null) parts += when (Core.btState) {
            LinkState.CONNECTED -> "Bluetooth: подключено"
            LinkState.CONNECTING -> "Bluetooth: подключение…"
            LinkState.WAITING, LinkState.DISCONNECTED -> "Bluetooth: нет связи"
        }
        if (Core.current?.ip != null) parts += when (Core.tvState) {
            SamsungRemote.State.CONNECTED -> "Wi-Fi: подключено"
            SamsungRemote.State.CONNECTING -> "Wi-Fi: подключение…"
            SamsungRemote.State.DISCONNECTED -> "Wi-Fi: нет связи"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PiTouch · $name")
            .setContentText(parts.joinToString(" · ").ifEmpty { "Работает в фоне" })
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выход", exit).build())
            .build()
    }

    companion object {
        const val ACTION_EXIT = "io.github.nervotrepka.pitouch.EXIT"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
    }
}
