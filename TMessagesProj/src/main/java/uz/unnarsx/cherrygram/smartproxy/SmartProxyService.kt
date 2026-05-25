/*
 * TaroGram SmartProxy — foreground service host (v2: survives task removal).
 *
 * The actual network work is done by libtgwsproxy.so on a worker thread
 * owned by [SmartProxyManager]. This service only exists to satisfy the
 * Android foreground service contract (notification + lifecycle binding)
 * so the OS does not freeze the proxy under Doze / battery saver.
 *
 * v2 changes:
 *  - onTaskRemoved schedules a self-restart via AlarmManager so swiping the
 *    app away from Recents does NOT take the proxy with it.
 *  - the notification carries a "Stop" action so the user can kill the
 *    proxy without opening the app.
 *  - foregroundServiceType=specialUse (declared in the manifest) gives us
 *    longer-lived behaviour on Android 14+.
 */
package uz.unnarsx.cherrygram.smartproxy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity

class SmartProxyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel()
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FileLog.d("$TAG: notification Stop tapped — stopping proxy")
            SmartProxyManager.stop(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLog.d("$TAG: task removed (user swiped TaroGram away); scheduling restart so proxy survives")
        if (!SmartProxyConfig.enabled) {
            super.onTaskRemoved(rootIntent)
            return
        }
        val restartIntent = Intent(applicationContext, SmartProxyService::class.java).apply {
            setPackage(packageName)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getService(applicationContext, RESTART_REQ, restartIntent, piFlags)
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            // Restart in 1s. We use ELAPSED_REALTIME so it survives wall-clock
            // changes; we deliberately do NOT use setExactAndAllowWhileIdle
            // because we do not have the SCHEDULE_EXACT_ALARM permission and
            // an inexact 1-sec wakeup is fine for a proxy daemon.
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1_000L, pi)
        } catch (t: Throwable) {
            FileLog.e("$TAG: AlarmManager.set failed", t)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        val stopIntent = Intent(this, SmartProxyService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, STOP_REQ, stopIntent, pendingFlags)

        val title = LocaleController.getString(R.string.TG_SmartProxy_Title)
        val text = LocaleController.formatString(
            "TG_SmartProxy_Running",
            R.string.TG_SmartProxy_Running,
            "${SmartProxyConfig.host}:${SmartProxyConfig.port}",
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .addAction(0, LocaleController.getString(R.string.TG_SmartProxy_NotifStop), stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "TaroGram Smart Proxy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Local MTProto / CloudFlare WS proxy"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "TaroSmartProxy"
        private const val CHANNEL_ID = "TaroGram-SmartProxy"
        private const val NOTIFICATION_ID = 0x715A40
        private const val STOP_REQ = 0x515A41
        private const val RESTART_REQ = 0x515A42

        const val ACTION_STOP = "uz.unnarsx.cherrygram.smartproxy.STOP"
    }
}
