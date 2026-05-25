/*
 * TaroCamera Engine — settings + diagnostic screen.
 *
 * Three blocks:
 *   1. Diagnostic — per-lens probe result. Shows every camera ID the
 *      enumerator could touch, marks the hidden ones, and prints focal
 *      length / FoV so the user can see WHICH lens is which on their SKU.
 *      This is what unblocks per-device tuning: without this, "ultra-wide"
 *      is just an SKU-specific string ID; with this, the user can see
 *      "id=2  HIDDEN  ultra-wide  fov=118deg" and know to pick it.
 *   2. Lens selection — back / front. Defaults to auto.
 *   3. Quality knobs — stab / HDR / NR / bitrate / fps / resolution.
 */
package uz.unnarsx.cherrygram.tarocamera

import android.content.Context
import android.view.View
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper

class TaroCameraPreferencesEntry : UniversalFragment() {

    private val backLensRow = 100
    private val frontLensRow = 101
    private val refreshInventoryRow = 102

    private val stabilizationRow = 201
    private val hdrRow = 202
    private val resolutionRow = 203
    private val fpsRow = 204
    private val videoBitrateRow = 205
    private val audioBitrateRow = 206
    private val nrRow = 207
    private val edgeRow = 208

    private val LENS_ROW_BASE = 1000
    private val lensRowToId = mutableMapOf<Int, String>()

    private var inventory: TaroCameraInventory? = null

    override fun getTitle(): CharSequence = getString(R.string.TG_TaroCamera_Title)

    override fun createView(context: Context): View {
        setMD3(true)
        inventory = TaroCameraEnumerator.enumerate(context)
        return super.createView(context)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(getString(R.string.TG_TaroCamera_Diag_Header)))

        val inv = inventory
        if (inv == null) {
            items.add(SettingsHelper.asTextDetail(0, 0, getString(R.string.TG_TaroCamera_Diag_Empty), ""))
        } else {
            // Diagnostic rows — one per lens.
            lensRowToId.clear()
            inv.lenses.forEachIndexed { idx, lens ->
                val rowId = LENS_ROW_BASE + idx
                lensRowToId[rowId] = lens.id
                val title = "id=${lens.id}  ${facingShort(lens.facing)}  ${lens.guessRole()}"
                val tags = mutableListOf<String>()
                if (lens.hiddenFromCameraIdList) tags += "HIDDEN"
                if (lens.isLogicalMultiCamera) tags += "LOGICAL"
                if (lens.physicalIds.isNotEmpty()) tags += "phys=${lens.physicalIds}"
                if (lens.supportsVideoStab) tags += "stab"
                if (lens.supportsOpticalStab) tags += "OIS"
                if (lens.supportsHdr) tags += "HDR"
                val subtitle = buildString {
                    append("focal=${"%.2f".format(lens.focalLength)}mm  ")
                    append("fov=${"%.0f".format(lens.approxFovDeg)}\u00B0")
                    if (tags.isNotEmpty()) {
                        append("\n")
                        append(tags.joinToString("  ·  "))
                    }
                }
                items.add(SettingsHelper.asTextDetail(rowId, 0, title, subtitle))
            }
            items.add(
                UItem.asButton(
                    refreshInventoryRow,
                    getString(R.string.TG_TaroCamera_Diag_Refresh),
                    "${inv.lenses.size} lenses  ·  ${inv.lenses.count { it.hiddenFromCameraIdList }} hidden",
                ),
            )
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(getString(R.string.TG_TaroCamera_LensHeader)))
        items.add(
            UItem.asButton(
                backLensRow,
                getString(R.string.TG_TaroCamera_BackLens),
                describeChosenLens(TaroCameraConfig.backLensId, back = true),
            ),
        )
        items.add(
            UItem.asButton(
                frontLensRow,
                getString(R.string.TG_TaroCamera_FrontLens),
                describeChosenLens(TaroCameraConfig.frontLensId, back = false),
            ),
        )

        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(getString(R.string.TG_TaroCamera_QualityHeader)))
        items.add(
            UItem.asButton(
                stabilizationRow,
                getString(R.string.TG_TaroCamera_Stab),
                TaroCameraConfig.stabilization.uppercase(),
            ),
        )
        items.add(
            UItem.asButton(hdrRow, getString(R.string.TG_TaroCamera_Hdr), TaroCameraConfig.hdr.uppercase()),
        )
        items.add(
            UItem.asButton(
                resolutionRow,
                getString(R.string.TG_TaroCamera_Resolution),
                TaroCameraConfig.resolution.uppercase(),
            ),
        )
        items.add(
            UItem.asButton(fpsRow, getString(R.string.TG_TaroCamera_Fps), "${TaroCameraConfig.fps}"),
        )
        items.add(
            UItem.asButton(
                videoBitrateRow,
                getString(R.string.TG_TaroCamera_VideoBitrate),
                "${TaroCameraConfig.videoBitrateKbps} kbps",
            ),
        )
        items.add(
            UItem.asButton(
                audioBitrateRow,
                getString(R.string.TG_TaroCamera_AudioBitrate),
                "${TaroCameraConfig.audioBitrateKbps} kbps",
            ),
        )
        items.add(
            UItem.asButton(
                nrRow,
                getString(R.string.TG_TaroCamera_NoiseReduction),
                TaroCameraConfig.noiseReduction.uppercase(),
            ),
        )
        items.add(
            UItem.asButton(
                edgeRow,
                getString(R.string.TG_TaroCamera_Edge),
                TaroCameraConfig.edgeEnhancement.uppercase(),
            ),
        )
        items.add(UItem.asShadow(getString(R.string.TG_TaroCamera_QualityFooter)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            refreshInventoryRow -> {
                inventory = TaroCameraEnumerator.enumerate(context!!)
                listView.adapter.update(true)
            }
            backLensRow -> pickLens(back = true)
            frontLensRow -> pickLens(back = false)
            stabilizationRow -> pickEnum(
                getString(R.string.TG_TaroCamera_Stab),
                listOf("off", "video", "preview", "ois"),
                TaroCameraConfig.stabilization,
            ) { TaroCameraConfig.stabilization = it; listView.adapter.update(true) }
            hdrRow -> pickEnum(
                getString(R.string.TG_TaroCamera_Hdr),
                listOf("off", "auto", "on"),
                TaroCameraConfig.hdr,
            ) { TaroCameraConfig.hdr = it; listView.adapter.update(true) }
            resolutionRow -> pickEnum(
                getString(R.string.TG_TaroCamera_Resolution),
                listOf("auto", "480", "720", "1080", "2k", "4k"),
                TaroCameraConfig.resolution,
            ) { TaroCameraConfig.resolution = it; listView.adapter.update(true) }
            fpsRow -> pickInt(
                getString(R.string.TG_TaroCamera_Fps),
                listOf(24, 30, 60),
                TaroCameraConfig.fps,
            ) { TaroCameraConfig.fps = it; listView.adapter.update(true) }
            videoBitrateRow -> pickInt(
                getString(R.string.TG_TaroCamera_VideoBitrate),
                listOf(1024, 2048, 4096, 8192, 16384, 32768),
                TaroCameraConfig.videoBitrateKbps,
            ) { TaroCameraConfig.videoBitrateKbps = it; listView.adapter.update(true) }
            audioBitrateRow -> pickInt(
                getString(R.string.TG_TaroCamera_AudioBitrate),
                listOf(48, 64, 96, 128, 192, 256),
                TaroCameraConfig.audioBitrateKbps,
            ) { TaroCameraConfig.audioBitrateKbps = it; listView.adapter.update(true) }
            nrRow -> pickEnum(
                getString(R.string.TG_TaroCamera_NoiseReduction),
                listOf("off", "fast", "high_quality"),
                TaroCameraConfig.noiseReduction,
            ) { TaroCameraConfig.noiseReduction = it; listView.adapter.update(true) }
            edgeRow -> pickEnum(
                getString(R.string.TG_TaroCamera_Edge),
                listOf("off", "fast", "high_quality"),
                TaroCameraConfig.edgeEnhancement,
            ) { TaroCameraConfig.edgeEnhancement = it; listView.adapter.update(true) }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    private fun pickLens(back: Boolean) {
        val inv = inventory ?: return
        val lenses = if (back) inv.back() else inv.front()
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()
        labels += "Auto"
        ids += ""
        for (l in lenses) {
            labels += "id=${l.id}  ${l.guessRole()}  focal=${"%.2f".format(l.focalLength)}mm  fov=${"%.0f".format(l.approxFovDeg)}\u00B0" +
                if (l.hiddenFromCameraIdList) "  (HIDDEN)" else ""
            ids += l.id
        }
        val activity = parentActivity ?: return
        AlertDialog.Builder(activity, resourceProvider)
            .setTitle(getString(if (back) R.string.TG_TaroCamera_BackLens else R.string.TG_TaroCamera_FrontLens) as CharSequence)
            .setItems(labels.toTypedArray()) { _, which ->
                if (back) TaroCameraConfig.backLensId = ids[which]
                else TaroCameraConfig.frontLensId = ids[which]
                listView.adapter.update(true)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun pickEnum(title: CharSequence, values: List<String>, current: String, onPick: (String) -> Unit) {
        val activity = parentActivity ?: return
        AlertDialog.Builder(activity, resourceProvider)
            .setTitle(title as CharSequence)
            .setItems(values.map { it.uppercase() + if (it == current) "  ✓" else "" }.toTypedArray()) { _, which ->
                onPick(values[which])
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun pickInt(title: CharSequence, values: List<Int>, current: Int, onPick: (Int) -> Unit) {
        val activity = parentActivity ?: return
        AlertDialog.Builder(activity, resourceProvider)
            .setTitle(title as CharSequence)
            .setItems(values.map { it.toString() + if (it == current) "  ✓" else "" }.toTypedArray()) { _, which ->
                onPick(values[which])
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun describeChosenLens(id: String, back: Boolean): String {
        if (id.isEmpty()) return "Auto"
        val l = inventory?.byId(id) ?: return "id=$id (not detected)"
        return "id=${l.id}  ${l.guessRole()}  ${"%.0f".format(l.approxFovDeg)}\u00B0"
    }

    private fun facingShort(f: Int): String = when (f) {
        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "back"
        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "front"
        else -> "?"
    }
}
