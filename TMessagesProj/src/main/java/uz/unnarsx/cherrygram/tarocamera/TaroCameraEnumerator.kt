/*
 * TaroCamera Engine — a TaroGram-owned Camera2 stack.
 *
 * This file is the camera enumerator. Its job is to answer one question:
 *   "Which physical camera lenses can we ACTUALLY open on this device?"
 *
 * On Realme / OnePlus / OPPO firmware (Senna / SM8450 family in particular),
 * `CameraManager.getCameraIdList()` is filtered per-package by an OPLUS HAL
 * whitelist. Third-party apps usually only see "0" (rear logical) and "1"
 * (front), even when the device has additional physical cameras hard-wired:
 *
 *   ID 0 — rear main (logical multi-camera on Snapdragon 8 Gen 1)
 *   ID 1 — front
 *   ID 2 — ultra-wide  (HIDDEN)
 *   ID 3 — microscope  (HIDDEN, very narrow FoV)
 *   ID 4 — auxiliary   (depth / monochrome / ToF — varies per SKU)
 *
 * Two tricks let us reach the hidden ones from a regular app context:
 *   1. Logical-camera physical IDs.  The Snapdragon logical camera at ID 0
 *      exposes its physical sub-cameras through
 *      `getPhysicalCameraIds()`. We can address them at session creation
 *      time via `OutputConfiguration.setPhysicalCameraId()` without ever
 *      asking the HAL to list them as standalone IDs.
 *   2. Blind probe.  `CameraManager.openCamera()` and
 *      `getCameraCharacteristics()` accept ARBITRARY string IDs. The HAL
 *      will service the request if the camera physically exists, even when
 *      that ID is filtered out of `getCameraIdList()`. We try IDs 0..9 and
 *      record which ones respond.
 *
 * The result is a `TaroCameraInventory` that the engine, the diagnostic UI,
 * and the recorder all share.
 */
package uz.unnarsx.cherrygram.tarocamera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import android.util.SizeF

private const val TAG = "TaroCamera"

/** A single physical camera lens that TaroGram could open. */
data class TaroLens(
    val id: String,
    val facing: Int,
    val focalLength: Float,
    val sensorSize: SizeF?,
    val maxPreviewSize: Size?,
    val maxRecordSize: Size?,
    val supportedFps: List<Int>,
    val supportsVideoStab: Boolean,
    val supportsOpticalStab: Boolean,
    val supportsHdr: Boolean,
    val isLogicalMultiCamera: Boolean,
    val physicalIds: Set<String>,
    val hiddenFromCameraIdList: Boolean,
    val approxFovDeg: Float,
) {
    /** "main" / "ultra-wide" / "tele" / "front" / "macro" / "unknown" — a
     * heuristic label based on focal length and FoV. The actual lens role
     * varies per SKU, so this is just a hint we surface to the UI. */
    fun guessRole(): String = when {
        facing == CameraCharacteristics.LENS_FACING_FRONT -> "front"
        approxFovDeg >= 95f -> "ultra-wide"
        approxFovDeg <= 30f -> "tele"
        focalLength in 2.5f..3.5f -> "ultra-wide"
        focalLength in 4f..7f -> "main"
        focalLength > 7f -> "tele"
        else -> "unknown"
    }
}

/** All cameras TaroGram detected, ready for the engine + diagnostic UI. */
data class TaroCameraInventory(
    val lenses: List<TaroLens>,
    val visibleIds: List<String>,
    val probedIds: List<String>,
) {
    fun byId(id: String): TaroLens? = lenses.firstOrNull { it.id == id }
    fun back(): List<TaroLens> = lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
    fun front(): List<TaroLens> = lenses.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
}

object TaroCameraEnumerator {

    /** IDs we try blindly when `getCameraIdList()` returns a whitelist. We
     * stop at 9 because Snapdragon HALs rarely expose anything past that. */
    private val BLIND_PROBE_IDS = (0..9).map { it.toString() }

    @Synchronized
    fun enumerate(context: Context): TaroCameraInventory {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val visible: List<String> = try {
            cm.cameraIdList.toList()
        } catch (e: CameraAccessException) {
            Log.w(TAG, "enumerator: getCameraIdList failed", e)
            emptyList()
        }

        val probed = mutableSetOf<String>()
        probed.addAll(visible)

        val candidateIds = LinkedHashSet<String>().apply {
            addAll(visible)
            addAll(BLIND_PROBE_IDS)
            // Logical physical IDs come into play once we look at characteristics.
        }

        val lenses = mutableListOf<TaroLens>()
        for (id in candidateIds) {
            val chars = try {
                cm.getCameraCharacteristics(id)
            } catch (e: Exception) {
                if (!visible.contains(id)) {
                    Log.v(TAG, "blind probe: id=$id not present ($e)")
                }
                continue
            }
            probed.add(id)
            val hidden = !visible.contains(id)
            lenses += describe(id, chars, hidden)
            // Also expand into physical sub-IDs for logical multi-cameras.
            for (physId in lenses.last().physicalIds) {
                if (lenses.any { it.id == physId }) continue
                val pc = try {
                    cm.getCameraCharacteristics(physId)
                } catch (e: Exception) {
                    Log.v(TAG, "blind probe: physical id=$physId failed ($e)")
                    null
                }
                if (pc != null) {
                    probed.add(physId)
                    lenses += describe(physId, pc, hidden = !visible.contains(physId))
                }
            }
        }

        val inventory = TaroCameraInventory(
            lenses = lenses.distinctBy { it.id },
            visibleIds = visible,
            probedIds = probed.toList().sorted(),
        )
        logInventory(inventory)
        return inventory
    }

    private fun describe(id: String, chars: CameraCharacteristics, hidden: Boolean): TaroLens {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
        val focal = focals.firstOrNull() ?: 0f
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
        val recordSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)?.toList() ?: emptyList()
        val maxPreview = previewSizes.maxByOrNull { it.width.toLong() * it.height }
        val maxRecord = recordSizes.maxByOrNull { it.width.toLong() * it.height }
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fpsValues = fpsRanges.flatMap { listOf(it.lower, it.upper) }.toSortedSet().toList()

        val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val supportsVideoStab = stabModes.any { it == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON }
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val supportsOis = oisModes.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }

        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        val supportsHdr = sceneModes.any { it == CameraMetadata.CONTROL_SCENE_MODE_HDR }

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val isLogical = caps.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA }
        val physicalIds = if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.physicalCameraIds ?: emptySet()
        } else emptySet()

        val fov = approxFov(focal, sensorSize)

        return TaroLens(
            id = id,
            facing = facing,
            focalLength = focal,
            sensorSize = sensorSize,
            maxPreviewSize = maxPreview,
            maxRecordSize = maxRecord,
            supportedFps = fpsValues,
            supportsVideoStab = supportsVideoStab,
            supportsOpticalStab = supportsOis,
            supportsHdr = supportsHdr,
            isLogicalMultiCamera = isLogical,
            physicalIds = physicalIds,
            hiddenFromCameraIdList = hidden,
            approxFovDeg = fov,
        )
    }

    private fun approxFov(focal: Float, sensor: SizeF?): Float {
        if (focal <= 0f || sensor == null) return 0f
        // Diagonal field-of-view in degrees:  2 * atan( diag/2 / focal )
        val diag = Math.hypot(sensor.width.toDouble(), sensor.height.toDouble())
        return Math.toDegrees(2.0 * Math.atan(diag / (2.0 * focal))).toFloat()
    }

    private fun logInventory(inv: TaroCameraInventory) {
        Log.i(TAG, "===== TaroCameraEnumerator =====")
        Log.i(TAG, "visible IDs (HAL whitelist): ${inv.visibleIds}")
        Log.i(TAG, "probed  IDs (blind+logical): ${inv.probedIds}")
        for (l in inv.lenses) {
            val tag = if (l.hiddenFromCameraIdList) "HIDDEN" else "VISIBLE"
            Log.i(
                TAG,
                "id=${l.id}  $tag  facing=${facingName(l.facing)}  " +
                    "role=${l.guessRole()}  focal=${l.focalLength}mm  " +
                    "fov=${"%.1f".format(l.approxFovDeg)}deg  " +
                    "logical=${l.isLogicalMultiCamera}  physIds=${l.physicalIds}  " +
                    "stab=${l.supportsVideoStab}  ois=${l.supportsOpticalStab}  hdr=${l.supportsHdr}",
            )
        }
        Log.i(TAG, "================================")
    }

    private fun facingName(f: Int): String = when (f) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }
}
