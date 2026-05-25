/*
 * TaroGram SmartProxy — manager / lifecycle controller.
 *
 * Owns the running state of the native proxy and supervises it from a
 * dedicated background thread. The actual long-lived process lives in
 * [SmartProxyService] (a foreground service) so Android does not kill it
 * while the app is backgrounded.
 *
 * The watchdog logic is intentionally simple: every N seconds we ask the
 * Go side for its stats blob (parsed as JSON via Jackson which is already
 * on the classpath). If the proxy reports "stopped" or "fatal" we restart
 * it. We do not micromanage individual DC connections — that's the Go
 * side's job.
 */
package uz.unnarsx.cherrygram.smartproxy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.ObjectMapper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.tgnet.ConnectionsManager

object SmartProxyManager {

    private const val TAG = "TaroSmartProxy"

    @Volatile private var workerThread: HandlerThread? = null
    @Volatile private var worker: Handler? = null
    @Volatile private var watchdogRunning = false
    @Volatile private var lastStartFailed = false

    @JvmStatic
    val isRunning: Boolean get() = workerThread != null && !lastStartFailed

    /**
     * Start the proxy. Idempotent: calling [start] while already running is a no-op.
     * Always returns quickly; the actual native call happens on the worker thread.
     */
    fun start(ctx: Context = ApplicationLoader.applicationContext) {
        if (workerThread != null) {
            FileLog.d("$TAG: start() called while already running — ignoring")
            return
        }
        SmartProxyConfig.enabled = true

        val thread = HandlerThread("TaroSmartProxy-worker").also { it.start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        worker = handler

        handler.post {
            try {
                val cacheDir = ctx.cacheDir.absolutePath
                NativeProxy.setCfProxyCacheDir(cacheDir)
                NativeProxy.setCfProxyConfig(
                    enabled = SmartProxyConfig.cloudFlareEnabled,
                    priority = false,
                    userDomain = SmartProxyConfig.cloudFlareDomain,
                )
                NativeProxy.setPoolSize(SmartProxyConfig.poolSize)

                val rc = NativeProxy.startProxy(
                    host = SmartProxyConfig.host,
                    port = SmartProxyConfig.port,
                    dcIps = "", // auto-DC routing — Go side queries Telegram for current DC IPs
                    secret = SmartProxyConfig.secret,
                    verbose = 0,
                )
                if (rc != 0) {
                    lastStartFailed = true
                    FileLog.e("$TAG: native StartProxy returned $rc")
                    return@post
                }
                lastStartFailed = false
                FileLog.d("$TAG: native StartProxy OK on ${SmartProxyConfig.host}:${SmartProxyConfig.port}")

                if (SmartProxyConfig.autoApplyToTelegram) {
                    applyProxyToTelegram()
                }

                startWatchdog()

                // Also bring up the foreground service so Doze does not freeze us
                startService(ctx)
            } catch (t: Throwable) {
                FileLog.e("$TAG: start failed", t)
                lastStartFailed = true
            }
        }
    }

    /** Stop the proxy. Idempotent. */
    fun stop(ctx: Context = ApplicationLoader.applicationContext) {
        SmartProxyConfig.enabled = false
        val w = worker
        val t = workerThread
        worker = null
        workerThread = null
        watchdogRunning = false

        w?.post {
            try {
                if (SmartProxyConfig.autoApplyToTelegram) {
                    // Restore the user's original (cleared) proxy state.
                    ConnectionsManager.setProxySettings(false, "", 1080, "", "", "")
                }
                val rc = NativeProxy.stopProxy()
                FileLog.d("$TAG: native StopProxy returned $rc")
            } catch (th: Throwable) {
                FileLog.e("$TAG: stop failed", th)
            } finally {
                t?.quitSafely()
                stopService(ctx)
            }
        }
    }

    /** Apply 127.0.0.1:port to all of Telegram's network stacks with the live secret. */
    private fun applyProxyToTelegram() {
        val secret = NativeProxy.getSecretWithPrefix() ?: SmartProxyConfig.secret
        AndroidUtilities.runOnUIThread {
            ConnectionsManager.setProxySettings(
                true,
                SmartProxyConfig.host,
                SmartProxyConfig.port,
                "",
                "",
                secret,
            )
            FileLog.d("$TAG: applied 127.0.0.1:${SmartProxyConfig.port} to Telegram (secret=${secret.take(8)}…)")
        }
    }

    private val mapper = ObjectMapper()

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        val intervalMs = SmartProxyConfig.watchdogIntervalSec.coerceAtLeast(5) * 1000L
        worker?.postDelayed(object : Runnable {
            override fun run() {
                if (!watchdogRunning) return
                try {
                    val stats = NativeProxy.getStats()
                    if (stats == null) {
                        FileLog.w("$TAG: watchdog — null stats, restarting native proxy")
                        recoverInline()
                    } else {
                        val node = mapper.readTree(stats)
                        val running = node.path("running").asBoolean(true)
                        val lastErr = node.path("last_error").asText("")
                        if (!running || lastErr.contains("fatal", ignoreCase = true)) {
                            FileLog.w("$TAG: watchdog — proxy down (running=$running, err=$lastErr) — recovering")
                            recoverInline()
                        }
                    }
                } catch (t: Throwable) {
                    FileLog.w("$TAG: watchdog tick failed: ${t.message}")
                }
                worker?.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    private fun recoverInline() {
        try { NativeProxy.stopProxy() } catch (_: Throwable) {}
        try {
            NativeProxy.startProxy(
                host = SmartProxyConfig.host,
                port = SmartProxyConfig.port,
                dcIps = "",
                secret = SmartProxyConfig.secret,
                verbose = 0,
            )
            if (SmartProxyConfig.autoApplyToTelegram) applyProxyToTelegram()
        } catch (t: Throwable) {
            FileLog.e("$TAG: inline recovery failed", t)
        }
    }

    private fun startService(ctx: Context) {
        val intent = Intent(ctx, SmartProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopService(ctx: Context) {
        ctx.stopService(Intent(ctx, SmartProxyService::class.java))
    }

    /** Called from [org.telegram.messenger.ApplicationLoader] after app startup. */
    @JvmStatic
    fun maybeAutoStart() {
        if (SmartProxyConfig.enabled) {
            FileLog.d("$TAG: maybeAutoStart — user has SmartProxy enabled, starting")
            start()
        }
    }
}
