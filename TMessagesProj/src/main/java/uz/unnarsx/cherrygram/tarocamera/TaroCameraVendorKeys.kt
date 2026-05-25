/*
 * TaroCamera Engine — vendor-namespaced capture key plumbing.
 *
 * Standard `CONTROL_ZOOM_RATIO` only exposes the zoom range the HAL
 * advertises to "regular" apps. On Realme/OPPO/OnePlus SoCs the wide
 * sub-sensor is reachable via vendor keys outside that range — most
 * notably `com.oplus.original.zoomRatio`, which the stock Realme Camera
 * app sets to e.g. 0.6 to switch to ultra-wide while leaving the main
 * logical camera open.
 *
 * Those keys are vendor-namespaced and not part of the public
 * `CaptureRequest.Key` constants. The `CaptureRequest.Key` constructor
 * happens to be public-package, so we can build one via reflection and
 * push values through the standard `CaptureRequest.Builder.set` path.
 *
 * If reflection ever fails (key not registered with the HAL, signature
 * changed, etc.) we log and fall back. Nothing here ever crashes the
 * caller.
 */
package uz.unnarsx.cherrygram.tarocamera

import android.hardware.camera2.CaptureRequest
import android.util.Log

private const val TAG = "TaroCamera"

object TaroCameraVendorKeys {

    /** OPPO/Realme. Same payload type as the standard ZOOM_RATIO. */
    private const val OPLUS_ZOOM_RATIO = "com.oplus.original.zoomRatio"

    /** QCom QCamera3 — set the physical sub-camera the session should
     *  route to. Value type is a String containing the physical ID. */
    private const val CODEAURORA_CAMERA_ID = "org.codeaurora.qcamera3.sessionParameters.cameraId"

    /** QCom — kicks the HAL into using the dedicated wide stream pipeline
     *  even when ZOOM_RATIO would not normally route there. */
    private const val CODEAURORA_EXTENDED_ZOOM = "org.codeaurora.qcamera3.sessionParameters.ExtendedMaxZoom"

    @Volatile private var oplusKey: CaptureRequest.Key<Float>? = null
    @Volatile private var codeauroraCameraIdKey: CaptureRequest.Key<ByteArray>? = null
    @Volatile private var codeauroraExtZoomKey: CaptureRequest.Key<Float>? = null
    @Volatile private var loggedFailure = false

    @Suppress("UNCHECKED_CAST")
    fun applyOplusZoomRatio(builder: CaptureRequest.Builder, ratio: Float): Boolean {
        val key = oplusKey ?: buildFloatKey(OPLUS_ZOOM_RATIO).also { oplusKey = it } ?: return false
        return try {
            builder.set(key, ratio)
            Log.i(TAG, "OPLUS vendor zoomRatio set to $ratio")
            true
        } catch (t: Throwable) {
            if (!loggedFailure) {
                Log.w(TAG, "OPLUS vendor zoomRatio set failed", t)
                loggedFailure = true
            }
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applyCodeauroraCameraId(builder: CaptureRequest.Builder, physicalId: String): Boolean {
        val key = codeauroraCameraIdKey
            ?: buildKey(CODEAURORA_CAMERA_ID, ByteArray::class.java).also { codeauroraCameraIdKey = it as CaptureRequest.Key<ByteArray>? } as? CaptureRequest.Key<ByteArray>
            ?: return false
        return try {
            // qcamera3 expects a null-terminated UTF-8 byte payload.
            val payload = (physicalId + "\u0000").toByteArray(Charsets.UTF_8)
            builder.set(key, payload)
            Log.i(TAG, "codeaurora cameraId set to '$physicalId'")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "codeaurora cameraId set failed", t)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun applyCodeauroraExtendedZoom(builder: CaptureRequest.Builder, ratio: Float): Boolean {
        val key = codeauroraExtZoomKey ?: buildFloatKey(CODEAURORA_EXTENDED_ZOOM).also { codeauroraExtZoomKey = it } ?: return false
        return try {
            builder.set(key, ratio)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildFloatKey(name: String): CaptureRequest.Key<Float>? =
        buildKey(name, Float::class.javaObjectType) as? CaptureRequest.Key<Float>

    /**
     * Builds a [CaptureRequest.Key] via reflection. The two-argument
     * `(String, Class)` constructor is package-private on AOSP but
     * accessible reflectively on every Android version where these
     * vendor extensions exist.
     */
    private fun <T> buildKey(name: String, type: Class<T>): CaptureRequest.Key<*>? {
        return try {
            val keyClass = CaptureRequest.Key::class.java
            val ctor = keyClass.getDeclaredConstructor(String::class.java, Class::class.java)
            ctor.isAccessible = true
            ctor.newInstance(name, type) as CaptureRequest.Key<*>
        } catch (t: Throwable) {
            Log.w(TAG, "buildKey($name) failed", t)
            null
        }
    }
}
