/*
 * This file is part of TaroGram (fork of Cherrygram).
 * Licensed under GNU GPL v. 2 or later.
 *
 * TaroCameraEnhancements applies Camera2 ISP enhancements tailored to
 * Qualcomm-based Realme / OPPO / OnePlus devices (in particular the
 * Realme GT Neo 5 / RMX3706 on the "senna" / taro platform).
 *
 * The enhancements are pure public Camera2 API calls — no vendor tags,
 * no native code, no reflection. Each request is wrapped in try/catch
 * because Camera2 silently rejects unsupported keys on some HALs and
 * we want to degrade gracefully on devices that don't accept a given
 * option.
 *
 * Coverage:
 *   - CONTINUOUS_VIDEO autofocus (smoother than picture AF during recording)
 *   - Hardware video stabilization (HAL-side EIS via QC EISV3 when available)
 *   - Preview stabilization (Android 13+; "sticky" stabilization, less jitter)
 *   - Optical image stabilization
 *   - HQ noise reduction + edge enhancement
 *   - Full face detection (helps the AE meter on subjects)
 *   - Low Light Boost AE mode (Android 15+ for some HALs)
 *
 * Bitrate boosts are in getTaroVideoBitrate / getTaroAudioBitrate so callers
 * encoding instant video messages can opt in.
 */

package uz.unnarsx.cherrygram.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TaroCameraEnhancements {

    /** Manufacturers that share the OPLUS / Qualcomm camera HAL family. */
    private static final Set<String> SUPPORTED_MANUFACTURERS = new HashSet<>(Arrays.asList(
            "realme", "oppo", "oneplus"
    ));

    private TaroCameraEnhancements() {}

    /**
     * Returns true if the device is the Realme GT Neo 5 (senna / taro platform):
     * RMX3706, RMX3708 and related variants (22623 / 22624 / 22625).
     */
    public static boolean isSenna() {
        final String model = String.valueOf(Build.MODEL).toUpperCase(Locale.ROOT);
        return model.startsWith("RMX3706") || model.startsWith("RMX3708");
    }

    /**
     * Returns true for the OPLUS / Qualcomm HAL family (Realme, OPPO, OnePlus).
     * These devices share the same vendor camera stack and benefit from the
     * same Camera2 ISP tuning.
     */
    public static boolean isOplusFamily() {
        return SUPPORTED_MANUFACTURERS.contains(
                String.valueOf(Build.MANUFACTURER).toLowerCase(Locale.ROOT));
    }

    /** Should the Taro Camera2 enhancements be applied on this device? */
    public static boolean isEnhancementSupported() {
        return isOplusFamily();
    }

    /**
     * Apply Camera2 ISP enhancements to a video capture request.
     * Safe to call on any device — no-ops when not supported.
     *
     * @param builder            target capture request builder
     * @param characteristics    characteristics for the active camera (nullable)
     */
    public static void applyVideoCaptureEnhancements(
            CaptureRequest.Builder builder,
            @Nullable CameraCharacteristics characteristics
    ) {
        if (builder == null || !isEnhancementSupported()) {
            return;
        }
        trySet(builder, CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

        // Prefer preview stabilization (Android 13+) for "sticky" results; fall back
        // to regular HAL stabilization otherwise.
        boolean previewStabSet = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            previewStabSet = trySet(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        }
        if (!previewStabSet) {
            trySet(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }

        trySet(builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        trySet(builder, CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        trySet(builder, CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_HIGH_QUALITY);
        trySet(builder, CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL);

        // Low Light Boost AE mode. Constant value 5 corresponds to
        // CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY which is only
        // public on API 35. We pass the raw integer so the code still compiles
        // on lower SDKs; HALs that don't recognise it will reject the request
        // and trySet swallows the exception.
        if (Build.VERSION.SDK_INT >= 35 && characteristics != null
                && supportsLowLightBoost(characteristics)) {
            trySet(builder, CaptureRequest.CONTROL_AE_MODE, 5);
        }
    }

    /**
     * Audio bitrate (bits/s) for instant video messages when the device is in
     * the Taro-enhanced family. Returns 0 if no override should be applied.
     */
    public static int getTaroAudioBitrate() {
        return isEnhancementSupported() ? 96 * 1024 : 0;
    }

    /**
     * Video bitrate (bits/s) for instant video messages when the device is in
     * the Taro-enhanced family. Returns 0 if no override should be applied.
     */
    public static int getTaroVideoBitrate() {
        return isEnhancementSupported() ? 1_200_000 : 0;
    }

    /**
     * Pick the best back-facing camera ID for the TaroGram instant video pipeline.
     * If {@code preferWide} is true, returns the back-facing camera with the
     * shortest focal length (the ultra-wide). Falls back to the first back-facing
     * camera otherwise.
     *
     * Returns {@code null} when the manager fails or no back camera is exposed.
     */
    @Nullable
    public static String pickBackCameraId(@NonNull CameraManager mgr, boolean preferWide) {
        try {
            String[] ids = mgr.getCameraIdList();
            String fallback = null;
            String wideId = null;
            float wideFocal = Float.MAX_VALUE;
            for (String id : ids) {
                CameraCharacteristics ch;
                try {
                    ch = mgr.getCameraCharacteristics(id);
                } catch (Throwable t) {
                    continue;
                }
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                if (fallback == null) fallback = id;
                if (!preferWide) continue;
                float[] focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focals == null || focals.length == 0) continue;
                float minFocal = Float.MAX_VALUE;
                for (float f : focals) if (f > 0 && f < minFocal) minFocal = f;
                if (minFocal < wideFocal) {
                    wideFocal = minFocal;
                    wideId = id;
                }
            }
            return preferWide && wideId != null ? wideId : fallback;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * Dump the camera inventory to logcat. Useful for diagnosing wide / tele
     * camera availability on devices like Realme GT Neo 5 where the HAL
     * whitelists most physical IDs to the stock camera app.
     *
     * Called once per process; subsequent calls are no-ops.
     */
    private static volatile boolean inventoryLogged = false;
    public static void logCameraInventory(@Nullable Context ctx) {
        if (inventoryLogged || ctx == null) return;
        inventoryLogged = true;
        try {
            CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null) return;
            String[] ids = mgr.getCameraIdList();
            FileLog.d("TaroCamera: visible camera IDs = " + Arrays.toString(ids));
            for (String id : ids) {
                logCameraDetail(mgr, id, "  ");
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void logCameraDetail(@NonNull CameraManager mgr, @NonNull String id, @NonNull String prefix) {
        try {
            CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            float[] focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean isLogical = caps != null && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
            FileLog.d(prefix + "TaroCamera: id=" + id
                    + " facing=" + lensFacingName(facing)
                    + " focal=" + Arrays.toString(focals)
                    + (isLogical ? " LOGICAL" : ""));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Set<String> phys = ch.getPhysicalCameraIds();
                if (phys != null && !phys.isEmpty()) {
                    FileLog.d(prefix + "  physical IDs = " + phys);
                    for (String pid : phys) {
                        try {
                            CameraCharacteristics pch = mgr.getCameraCharacteristics(pid);
                            float[] pf = pch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            Integer pfacing = pch.get(CameraCharacteristics.LENS_FACING);
                            FileLog.d(prefix + "    physical id=" + pid
                                    + " facing=" + lensFacingName(pfacing)
                                    + " focal=" + Arrays.toString(pf));
                        } catch (Throwable inner) {
                            FileLog.d(prefix + "    physical id=" + pid + " chars unavailable (" + inner.getMessage() + ")");
                        }
                    }
                }
            }
        } catch (CameraAccessException e) {
            FileLog.e(e);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static String lensFacingName(@Nullable Integer facing) {
        if (facing == null) return "?";
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK: return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "EXTERNAL";
            default: return "unknown(" + facing + ")";
        }
    }

    private static boolean contains(@NonNull int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    /**
     * Find the physical camera ID of the ultra-wide lens inside a logical
     * multi-camera device, or null if the logical camera doesn't expose a
     * wider physical lens than its own focal length.
     */
    @Nullable
    public static String findWidePhysicalId(@NonNull CameraManager mgr, @NonNull String logicalId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        try {
            CameraCharacteristics logical = mgr.getCameraCharacteristics(logicalId);
            Set<String> physIds = logical.getPhysicalCameraIds();
            if (physIds == null || physIds.isEmpty()) return null;
            float[] logicalFocals = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float refFocal = logicalFocals != null && logicalFocals.length > 0 ? logicalFocals[0] : Float.MAX_VALUE;
            String widest = null;
            float widestFocal = refFocal;
            for (String pid : physIds) {
                try {
                    CameraCharacteristics pch = mgr.getCameraCharacteristics(pid);
                    float[] pf = pch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (pf == null || pf.length == 0) continue;
                    float minF = Float.MAX_VALUE;
                    for (float f : pf) if (f > 0 && f < minF) minF = f;
                    if (minF < widestFocal) {
                        widestFocal = minF;
                        widest = pid;
                    }
                } catch (Throwable ignored) {
                }
            }
            return widest;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static boolean supportsLowLightBoost(CameraCharacteristics ch) {
        try {
            int[] modes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            if (modes == null) return false;
            for (int m : modes) {
                if (m == 5) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static <T> boolean trySet(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value) {
        try {
            b.set(key, value);
            return true;
        } catch (Throwable t) {
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                FileLog.d("TaroCameraEnhancements: rejected " + key.getName() + "=" + value);
            }
            return false;
        }
    }
}
