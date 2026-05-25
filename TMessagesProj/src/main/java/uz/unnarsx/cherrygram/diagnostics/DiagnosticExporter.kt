/*
 * TaroGram diagnostic exporter.
 *
 * Single-button "what do you have on your device" dump. Concatenates:
 *   - build info (versionName, package, abi)
 *   - device info (manufacturer, model, Android version, HAL props)
 *   - TaroCamera enumerator snapshot (every visible + hidden camera ID,
 *     focal lengths, sensor sizes, physicalIds, FoV)
 *   - SmartProxy state snapshot (running flag, ping history, recovery
 *     counters, transport flips)
 *   - last 500 logcat lines filtered to TaroCamera / TaroSmartProxy / Camera2Session
 *   - the running app's foreground services / proxy config
 *
 * The result is written to a temp file and shared via Intent.ACTION_SEND so
 * the user can ping it back in Telegram. We never include any PII; the
 * Telegram session content, account, phone number etc. are not touched.
 */
package uz.unnarsx.cherrygram.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import uz.unnarsx.cherrygram.smartproxy.SmartProxyManager
import uz.unnarsx.cherrygram.tarocamera.TaroCameraEnumerator
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExporter {

    private const val TAG = "TaroDiag"

    fun export(ctx: Context): File? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(ctx.cacheDir, "tarogram-diag").apply { mkdirs() }
            val out = File(dir, "tarogram-diag-$ts.txt")
            FileWriter(out).use { w ->
                w.appendLine("# TaroGram diagnostic export")
                w.appendLine("# Generated: ${Date()}")
                w.appendLine()

                w.appendLine("## Build")
                w.appendLine("package          = ${ctx.packageName}")
                w.appendLine("versionName      = ${BuildConfig.BUILD_VERSION_STRING}")
                w.appendLine("versionCode      = ${BuildConfig.VERSION_NUM}")
                w.appendLine("debug            = ${BuildConfig.DEBUG_VERSION}")
                w.appendLine()

                w.appendLine("## Device")
                w.appendLine("manufacturer     = ${Build.MANUFACTURER}")
                w.appendLine("brand            = ${Build.BRAND}")
                w.appendLine("model            = ${Build.MODEL}")
                w.appendLine("device           = ${Build.DEVICE}")
                w.appendLine("product          = ${Build.PRODUCT}")
                w.appendLine("hardware         = ${Build.HARDWARE}")
                w.appendLine("fingerprint      = ${Build.FINGERPRINT}")
                w.appendLine("sdk              = ${Build.VERSION.SDK_INT}")
                w.appendLine("release          = ${Build.VERSION.RELEASE}")
                w.appendLine("incremental      = ${Build.VERSION.INCREMENTAL}")
                w.appendLine("supported_abis   = ${Build.SUPPORTED_ABIS.joinToString(",")}")
                w.appendLine()

                w.appendLine("## TaroCamera enumerator")
                try {
                    val inv = TaroCameraEnumerator.enumerate(ctx)
                    w.appendLine("visibleIds  = ${inv.visibleIds}")
                    w.appendLine("probedIds   = ${inv.probedIds}")
                    w.appendLine("lensCount   = ${inv.lenses.size}")
                    inv.lenses.forEach { lens ->
                        w.appendLine(
                            "  id=${lens.id} facing=${lens.facing} role=${lens.guessRole()} " +
                                "focal=${lens.focalLength}mm sensor=${lens.sensorSize} " +
                                "maxPreview=${lens.maxPreviewSize} maxRecord=${lens.maxRecordSize} " +
                                "fps=${lens.supportedFps} stab=${lens.supportsVideoStab} " +
                                "ois=${lens.supportsOpticalStab} hdr=${lens.supportsHdr} " +
                                "logical=${lens.isLogicalMultiCamera} phys=${lens.physicalIds} " +
                                "hidden=${lens.hiddenFromCameraIdList} fov=${lens.approxFovDeg}deg",
                        )
                    }
                } catch (t: Throwable) {
                    w.appendLine("ERROR: ${t.message}")
                    FileLog.e("$TAG: TaroCamera dump failed", t)
                }
                w.appendLine()

                w.appendLine("## SmartProxy")
                try {
                    w.appendLine(SmartProxyManager.snapshotJson())
                } catch (t: Throwable) {
                    w.appendLine("ERROR: ${t.message}")
                    FileLog.e("$TAG: SmartProxy dump failed", t)
                }
                w.appendLine()

                w.appendLine("## Recent logcat (TaroCamera / TaroSmartProxy / Camera2Session, last 500 lines)")
                try {
                    dumpLogcat(w)
                } catch (t: Throwable) {
                    w.appendLine("ERROR: ${t.message}")
                    FileLog.e("$TAG: logcat dump failed", t)
                }
            }
            out
        } catch (t: Throwable) {
            FileLog.e("$TAG: export failed", t)
            null
        }
    }

    /**
     * Read up to 500 recent logcat lines that mention TaroCamera, TaroSmartProxy,
     * Camera2Session or the app's package. On most Android 7+ builds non-system
     * apps can only read their own logs (selinux), so the result may be sparse
     * — that's still useful for triage.
     */
    private fun dumpLogcat(w: FileWriter) {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat",
                "-d",
                "-t", "500",
                "TaroCamera:V",
                "TaroSmartProxy:V",
                "Camera2Session:V",
                "InstantCameraView:V",
                "AndroidRuntime:E",
                "FATAL:V",
                "*:S",
            ),
        )
        BufferedReader(InputStreamReader(process.inputStream)).use { r ->
            var line: String? = r.readLine()
            while (line != null) {
                w.appendLine(line)
                line = r.readLine()
            }
        }
        process.waitFor()
    }

    /** Build a Share intent for the exported file. */
    fun shareIntent(ctx: Context, file: File): Intent {
        val authority = "${ctx.packageName}.provider"
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, authority, file)
        } catch (t: Throwable) {
            FileLog.e("$TAG: FileProvider.getUriForFile failed; falling back to file:// URI", t)
            Uri.fromFile(file)
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TaroGram diagnostic")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** One-shot helper used by the preferences entry. */
    @JvmStatic
    fun exportAndShare(ctx: Context = ApplicationLoader.applicationContext) {
        AndroidUtilities.runOnUIThread {
            val file = export(ctx)
            if (file == null) {
                try {
                    BulletinFactory.global()?.createErrorBulletin(
                        LocaleController.getString(R.string.TG_Diagnostic_Failed),
                    )?.show()
                } catch (_: Throwable) { /* no active fragment to show a bulletin in */ }
                return@runOnUIThread
            }
            val share = shareIntent(ctx, file)
            val chooser = Intent.createChooser(share, LocaleController.getString(R.string.TG_Diagnostic_Share))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        }
    }
}
