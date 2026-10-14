package com.openzeekr.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon the user picked in Settings. Every option is an `<activity-alias>` of
 * [MainActivity] in the manifest and exactly one of them is enabled; [DEFAULT] is enabled in the
 * manifest itself, so a fresh install shows the familiar icon. The five car icons are generated from
 * the 7GT render by branding/icons/icons.py.
 */
enum class AppIcon(val alias: String, val label: String, val iconRes: Int) {
    DEFAULT("LauncherDefault", "Huidig", R.mipmap.ic_launcher),
    SILHOUET("LauncherSilhouet", "Silhouet", R.mipmap.ic_launcher_silhouet),
    CLOSEUP("LauncherCloseup", "Close-up", R.mipmap.ic_launcher_closeup),
    LICHTLIJN("LauncherLichtlijn", "Lichtlijn", R.mipmap.ic_launcher_lichtlijn),
    SLEUTEL("LauncherSleutel", "Digitale sleutel", R.mipmap.ic_launcher_sleutel),
    FOTO("LauncherFoto", "Foto", R.mipmap.ic_launcher_foto);

    /** Alias class names live in the code namespace, not the (".test"-suffixed) application id. */
    fun component(context: Context) =
        ComponentName(context.packageName, "${MainActivity::class.java.`package`!!.name}.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == DEFAULT  // manifest default
                    else -> false
                }
            } ?: DEFAULT
        }

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
        }
    }
}
