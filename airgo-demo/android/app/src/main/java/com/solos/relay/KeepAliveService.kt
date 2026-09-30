package com.solos.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the session alive on battery.
 *
 * Plugged into a PC the phone never dozes, so everything works; unplugged, with the screen
 * off or in a pocket, Android (and Samsung harder) throttles Bluetooth, network and CPU
 * for any app without foreground status - which shows up as the glasses dropping and Wi-Fi
 * photo transfer dying. A foreground service plus a wake lock and a Wi-Fi lock is the
 * supported way to say "a device session is running, don't do that".
 *
 * Runs while the glasses are connected; the notification is the visible cost.
 */
class KeepAliveService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL = "session"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.e(TAG, "could not start keep-alive service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasses session", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AirGo Agent")
            .setContentText("Connected to the glasses - keeping Bluetooth and Wi-Fi awake")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        // connectedDevice: the glasses link. microphone: speech input while the screen is off.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        runCatching { startForeground(1, notification, types) }
            .onFailure {
                // Microphone type needs RECORD_AUDIO granted; fall back to the link alone.
                Log.w(TAG, "startForeground with mic failed, retrying without", it)
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }

        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airgo:session")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airgo:photos")
                .apply { setReferenceCounted(false); acquire() }
        }
        Log.i(TAG, "keep-alive on (wake lock + wifi lock)")
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        wake = null
        wifi = null
        Log.i(TAG, "keep-alive off")
        super.onDestroy()
    }
}
