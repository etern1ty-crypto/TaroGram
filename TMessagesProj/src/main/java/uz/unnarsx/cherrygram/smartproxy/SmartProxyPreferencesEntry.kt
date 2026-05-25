/*
 * TaroGram SmartProxy — settings UI.
 *
 * Modelled after Cherrygram's existing preferences (Java UItem / UniversalFragment),
 * but written in Kotlin so it can call straight into [SmartProxyManager] /
 * [SmartProxyConfig] without an extra Java bridge.
 */
package uz.unnarsx.cherrygram.smartproxy

import android.content.Context
import android.view.View
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import uz.unnarsx.cherrygram.diagnostics.DiagnosticExporter
import uz.unnarsx.cherrygram.preferences.helpers.SettingsHelper

class SmartProxyPreferencesEntry : UniversalFragment() {

    private val enabledRow = 1
    private val autoApplyRow = 2
    private val cloudFlareRow = 3
    private val cfAutoFallbackRow = 4
    private val portRow = 5
    private val watchdogRow = 6
    private val regenSecretRow = 7
    private val statusRow = 8
    private val diagnosticRow = 9

    override fun getTitle(): CharSequence = getString(R.string.TG_SmartProxy_Category)

    override fun createView(context: Context): View {
        setMD3(true)
        return super.createView(context)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(getString(R.string.TG_SmartProxy_Category)))

        items.add(
            SettingsHelper.asSwitchCG(
                enabledRow,
                getString(R.string.TG_SmartProxy_Enabled),
                getString(R.string.TG_SmartProxy_Enabled_Desc),
            ).setChecked(SmartProxyConfig.enabled),
        )

        items.add(
            SettingsHelper.asSwitchCG(
                autoApplyRow,
                getString(R.string.TG_SmartProxy_AutoApply),
                getString(R.string.TG_SmartProxy_AutoApply_Desc),
            ).setChecked(SmartProxyConfig.autoApplyToTelegram),
        )

        items.add(
            SettingsHelper.asSwitchCG(
                cloudFlareRow,
                getString(R.string.TG_SmartProxy_CloudFlare),
                getString(R.string.TG_SmartProxy_CloudFlare_Desc),
            ).setChecked(SmartProxyConfig.cloudFlareEnabled),
        )

        items.add(
            SettingsHelper.asSwitchCG(
                cfAutoFallbackRow,
                getString(R.string.TG_SmartProxy_CfAutoFallback),
                getString(R.string.TG_SmartProxy_CfAutoFallback_Desc),
            ).setChecked(SmartProxyConfig.cloudFlareAutoFallback),
        )

        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                portRow,
                getString(R.string.TG_SmartProxy_Port),
                SmartProxyConfig.port.toString(),
            ),
        )

        items.add(
            UItem.asButton(
                watchdogRow,
                getString(R.string.TG_SmartProxy_Watchdog),
                SmartProxyConfig.watchdogIntervalSec.toString(),
            ),
        )

        items.add(
            UItem.asButton(
                regenSecretRow,
                getString(R.string.TG_SmartProxy_RegenerateSecret),
            ),
        )

        items.add(UItem.asShadow(null))

        val statusText = if (SmartProxyManager.isRunning) {
            getString(R.string.TG_SmartProxy_Status_On)
        } else {
            getString(R.string.TG_SmartProxy_Status_Off)
        }
        items.add(
            SettingsHelper.asTextDetail(
                statusRow,
                0,
                getString(R.string.TG_SmartProxy_Title),
                statusText,
            ),
        )

        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                diagnosticRow,
                getString(R.string.TG_Diagnostic_Export),
                getString(R.string.TG_Diagnostic_Export_Desc),
            ),
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            enabledRow -> {
                val newValue = !SmartProxyConfig.enabled
                if (newValue) {
                    SmartProxyManager.start(context!!)
                } else {
                    SmartProxyManager.stop(context!!)
                }
                SettingsHelper.updateCheckState(view, newValue)
                showRestartHint()
                listView.adapter.update(true)
            }
            autoApplyRow -> {
                SmartProxyConfig.autoApplyToTelegram = !SmartProxyConfig.autoApplyToTelegram
                SettingsHelper.updateCheckState(view, SmartProxyConfig.autoApplyToTelegram)
                showRestartHint()
            }
            cloudFlareRow -> {
                SmartProxyConfig.cloudFlareEnabled = !SmartProxyConfig.cloudFlareEnabled
                SettingsHelper.updateCheckState(view, SmartProxyConfig.cloudFlareEnabled)
                if (SmartProxyManager.isRunning) showRestartHint()
            }
            cfAutoFallbackRow -> {
                SmartProxyConfig.cloudFlareAutoFallback = !SmartProxyConfig.cloudFlareAutoFallback
                SettingsHelper.updateCheckState(view, SmartProxyConfig.cloudFlareAutoFallback)
            }
            regenSecretRow -> {
                val newSecret = ByteArray(16).also { java.util.Random().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
                SmartProxyConfig.secret = newSecret
                if (SmartProxyManager.isRunning) showRestartHint()
                listView.adapter.update(true)
            }
            diagnosticRow -> {
                DiagnosticExporter.exportAndShare(context!!)
            }
            else -> {
                // Port and watchdog interval are read-only buttons for the first
                // iteration. Editing comes in a follow-up PR with a number
                // picker dialog.
            }
        }
    }

    override fun onLongClick(
        item: UItem,
        view: View,
        position: Int,
        x: Float,
        y: Float,
    ): Boolean = false

    private fun showRestartHint() {
        try {
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                getString(R.string.TG_SmartProxy_RestartHint),
            ).show()
        } catch (_: Throwable) { /* fragment not yet attached, ignore */ }
    }
}
