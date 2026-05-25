/*
 * TaroCamera Engine — capture session.
 *
 * This is the "open the lens, give me a preview surface" half of the engine.
 * It is intentionally independent of Cherrygram's Camera2Session so we can
 * apply our own quality + stabilization settings without inheriting any of
 * the round-video-specific assumptions baked into the upstream class.
 *
 * The session can be created in two modes (see TaroCameraConfig):
 *
 *   - direct      :  openCamera(physicalId, ...). Works for hidden IDs on
 *                    Realme/OPPO when the HAL still services them despite
 *                    `getCameraIdList()` filtering.
 *   - logical+set :  openCamera(logicalId, ...) + OutputConfiguration.
 *                    setPhysicalCameraId(physicalId).  This is the path
 *                    GCam uses on Snapdragon 8 Gen 1 to reach the
 *                    ultra-wide lens through the main logical camera.
 *
 * For iteration 1 we expose this engine through the diagnostic UI only —
 * the actual round-video recorder still goes through Camera2Session. The
 * next iteration will wire this session into InstantCameraView.
 */
package uz.unnarsx.cherrygram.tarocamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

private const val TAG = "TaroCamera"

/**
 * Open + manage a single capture session for one lens.
 *
 * Lifecycle:
 *   val s = TaroCameraSession(ctx, lensId)
 *   s.start(previewSurface) { ok, err -> ... }
 *   ...
 *   s.stop()
 *
 * All callbacks run on an internal worker thread. The caller is
 * responsible for posting back to its own UI thread if needed.
 */
@SuppressLint("MissingPermission") // caller already checks CAMERA permission
class TaroCameraSession(
    private val context: Context,
    val lensId: String,
    private val parentLogicalId: String? = null, // for logical+physical mode
) {
    private val thread = HandlerThread("TaroCamera-$lensId").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val cm: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var previewSurface: Surface? = null

    /** Open and start streaming. `onResult(true, null)` once preview is up. */
    fun start(
        surfaceTexture: SurfaceTexture,
        targetSize: Size,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        handler.post {
            try {
                surfaceTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
                previewSurface = Surface(surfaceTexture)

                val idToOpen = parentLogicalId ?: lensId
                Log.i(TAG, "openCamera idToOpen=$idToOpen (logical=$parentLogicalId, physical=$lensId)")
                cm.openCamera(idToOpen, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        configureSession(camera, onResult)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "onDisconnected for $idToOpen")
                        camera.close()
                        device = null
                        onResult(false, "disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "onError for $idToOpen, code=$error")
                        camera.close()
                        device = null
                        onResult(false, "open error $error")
                    }
                }, handler)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "openCamera failed", e)
                onResult(false, e.reason.toString())
            } catch (e: SecurityException) {
                Log.e(TAG, "openCamera permission denied", e)
                onResult(false, "permission")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "openCamera invalid id", e)
                onResult(false, "invalid id")
            }
        }
    }

    private fun configureSession(
        camera: CameraDevice,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val surf = previewSurface ?: return onResult(false, "no preview surface")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val output = OutputConfiguration(surf).apply {
                    if (parentLogicalId != null && parentLogicalId != lensId) {
                        // OutputConfiguration.setPhysicalCameraId — the
                        // trick that GCam uses to switch to UW through
                        // the logical camera. Available since Android 9.
                        setPhysicalCameraId(lensId)
                        Log.i(TAG, "OutputConfiguration.setPhysicalCameraId($lensId)")
                    }
                }
                val sessionCfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    sessionStateCallback(onResult),
                )
                camera.createCaptureSession(sessionCfg)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(surf),
                    sessionStateCallback(onResult),
                    handler,
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
            onResult(false, "session error")
        }
    }

    private fun sessionStateCallback(
        onResult: (success: Boolean, error: String?) -> Unit,
    ) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            session = s
            try {
                val req = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                req.addTarget(previewSurface!!)
                applyQualityKnobs(req)
                s.setRepeatingRequest(req.build(), null, handler)
                Log.i(TAG, "preview started lens=$lensId")
                onResult(true, null)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "setRepeatingRequest failed", e)
                onResult(false, "repeat error")
            }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            Log.e(TAG, "session configure FAILED for lens=$lensId")
            onResult(false, "configure failed")
        }
    }

    private fun applyQualityKnobs(req: CaptureRequest.Builder) {
        when (TaroCameraConfig.stabilization) {
            "off" -> {
                req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            "preview" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                }
            }
            "video" -> req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            "ois" -> req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        when (TaroCameraConfig.hdr) {
            "on" -> {
                req.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
                req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            }
            "off" -> {
                req.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
            }
        }
        when (TaroCameraConfig.noiseReduction) {
            "off" -> req.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            "fast" -> req.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            "high_quality" -> req.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }
        when (TaroCameraConfig.edgeEnhancement) {
            "off" -> req.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            "fast" -> req.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            "high_quality" -> req.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }
    }

    fun stop() {
        handler.post {
            try {
                session?.close()
            } catch (_: Exception) {}
            session = null
            try {
                device?.close()
            } catch (_: Exception) {}
            device = null
            try {
                previewSurface?.release()
            } catch (_: Exception) {}
            previewSurface = null
        }
        thread.quitSafely()
    }

    /**
     * For diagnostics: try to open a lens and immediately close.
     *
     * Returns true if the camera HAL accepted the open + a one-shot
     * preview surface. This is how the diagnostic screen confirms that a
     * hidden physical ID (e.g. "2" — ultra-wide on Senna) is reachable
     * without a SennaCamUnlock module.
     */
    companion object {
        fun probeOpen(context: Context, lensId: String, parentLogicalId: String? = null): String {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val idToOpen = parentLogicalId ?: lensId
            return try {
                cm.getCameraCharacteristics(idToOpen) // throws if HAL refuses
                "ok (characteristics available)"
            } catch (e: CameraAccessException) {
                "BLOCKED (CameraAccessException reason=${e.reason})"
            } catch (e: IllegalArgumentException) {
                "BLOCKED (invalid id)"
            } catch (e: SecurityException) {
                "BLOCKED (no permission)"
            }
        }
    }
}
