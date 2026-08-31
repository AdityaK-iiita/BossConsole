package ai.rever.boss.components.window_panel

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Path comparison for tab reuse.
 *
 * Clicking a file that is already open must focus that tab. It opened a second
 * one instead because the check was raw string equality and the two callers
 * spell the same path differently.
 */
class TabPathsTest {
    @Test
    fun `a doubled separator compares equal to a single one`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "a.kt").also { it.writeText("x") }
        // What the git provider produced from a project path ending in "/".
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}//a.kt"),
        )
    }

    @Test
    fun `a dot segment resolves to the same file`(
        @TempDir dir: File,
    ) {
        File(dir, "sub").mkdirs()
        val file = File(dir, "sub/a.kt").also { it.writeText("x") }
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}/sub/./a.kt"),
        )
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}/sub/../sub/a.kt"),
        )
    }

    @Test
    fun `a trailing separator does not change the identity`(
        @TempDir dir: File,
    ) {
        assertEquals(TabPaths.normalize(dir.absolutePath), TabPaths.normalize("${dir.absolutePath}/"))
    }

    @Test
    fun `different files stay different`(
        @TempDir dir: File,
    ) {
        File(dir, "a.kt").writeText("x")
        File(dir, "b.kt").writeText("y")
        assertNotEquals(
            TabPaths.normalize("${dir.absolutePath}/a.kt"),
            TabPaths.normalize("${dir.absolutePath}/b.kt"),
        )
    }

    @Test
    fun `an unresolvable path still compares consistently with itself`() {
        // The file need not exist - a tab can outlive its file.
        assertEquals(
            TabPaths.normalize("/nope/does/not/exist.kt"),
            TabPaths.normalize("/nope/does//not/exist.kt"),
        )
    }

    @Test
    fun `a blank path is blank, not the working directory`() {
        assertEquals("", TabPaths.normalize(""))
        assertEquals("", TabPaths.normalize("   "))
    }
}
