/*
 * TaroGram SmartProxy — foreground service host.
 *
 * The actual network work is done by libtgwsproxy.so on a worker thread
 * owned by [SmartProxyManager]. This service only exists to satisfy the
 * Android foreground service contract (notification + lifecycle binding)
 * so the OS does not freeze the proxy under Doze / battery saver.
 */
package uz.unnarsx.cherrygram.smartproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        private const val CHANNEL_ID = "TaroGram-SmartProxy"
        private const val NOTIFICATION_ID = 0x715A40
    }
}
