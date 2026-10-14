package com.openzeekr.app.util

import android.content.pm.PackageManager
import com.openzeekr.app.util.AppBranding.Design
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBrandingTest {
    private fun resolve(vararg enabled: Pair<String, Int>) =
        AppBranding.resolve { alias -> enabled.toMap()[alias] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }

    @Test fun aFreshInstallShowsTheOriginalMark() {
        assertEquals(Design.DEFAULT, resolve())
    }

    @Test fun theEnabledAliasDecidesTheDesign() {
        assertEquals(Design.SLEUTEL, resolve(
            "LauncherDefault" to PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            "LauncherSleutel" to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        ))
        // Back to the original mark after another design was picked.
        assertEquals(Design.DEFAULT, resolve(
            "LauncherDefault" to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            "LauncherFoto" to PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        ))
    }

    @Test fun nothingEnabledFallsBackToTheOriginalMark() {
        assertEquals(Design.DEFAULT, resolve(*Design.entries
            .map { it.alias to PackageManager.COMPONENT_ENABLED_STATE_DISABLED }.toTypedArray()))
    }

    @Test fun everyDesignHasItsOwnAliasAndStatusIcon() {
        assertEquals(Design.entries.size, Design.entries.map { it.alias }.toSet().size)
        // Silhouet and Foto look alike in one colour, but each design has its own resource.
        assertEquals(Design.entries.size, Design.entries.map { it.statusIcon }.toSet().size)
    }
}
