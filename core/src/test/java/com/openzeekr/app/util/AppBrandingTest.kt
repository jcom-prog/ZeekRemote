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

    @Test fun aPhotoDesignIsFoundLikeAnyOther() {
        // 0.3.3: the five photo designs; Velg comes before Oranje wiel in the enum, but only the
        // enabled alias counts.
        assertEquals(Design.WIEL, resolve(
            "LauncherDefault" to PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            "LauncherVelg" to PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            "LauncherWiel" to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        ))
        // A new alias the user never touched stays at its manifest default (disabled): it does not
        // take over from the design picked before the update.
        assertEquals(Design.SLEUTEL, resolve(
            "LauncherDefault" to PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            "LauncherSleutel" to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        ))
    }

    @Test fun nothingEnabledFallsBackToTheOriginalMark() {
        assertEquals(Design.DEFAULT, resolve(*Design.entries
            .map { it.alias to PackageManager.COMPONENT_ENABLED_STATE_DISABLED }.toTypedArray()))
    }

    @Test fun everyDesignHasItsOwnAliasAndStatusIcon() {
        assertEquals(Design.entries.size, Design.entries.map { it.alias }.toSet().size)
        // Silhouet/Foto and Velg/Oranje wiel look alike in one colour, but each design has its own resource.
        assertEquals(Design.entries.size, Design.entries.map { it.statusIcon }.toSet().size)
    }
}
