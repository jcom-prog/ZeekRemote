package com.openzeekr.app.util

/**
 * The top-bar title: the car's name followed by the installed app version, so the version shown
 * always matches what is installed (user 03/10: it was typed into the car name by hand and went
 * stale with every install). A version the user typed at the end of the name is dropped from the
 * display; renaming edits only the name.
 */
object HeaderTitle {
    // Only a separate trailing token that looks like an app version (x.y.z, optional v / -suffix);
    // "Model3.1", "Car 2024.10" or "Zeekr 7X 2.0" are names, not versions (review 0.1.62).
    private val trailingVersion = Regex("""(^|\s+)[vV]?\d+\.\d+\.\d+(\.\d+)?([-_][A-Za-z0-9.]+)?\s*$""")
    private const val DEFAULT_NAME = "My Zeekr"

    /** The name without a hand-typed trailing version (blank -> the default name). */
    fun baseName(nickname: String): String {
        var name = nickname.trim()
        while (true) {
            val stripped = name.replace(trailingVersion, "").trim()
            if (stripped == name) break
            name = stripped
        }
        return name.ifBlank { DEFAULT_NAME }
    }

    /** The installed version as shown: the build suffix ("-work") is left out. */
    fun shortVersion(versionName: String?): String = versionName.orEmpty().substringBefore('-').trim()

    fun title(nickname: String, versionName: String?): String {
        val v = shortVersion(versionName)
        return if (v.isEmpty()) baseName(nickname) else "${baseName(nickname)} $v"
    }
}
