package ai.rever.boss.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [OsOpenArguments], which decides whether the `argv` BOSS launched
 * with is the operator's CLI or the OS asking it to open something.
 *
 * The bug behind it: `main.kt` only recognised args starting `boss://`,
 * `http://` or `https://`, so a file path (how Windows and Linux deliver a
 * double-clicked file) reached one of two dead ends and nothing opened. The
 * important rule here is the *other* direction - `boss file /tmp/x.md` must NOT
 * be extracted, or the file opens twice.
 */
class OsOpenArgumentsTest {
    /** Every path ending `.md` or `.kt` is a file, `/proj` is a directory, nothing else exists. */
    private val kindOf: (String) -> OsOpenArguments.OpenTargetKind = { path ->
        when {
            path.endsWith(".md") || path.endsWith(".kt") -> OsOpenArguments.OpenTargetKind.FILE
            path.trimEnd('/').endsWith("/proj") -> OsOpenArguments.OpenTargetKind.DIRECTORY
            else -> OsOpenArguments.OpenTargetKind.ABSENT
        }
    }

    private fun links(vararg args: String) = OsOpenArguments.deepLinksFrom(arrayOf(*args), kindOf)

    @Test
    fun `no args means nothing to open`() {
        assertTrue(OsOpenArguments.deepLinksFrom(emptyArray(), kindOf).isEmpty())
    }

    @Test
    fun `url scheme args pass through untouched`() {
        assertEquals(listOf("https://example.com/a"), links("https://example.com/a"))
        assertEquals(listOf("http://localhost:3000"), links("http://localhost:3000"))
        assertEquals(listOf("boss://terminal"), links("boss://terminal"))
    }

    @Test
    fun `an existing file becomes a boss file link`() {
        val result = links("/tmp/notes.md")
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("boss://file?path="), result.single())
        assertTrue(result.single().contains("notes.md"))
    }

    @Test
    fun `a path is absolutised, so a relative argument still resolves`() {
        // A shell can hand over `notes.md` with the working directory implied,
        // and the deep link is consumed elsewhere with no memory of that cwd.
        val link = links("notes.md").single()
        val path = link.substringAfter("boss://file?path=")
        assertTrue(path.startsWith("%2F") || path.startsWith("/"), "expected an absolute path, got $path")
    }

    @Test
    fun `a path that does not exist is not an open request`() {
        // Far more likely a mistyped flag or an argument this function does not
        // know about than a file worth opening.
        assertTrue(links("/tmp/gone.txt").isEmpty())
        assertTrue(links("something").isEmpty())
    }

    @Test
    fun `flags are never paths`() {
        assertTrue(links("--unregister-protocol").isEmpty())
        assertTrue(links("-n").isEmpty())
        // Checked before the filesystem: a file called "-h.md" in the working
        // directory must not turn a flag into an open request.
        assertTrue(links("-h.md").isEmpty())
    }

    @Test
    fun `a CLI invocation is left entirely to Clikt`() {
        // The whole point: extracting here as well would open the file twice,
        // once from the deep link and once from BossFileCommand.
        assertTrue(links("file", "/tmp/notes.md").isEmpty())
        assertTrue(links("url", "https://example.com").isEmpty())
        assertTrue(links("terminal", "-c", "ls").isEmpty())
        assertTrue(links("folder", "/tmp").isEmpty())
        assertTrue(links("workspace", "/tmp/notes.md").isEmpty())
        // A subcommand behind a flag is still the CLI.
        assertTrue(links("--verbose", "file", "/tmp/notes.md").isEmpty())
    }

    @Test
    fun `a multi-file selection produces one link each`() {
        val result = links("/tmp/a.md", "/tmp/b.kt")
        assertEquals(2, result.size)
        assertTrue(result.all { it.startsWith("boss://file?path=") })
    }

    @Test
    fun `mixed links and files all come through`() {
        val result = links("https://example.com", "/tmp/a.md", "/tmp/gone.txt")
        assertEquals(2, result.size)
        assertEquals("https://example.com", result.first())
    }

    @Test
    fun `a file URL from a Linux file manager becomes a file link`() {
        // `Exec=%U` in the desktop entry is what lets BOSS accept links AND
        // files, and file managers hand `%U` a file:// URL rather than a path.
        val result = links("file:///tmp/notes.md")
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("boss://file?path="))
        assertTrue(result.single().contains("notes.md"))
    }

    @Test
    fun `a percent-escaped file URL is decoded`() {
        val spaced: (String) -> OsOpenArguments.OpenTargetKind = { path ->
            if (path == "/tmp/my notes.md") {
                OsOpenArguments.OpenTargetKind.FILE
            } else {
                OsOpenArguments.OpenTargetKind.ABSENT
            }
        }
        val result = OsOpenArguments.deepLinksFrom(arrayOf("file:///tmp/my%20notes.md"), spaced)
        assertEquals(1, result.size)
        // Re-encoded for the deep link, so the space survives the round trip
        // rather than truncating the path.
        assertTrue(result.single().contains("my+notes.md") || result.single().contains("my%20notes.md"))
    }

    @Test
    fun `a localhost file URL is treated as local`() {
        // File(URI) rejects any authority component, so this used to fall into
        // the exception path and the file silently did not open.
        val result = links("file://localhost/tmp/notes.md")
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("boss://file?path="))
    }

    @Test
    fun `a file URL naming another host is refused`() {
        // A path on another machine. Dropping the host and opening the local path
        // of the same name would open the wrong file.
        assertTrue(
            OsOpenArguments
                .deepLinksFrom(arrayOf("file://fileserver/tmp/notes.md")) { OsOpenArguments.OpenTargetKind.FILE }
                .isEmpty(),
        )
    }

    @Test
    fun `a directory becomes a folder link`() {
        // `boss://folder` and `boss folder` both exist, so dropping a project
        // folder on the app should do something. The file-only predicate made it
        // silently do nothing.
        val result = links("/home/me/proj")
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("boss://folder?path="), result.single())
        assertTrue(result.single().contains("proj"))
    }

    @Test
    fun `a folder URL from a file manager becomes a folder link`() {
        assertTrue(links("file:///home/me/proj").single().startsWith("boss://folder?path="))
    }

    @Test
    fun `a subcommand name only counts as the first non-flag argument`() {
        // `args.any { it in CLI_SUBCOMMANDS }` matched at ANY position, so an OS
        // open request whose path happened to be exactly one of these names was
        // dropped as a CLI call. The KDoc always said "first non-flag argument".
        assertTrue(links("file", "/tmp/a.md").isEmpty(), "a real CLI call is still left to Clikt")
        assertTrue(links("--verbose", "url", "https://example.com").isEmpty())

        // ...but a path argument that merely equals a subcommand name is not one.
        val kindWithOddName: (String) -> OsOpenArguments.OpenTargetKind = { path ->
            if (path == "terminal") OsOpenArguments.OpenTargetKind.FILE else OsOpenArguments.OpenTargetKind.ABSENT
        }
        val result = OsOpenArguments.deepLinksFrom(arrayOf("/tmp/a.md", "terminal"), kindWithOddName)
        assertEquals(1, result.size, "the second arg names a real file and should open: $result")
    }

    @Test
    fun `the CLI subcommand list matches the CLI`() {
        // The failure mode when these drift is a double-open, which is easy to
        // miss and hard to attribute - so it is pinned against the source that
        // registers them.
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        val source = File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/cli/BossCommand.kt")
        assertTrue(source.isFile, "BossCommand.kt not found at ${source.absolutePath}")

        // The negative lookbehind excludes the root command, which is declared
        // as `NoOpCliktCommand(name = "boss")` and whose text contains
        // `CliktCommand(name = "boss")` as a substring. Without it the test
        // demands a "boss" subcommand that does not exist.
        val registered =
            Regex("""(?<!NoOp)CliktCommand\(name\s*=\s*"([a-z-]+)"\)""")
                .findAll(source.readText())
                .map { it.groupValues[1] }
                .toSet()
        assertTrue(registered.isNotEmpty(), "no subcommand names parsed out of BossCommand.kt")
        assertEquals(
            registered.sorted(),
            OsOpenArguments.CLI_SUBCOMMANDS.sorted(),
            "OsOpenArguments.CLI_SUBCOMMANDS must list every subcommand createBossCLI registers, " +
                "or an argument to that subcommand is opened twice",
        )
    }
}
