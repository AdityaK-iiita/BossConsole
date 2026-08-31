package ai.rever.boss.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * resolveFile is the control that stops project_replace rewriting a file outside the
 * project (e.g. ~/.zshrc). Its KDoc says so; these pin it, since a confinement check
 * that silently rots is worse than none.
 */
class ResolveFileConfinementTest {
    private val svc = ContentSearchService(projectPathProvider = { null })

    @Test
    fun `a relative path inside the project resolves`(
        @TempDir dir: File,
    ) {
        File(dir, "src").mkdirs()
        val f = File(dir, "src/A.kt").apply { writeText("x") }
        val resolved = svc.resolveFile("src/A.kt", dir.absolutePath)
        assertEquals(f.canonicalFile, resolved?.canonicalFile)
    }

    @Test
    fun `an absolute path outside the project is refused`(
        @TempDir dir: File,
        @TempDir other: File,
    ) {
        val outside = File(other, "secret.txt").apply { writeText("x") }
        assertNull(svc.resolveFile(outside.absolutePath, dir.absolutePath))
    }

    @Test
    fun `a dotdot traversal escaping the project is refused`(
        @TempDir dir: File,
    ) {
        val victim = File(dir.parentFile, "victim.txt").apply { writeText("x") }
        assertNull(svc.resolveFile("../${victim.name}", dir.absolutePath))
    }

    @Test
    fun `a sibling project sharing a name prefix is refused`(
        @TempDir parent: File,
    ) {
        // /parent/proj must not admit /parent/proj-other/x
        val proj = File(parent, "proj").apply { mkdirs() }
        val other = File(parent, "proj-other").apply { mkdirs() }
        val f = File(other, "x.kt").apply { writeText("x") }
        assertNull(svc.resolveFile(f.absolutePath, proj.absolutePath))
    }

    @Test
    fun `a symlink escaping the project is refused`(
        @TempDir dir: File,
        @TempDir other: File,
    ) {
        val target = File(other, "outside.txt").apply { writeText("x") }
        val link = File(dir, "link.txt")
        try {
            java.nio.file.Files
                .createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            return // filesystem without symlink support; skip
        }
        assertNull(svc.resolveFile("link.txt", dir.absolutePath))
    }
}
