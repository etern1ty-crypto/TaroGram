/*
 * TaroCamera Engine — persistent settings.
 *
 * Lives in a dedicated shared-prefs file (`TG_TaroCamera`) so the user can
 * uninstall TaroCamera by clearing that one file without nuking the rest
 * of Cherrygram's preferences. Mirrors the layout of SmartProxyConfig.
 */
package uz.unnarsx.cherrygram.tarocamera

import org.telegram.messenger.ApplicationLoader

object TaroCameraConfig {
    private const val PREFS_NAME = "TG_TaroCamera"

    // Lens selection. Empty string means "auto-pick whatever the enumerator
    // marks as the best back lens".
    private const val KEY_BACK_LENS_ID = "back_lens_id"
    private const val KEY_FRONT_LENS_ID = "front_lens_id"

    // Quality knobs. Defaults mirror what Telegram's round videos use
    // (~640x480 @30fps) but the user can crank these once they switch to
    // TaroCamera for full-resolution recording.
    private const val KEY_RESOLUTION = "resolution"       // "auto"|"480"|"720"|"1080"|"2k"|"4k"
    private const val KEY_FPS = "fps"                     // 24|30|60
    private const val KEY_VIDEO_BITRATE_KBPS = "video_bitrate" // 1024..32768
    private const val KEY_AUDIO_BITRATE_KBPS = "audio_bitrate" // 48..256
    private const val KEY_STABILIZATION = "stabilization" // "off"|"preview"|"video"|"ois"
    private const val KEY_HDR = "hdr"                     // "off"|"auto"|"on"
    private const val KEY_NOISE_REDUCTION = "nr"          // "off"|"fast"|"high_quality"
    private const val KEY_EDGE_ENHANCEMENT = "edge"       // "off"|"fast"|"high_quality"
    private const val KEY_USE_LOGICAL_PHYSICAL_SWITCH = "logical_phys_switch"
    // When true: open the device's logical multi-camera and switch lenses
    // by setting CONTROL_ZOOM_RATIO / OutputConfiguration.setPhysicalCameraId.
    // When false: open each physical id directly (works for hidden IDs on
    // Realme/OPPO when the HAL allows it).

    // Lens mode for the back camera. "auto" = whatever the lens picker
    // says; "main"/"wide"/"tele" override zoom ratio so that HAL-internal
    // routing on logical-camera-style SKUs (Realme/OPPO/OnePlus) picks the
    // matching physical sensor without us ever having to open it directly.
    private const val KEY_LENS_MODE = "lens_mode" // "auto"|"main"|"wide"|"tele"
    private const val KEY_CUSTOM_ZOOM_RATIO = "custom_zoom_ratio"
    private const val KEY_WIDE_ZOOM_RATIO = "wide_zoom_ratio"
    private const val KEY_TELE_ZOOM_RATIO = "tele_zoom_ratio"

    private val prefs by lazy { ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0) }

    var backLensId: String
        get() = prefs.getString(KEY_BACK_LENS_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BACK_LENS_ID, v).apply()

    var frontLensId: String
        get() = prefs.getString(KEY_FRONT_LENS_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FRONT_LENS_ID, v).apply()

    var resolution: String
        get() = prefs.getString(KEY_RESOLUTION, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_RESOLUTION, v).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 30)
        set(v) = prefs.edit().putInt(KEY_FPS, v).apply()

    var videoBitrateKbps: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE_KBPS, 4096)
        set(v) = prefs.edit().putInt(KEY_VIDEO_BITRATE_KBPS, v).apply()

    var audioBitrateKbps: Int
        get() = prefs.getInt(KEY_AUDIO_BITRATE_KBPS, 128)
        set(v) = prefs.edit().putInt(KEY_AUDIO_BITRATE_KBPS, v).apply()

    var stabilization: String
        get() = prefs.getString(KEY_STABILIZATION, "video") ?: "video"
        set(v) = prefs.edit().putString(KEY_STABILIZATION, v).apply()

    var hdr: String
        get() = prefs.getString(KEY_HDR, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_HDR, v).apply()

    var noiseReduction: String
        get() = prefs.getString(KEY_NOISE_REDUCTION, "fast") ?: "fast"
        set(v) = prefs.edit().putString(KEY_NOISE_REDUCTION, v).apply()

    var edgeEnhancement: String
        get() = prefs.getString(KEY_EDGE_ENHANCEMENT, "fast") ?: "fast"
        set(v) = prefs.edit().putString(KEY_EDGE_ENHANCEMENT, v).apply()

    var useLogicalPhysicalSwitch: Boolean
        get() = prefs.getBoolean(KEY_USE_LOGICAL_PHYSICAL_SWITCH, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_LOGICAL_PHYSICAL_SWITCH, v).apply()

    var lensMode: String
        get() = prefs.getString(KEY_LENS_MODE, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_LENS_MODE, v).apply()

    var customZoomRatio: Float
        get() = prefs.getFloat(KEY_CUSTOM_ZOOM_RATIO, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_CUSTOM_ZOOM_RATIO, v).apply()

    var wideZoomRatio: Float
        get() = prefs.getFloat(KEY_WIDE_ZOOM_RATIO, 0.6f)
        set(v) = prefs.edit().putFloat(KEY_WIDE_ZOOM_RATIO, v).apply()

    var teleZoomRatio: Float
        get() = prefs.getFloat(KEY_TELE_ZOOM_RATIO, 3.0f)
        set(v) = prefs.edit().putFloat(KEY_TELE_ZOOM_RATIO, v).apply()

    /**
     * Resolves the desired CONTROL_ZOOM_RATIO value for the back camera,
     * clamped to the HAL-supported zoom range. Returns null for "auto" or
     * when the HAL has no zoom range advertised (caller should not set the
     * key in that case).
     */
    fun resolveBackZoomRatio(range: android.util.Range<Float>?): Float? {
        if (range == null) return null
        val target = when (lensMode) {
            "main" -> 1.0f
            "wide" -> wideZoomRatio
            "tele" -> teleZoomRatio
            "custom" -> customZoomRatio
            else -> return null
        }
        return target.coerceIn(range.lower, range.upper)
    }

    fun targetResolution(): android.util.Size? = when (resolution) {
        "480" -> android.util.Size(640, 480)
        "720" -> android.util.Size(1280, 720)
        "1080" -> android.util.Size(1920, 1080)
        "2k" -> android.util.Size(2560, 1440)
        "4k" -> android.util.Size(3840, 2160)
        else -> null // "auto"
    }
}
