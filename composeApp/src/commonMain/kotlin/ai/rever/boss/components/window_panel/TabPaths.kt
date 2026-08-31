package ai.rever.boss.components.window_panel

import java.io.File

/**
 * Path comparison for "is this file already open in a tab?".
 *
 * The reuse check was a raw string equality on the tab's stored path, so the
 * same file opened from two places produced two tabs whenever the two callers
 * spelled the path differently. They do: the file tree passes the path the
 * host's scanner produced, while the git provider builds `"$projectPath/$rel"`
 * - which doubles the separator whenever the project path ends in one, and
 * never resolves `.`/`..` or a symlinked project root.
 */
internal object TabPaths {
    /**
     * A canonical form for comparison only - never for display or for opening.
     *
     * Falls back to a lexical cleanup when the file cannot be resolved (it may
     * not exist yet, or be unreadable), so an unresolvable path still compares
     * consistently with itself.
     */
    fun normalize(path: String): String {
        if (path.isBlank()) return ""
        val lexical = lexicalClean(path)
        return try {
            File(lexical).canonicalPath
        } catch (e: Exception) {
            lexical
        }
    }

    /** Collapse repeated separators and drop a trailing one. */
    private fun lexicalClean(path: String): String {
        // Backslash is a path separator on Windows but a LEGAL filename character on
        // macOS/Linux, so translating it unconditionally turned `a\b.kt` into `a/b.kt`
        // and could canonicalise onto a different real file (focusing the wrong tab).
        val unified = if (File.separatorChar == '\\') path.replace('\\', '/') else path
        val collapsed = unified.replace(Regex("/{2,}"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }
}
