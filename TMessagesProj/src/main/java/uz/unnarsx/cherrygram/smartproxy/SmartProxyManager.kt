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

    // Diagnostic / recovery state — exposed via [snapshot] for the diagnostic export.
    @Volatile private var lastDownAtMs: Long = 0L
    @Volatile private var lastPingOk: Boolean? = null
    @Volatile private var lastRecoveryAttemptMs: Long = 0L
    @Volatile private var lastRecoveryReason: String = ""
    @Volatile private var lastTransportFlip: String = ""
    @Volatile private var consecutiveFailures: Int = 0

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
                        recoverInline(reason = "null_stats")
                    } else {
                        val node = mapper.readTree(stats)
                        val running = node.path("running").asBoolean(true)
                        val lastErr = node.path("last_error").asText("")
                        if (!running || lastErr.contains("fatal", ignoreCase = true)) {
                            FileLog.w("$TAG: watchdog — proxy down (running=$running, err=$lastErr) — recovering")
                            recoverInline(reason = if (!running) "native_not_running" else lastErr)
                        } else {
                            // Healthy tick — reset the down marker and the failure streak.
                            if (lastDownAtMs != 0L) FileLog.d("$TAG: watchdog — back to healthy state")
                            lastDownAtMs = 0L
                            consecutiveFailures = 0
                        }
                    }
                } catch (t: Throwable) {
                    FileLog.w("$TAG: watchdog tick failed: ${t.message}")
                }
                worker?.postDelayed(this, intervalMs)
            }
        }, intervalMs)
    }

    /**
     * Watchdog v2 recovery cascade:
     *  1. Mark t0 = down detected.
     *  2. Wait recoveryCooldownSec (default 10s) — the proxy may already be recovering by itself.
     *  3. Probe 8.8.8.8:53 with a 3s TCP connect.
     *     - reachable  -> internet is fine, the proxy is what's broken. After
     *       N>=3 consecutive failures and cloudFlareAutoFallback=true we also
     *       toggle cloudFlareEnabled (CDN vs direct) before restarting.
     *       Then regenerate the secret (in case the previous one is leaked /
     *       mid-flight rejected) and restart.
     *     - unreachable -> device is offline. Sleep noInternetBackoffSec (30s)
     *       and let the next watchdog tick re-evaluate. We do NOT touch the
     *       proxy in this state because churning helps nothing.
     */
    private fun recoverInline(reason: String) {
        lastRecoveryReason = reason
        val now = System.currentTimeMillis()
        if (lastDownAtMs == 0L) lastDownAtMs = now

        val cooldownMs = SmartProxyConfig.recoveryCooldownSec.coerceAtLeast(1) * 1000L
        if (now - lastDownAtMs < cooldownMs) {
            FileLog.d("$TAG: recovery cooldown ${(now - lastDownAtMs) / 1000}s/${cooldownMs / 1000}s — waiting")
            return
        }

        val ok = pingInternet()
        lastPingOk = ok
        if (!ok) {
            consecutiveFailures += 1
            FileLog.w("$TAG: 8.8.8.8 unreachable — device offline (consecutiveFailures=$consecutiveFailures); backoff ${SmartProxyConfig.noInternetBackoffSec}s")
            // Reset the down marker so the cooldown timer starts fresh; the
            // next watchdog tick (intervalSec later) will retry.
            lastDownAtMs = now
            return
        }

        // Internet is fine, the proxy is what's broken. Flip CloudFlare if we've failed too many times in a row.
        consecutiveFailures += 1
        val flipped = if (SmartProxyConfig.cloudFlareAutoFallback && consecutiveFailures >= 3) {
            val newValue = !SmartProxyConfig.cloudFlareEnabled
            SmartProxyConfig.cloudFlareEnabled = newValue
            lastTransportFlip = if (newValue) "direct -> cloudflare" else "cloudflare -> direct"
            FileLog.w("$TAG: recovery — flipping transport ($lastTransportFlip) after $consecutiveFailures failures")
            true
        } else false

        // Regenerate the secret on every recovery — Telegram's MTProto pipeline
        // sometimes considers an existing 16-byte secret poisoned after a
        // server-side reject. A fresh secret + reapply is essentially free.
        try {
            SmartProxyConfig.secret = ByteArray(16).also { kotlin.random.Random.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { /* keep existing secret on failure */ }

        lastRecoveryAttemptMs = now
        FileLog.w("$TAG: recovery — restarting native proxy (flipped=$flipped, reason=$reason)")
        try { NativeProxy.stopProxy() } catch (_: Throwable) {}
        try {
            NativeProxy.setCfProxyConfig(
                enabled = SmartProxyConfig.cloudFlareEnabled,
                priority = false,
                userDomain = SmartProxyConfig.cloudFlareDomain,
            )
            val rc = NativeProxy.startProxy(
                host = SmartProxyConfig.host,
                port = SmartProxyConfig.port,
                dcIps = "",
                secret = SmartProxyConfig.secret,
                verbose = 0,
            )
            if (rc == 0) {
                lastDownAtMs = 0L
                // Keep consecutiveFailures as-is — the next OK tick resets it.
                if (SmartProxyConfig.autoApplyToTelegram) applyProxyToTelegram()
            } else {
                FileLog.e("$TAG: recovery — native StartProxy returned $rc")
            }
        } catch (t: Throwable) {
            FileLog.e("$TAG: recovery — startProxy threw", t)
        }
    }

    /** TCP connect to 8.8.8.8:53 with a short timeout. Cheap and accurate (DNS port is open everywhere). */
    private fun pingInternet(): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("8.8.8.8", 53), 3000)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** JSON snapshot of the current recovery state; consumed by the diagnostic exporter. */
    @JvmStatic
    fun snapshotJson(): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"running\":").append(isRunning).append(',')
        sb.append("\"enabled\":").append(SmartProxyConfig.enabled).append(',')
        sb.append("\"host\":\"").append(SmartProxyConfig.host).append('\"').append(',')
        sb.append("\"port\":").append(SmartProxyConfig.port).append(',')
        sb.append("\"cloudFlareEnabled\":").append(SmartProxyConfig.cloudFlareEnabled).append(',')
        sb.append("\"cloudFlareAutoFallback\":").append(SmartProxyConfig.cloudFlareAutoFallback).append(',')
        sb.append("\"watchdogIntervalSec\":").append(SmartProxyConfig.watchdogIntervalSec).append(',')
        sb.append("\"recoveryCooldownSec\":").append(SmartProxyConfig.recoveryCooldownSec).append(',')
        sb.append("\"noInternetBackoffSec\":").append(SmartProxyConfig.noInternetBackoffSec).append(',')
        sb.append("\"consecutiveFailures\":").append(consecutiveFailures).append(',')
        sb.append("\"lastDownAtMs\":").append(lastDownAtMs).append(',')
        sb.append("\"lastRecoveryAttemptMs\":").append(lastRecoveryAttemptMs).append(',')
        sb.append("\"lastPingOk\":").append(lastPingOk).append(',')
        sb.append("\"lastRecoveryReason\":\"").append(lastRecoveryReason.replace("\"", "\\\"")).append('\"').append(',')
        sb.append("\"lastTransportFlip\":\"").append(lastTransportFlip).append('\"')
        sb.append('}')
        return sb.toString()
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
