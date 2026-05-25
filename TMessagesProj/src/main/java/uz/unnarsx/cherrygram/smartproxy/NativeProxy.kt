/*
 * TaroGram SmartProxy — JNA bindings to libtgwsproxy.so
 *
 * The native library comes from https://github.com/amurcanov/tg-ws-proxy-android
 * which is licensed under GPL-3.0. This file (the Kotlin glue) is licensed under
 * the same terms as the rest of TaroGram / Cherrygram (GPL-2.0-or-later).
 *
 * The .so files live in TMessagesProj/jniLibs/<abi>/libtgwsproxy.so along with
 * libjnidispatch.so (the JNA runtime). They are bundled into the APK by the
 * existing jniLibs source set declaration in build.gradle.
 */
package uz.unnarsx.cherrygram.smartproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Low-level JNA interface to libtgwsproxy.so. Mirrors the exported symbols
 * declared in tg-ws-proxy.go (StartProxy, StopProxy, etc.).
 *
 * Method names match the Go side exactly (Go cgo exports preserve case).
 */
interface ProxyLibrary : Library {
    companion object {
        val INSTANCE: ProxyLibrary by lazy {
            Native.load("tgwsproxy", ProxyLibrary::class.java) as ProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetFakeTls(enabled: Int, domain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

/**
 * Thin Kotlin wrapper around [ProxyLibrary]. All calls are blocking and
 * delegated directly to the native side; cross-thread safety is the caller's
 * responsibility (Go internally uses its own scheduler).
 */
object NativeProxy {

    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int {
        return ProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose)
    }

    fun stopProxy(): Int = ProxyLibrary.INSTANCE.StopProxy()

    fun setPoolSize(size: Int) { ProxyLibrary.INSTANCE.SetPoolSize(size) }

    fun setCfProxyCacheDir(cacheDir: String) {
        ProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        ProxyLibrary.INSTANCE.SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain,
        )
    }

    fun setFakeTls(enabled: Boolean, domain: String = "") {
        ProxyLibrary.INSTANCE.SetFakeTls(if (enabled) 1 else 0, domain)
    }

    /** Returns the secret with the proper MTProto prefix (dd... or ee+domain_hex). */
    fun getSecretWithPrefix(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }

    /** JSON-encoded stats produced by the Go side: connections, bytes, last error… */
    fun getStats(): String? {
        val ptr = ProxyLibrary.INSTANCE.GetStats() ?: return null
        val res = ptr.getString(0)
        ProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
}
