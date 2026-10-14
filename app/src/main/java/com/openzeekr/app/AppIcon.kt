package com.openzeekr.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.openzeekr.app.util.AppBranding

/**
 * The launcher icon the user picked in Settings. Every option is an `<activity-alias>` of
 * [MainActivity] in the manifest and exactly one of them is enabled; [DEFAULT] is enabled in the
 * manifest itself, so a fresh install shows the familiar icon. The five car icons are generated from
 * the 7GT render by branding/icons/icons.py. The alias and the matching status-bar icon live in
 * [AppBranding.Design] (core), so notifications and the top-bar badge follow the choice (0.3.1).
 */
enum class AppIcon(val design: AppBranding.Design, val label: String, val iconRes: Int) {
    DEFAULT(AppBranding.Design.DEFAULT, "Huidig", R.mipmap.ic_launcher),
    SILHOUET(AppBranding.Design.SILHOUET, "Silhouet", R.mipmap.ic_launcher_silhouet),
    CLOSEUP(AppBranding.Design.CLOSEUP, "Close-up", R.mipmap.ic_launcher_closeup),
    LICHTLIJN(AppBranding.Design.LICHTLIJN, "Lichtlijn", R.mipmap.ic_launcher_lichtlijn),
    SLEUTEL(AppBranding.Design.SLEUTEL, "Digitale sleutel", R.mipmap.ic_launcher_sleutel),
    FOTO(AppBranding.Design.FOTO, "Foto", R.mipmap.ic_launcher_foto);

    val alias: String get() = design.alias

    /** Alias class names live in the code namespace, not the (".test"-suffixed) application id. */
    fun component(context: Context) =
        ComponentName(context.packageName, "${MainActivity::class.java.`package`!!.name}.$alias")

    companion object {
        fun current(context: Context): AppIcon = of(AppBranding.current(context))

        fun of(design: AppBranding.Design): AppIcon = entries.first { it.design == design }

        /**
         * Enables [target] first and only then disables the others, so the app never has zero launcher
         * entries. DONT_KILL_APP keeps the process - and with it the digital-key service - running.
         */
        fun select(context: Context, target: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(target.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != target }.forEach {
                pm.setComponentEnabledSetting(it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            AppBranding.onSelected(target.design)
        }
    }
}
