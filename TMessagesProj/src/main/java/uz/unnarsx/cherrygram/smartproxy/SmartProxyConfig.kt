/*
 * TaroGram SmartProxy — persistent settings.
 *
 * Stored in the dedicated "TG_SmartProxy" shared-preferences file so we do not
 * collide with Cherrygram's main preferences and a backup/restore of the
 * proxy state can be done independently if needed in the future.
 */
package uz.unnarsx.cherrygram.smartproxy

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import kotlin.random.Random

object SmartProxyConfig {

    private const val PREFS_NAME = "TG_SmartProxy"

    private val prefs: SharedPreferences by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Master toggle — does TaroGram start the proxy at boot / on app cold-start? */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** When true, automatically reconfigure Telegram's proxy slot to 127.0.0.1:port on start. */
    var autoApplyToTelegram: Boolean
        get() = prefs.getBoolean("auto_apply", true)
        set(v) = prefs.edit().putBoolean("auto_apply", v).apply()

    /** Local listener host. Hardcoded to loopback so the proxy is never exposed off-device. */
    val host: String = "127.0.0.1"

    /** Local listener port. Default matches the upstream amurcanov client (1443). */
    var port: Int
        get() = prefs.getInt("port", 1443)
        set(v) = prefs.edit().putInt("port", v).apply()

    /**
     * MTProto secret. We auto-generate a random 16-byte hex secret on first run
     * if the user has not supplied one. The Go side accepts a 32-char hex string
     * and emits the `dd...`/`ee...` prefix variant via [NativeProxy.getSecretWithPrefix].
     */
    var secret: String
        get() {
            val saved = prefs.getString("secret", null)
            if (!saved.isNullOrBlank()) return saved
            val generated = ByteArray(16).also { Random.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            prefs.edit().putString("secret", generated).apply()
            return generated
        }
        set(v) = prefs.edit().putString("secret", v).apply()

    /** When true, the Go side multiplexes through CloudFlare WS instead of direct DC TLS. */
    var cloudFlareEnabled: Boolean
        get() = prefs.getBoolean("cf_enabled", true)
        set(v) = prefs.edit().putBoolean("cf_enabled", v).apply()

    /** Optional custom CloudFlare-fronted domain. Empty string = let the Go side pick. */
    var cloudFlareDomain: String
        get() = prefs.getString("cf_domain", "") ?: ""
        set(v) = prefs.edit().putString("cf_domain", v).apply()

    /** Native worker pool size; 0 = let the Go side auto-size by CPU count. */
    var poolSize: Int
        get() = prefs.getInt("pool", 4)
        set(v) = prefs.edit().putInt("pool", v).apply()

    /**
     * Auto-recovery watchdog interval (seconds). Every N seconds the
     * [SmartProxyManager] polls [NativeProxy.getStats]; if the proxy reports
     * no active connection or a fatal error we run the recovery cascade.
     */
    var watchdogIntervalSec: Int
        get() = prefs.getInt("watchdog_sec", 30)
        set(v) = prefs.edit().putInt("watchdog_sec", v).apply()

    /**
     * If true, the watchdog will auto-flip between CloudFlare-WS mode and direct mode
     * when the active transport is down but the device clearly has internet
     * (8.8.8.8 reachable). This is what users want when CloudFlare is blocked
     * locally but a direct DC TLS connection works, and vice versa.
     */
    var cloudFlareAutoFallback: Boolean
        get() = prefs.getBoolean("cf_auto_fallback", true)
        set(v) = prefs.edit().putBoolean("cf_auto_fallback", v).apply()

    /**
     * Cooldown (seconds) after the watchdog observes a "down" state before
     * we run the diagnostic ping. Prevents thrashing when the proxy is just
     * mid-restart or briefly idle.
     */
    var recoveryCooldownSec: Int
        get() = prefs.getInt("recovery_cooldown_sec", 10)
        set(v) = prefs.edit().putInt("recovery_cooldown_sec", v).apply()

    /**
     * Backoff (seconds) when 8.8.8.8 was unreachable — the device probably
     * has no internet right now, so churning the proxy is pointless.
     */
    var noInternetBackoffSec: Int
        get() = prefs.getInt("no_internet_backoff_sec", 30)
        set(v) = prefs.edit().putInt("no_internet_backoff_sec", v).apply()
}
