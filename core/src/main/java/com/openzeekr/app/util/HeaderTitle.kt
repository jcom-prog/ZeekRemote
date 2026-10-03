package com.openzeekr.app.util

/**
 * The top-bar title: the car's name followed by the installed app version, so the version shown
 * always matches what is installed (user 03/10: it was typed into the car name by hand and went
 * stale with every install). A version the user typed at the end of the name is dropped from the
 * display; renaming edits only the name.
 */
object HeaderTitle {
    private val trailingVersion = Regex("""\s*v?\d+(\.\d+){1,3}(-[A-Za-z0-9.]+)?\s*$""")
    private const val DEFAULT_NAME = "My Zeekr"

    /** The name without a hand-typed trailing version (blank -> the default name). */
    fun baseName(nickname: String): String = nickname.replace(trailingVersion, "").trim().ifBlank { DEFAULT_NAME }

    /** The installed version as shown: the build suffix ("-work") is left out. */
    fun shortVersion(versionName: String?): String = versionName.orEmpty().substringBefore('-').trim()

    fun title(nickname: String, versionName: String?): String {
        val v = shortVersion(versionName)
        return if (v.isEmpty()) baseName(nickname) else "${baseName(nickname)} $v"
    }
}
