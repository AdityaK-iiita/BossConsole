package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the store itself rather than the pure helpers around it: the gate on what gets
 * recorded, how one page recorded under two spellings collapses, and the ordering the URL
 * bar actually sees.
 *
 * [UrlHistoryManager.historyFile] is pointed at a scratch file so this exercises the real
 * read/write path without reading or writing the developer's own history.
 */
class UrlHistoryManagerTest {
    private lateinit var tempFile: File
    private lateinit var originalFile: File

    @BeforeTest
    fun useScratchFile() {
        originalFile = UrlHistoryManager.historyFile
        tempFile = File.createTempFile("browser-history", ".json").also { it.delete() }
        UrlHistoryManager.historyFile = tempFile
        UrlHistoryManager.loadHistory()
        NavigationOutcomeTracker.clear()
    }

    @AfterTest
    fun restore() {
        // Drain first. A background write still in flight when the pointer moves back is
        // how an earlier version of this test wrote an empty history over a real one —
        // the store now binds its target when the write is requested, and waiting here
        // means the test can't depend on that being true.
        runBlocking { UrlHistoryManager.awaitPendingWrites() }
        tempFile.delete()
        UrlHistoryManager.historyFile = originalFile
        UrlHistoryManager.loadHistory()
        NavigationOutcomeTracker.clear()
    }

    @Test
    fun `two spellings of one page are a single suggestion straight away`() {
        // Previously these occupied two slots until a restart merged them.
        UrlHistoryManager.addUrl("https://x.com", "X")
        UrlHistoryManager.addUrl("https://www.x.com/", "X")

        val suggestions = UrlHistoryManager.getSuggestions("x.com")

        assertEquals(1, suggestions.size)
        assertEquals(2, suggestions.single().visitCount)
    }

    @Test
    fun `the https spelling is the one we keep`() {
        UrlHistoryManager.addUrl("http://x.com/app", "App")
        UrlHistoryManager.addUrl("https://x.com/app", "App")

        assertEquals("https://x.com/app", UrlHistoryManager.getSuggestions("x.com").single().url)
    }

    @Test
    fun `a prefix of the host ranks above a match buried in a title`() {
        // The `www.` in a stored domain used to keep the obvious answer out of the
        // domain-prefix bucket, so typing "you" ranked an unrelated page first.
        UrlHistoryManager.addUrl("https://example.com/watch", "Youtube tips and tricks")
        UrlHistoryManager.addUrl("https://www.youtube.com/", "YouTube")

        assertEquals("https://www.youtube.com/", UrlHistoryManager.getSuggestions("you").first().url)
    }

    @Test
    fun `a host and port are kept apart`() {
        UrlHistoryManager.addUrl("http://localhost:3000/", "Dev")
        UrlHistoryManager.addUrl("http://localhost:8080/", "Docs")

        val domains = UrlHistoryManager.getSuggestions("localhost").map { it.domain }.toSet()

        assertEquals(setOf("localhost:3000", "localhost:8080"), domains)
    }

    @Test
    fun `visits outrank recency`() {
        // The old score was recency in all but name: a count needed to pass ~1750 before
        // it moved anything.
        UrlHistoryManager.addUrl("https://daily.example/", "Daily")
        repeat(4) { UrlHistoryManager.addUrl("https://daily.example/", "Daily") }
        UrlHistoryManager.addUrl("https://once.example/", "Once")

        val ranked = UrlHistoryManager.getSuggestions("example")

        assertEquals("https://daily.example/", ranked.first().url)
    }

    @Test
    fun `an address that failed to load is never recorded`() {
        NavigationOutcomeTracker.recordFailure("https://youtube.como/")

        UrlHistoryManager.addUrl("https://youtube.como/", "youtube.como")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("youtube.como"))
    }

    @Test
    fun `addresses with no host to suggest are turned away`() {
        UrlHistoryManager.addUrl("about:blank", "New tab")
        UrlHistoryManager.addUrl("file:///Users/me/notes.html", "Notes")
        UrlHistoryManager.addUrl("data:text/plain,hi", "hi")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("notes"))
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("blank"))
    }

    @Test
    fun `a deletion survives a reload`() {
        UrlHistoryManager.addUrl("https://gone.example/", "Gone")
        runBlocking { UrlHistoryManager.saveHistory() }

        UrlHistoryManager.deleteUrl("https://gone.example/")
        runBlocking { UrlHistoryManager.awaitPendingWrites() }
        UrlHistoryManager.loadHistory()

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("gone.example"))
    }

    @Test
    fun `a deletion finds the entry under any spelling of it`() {
        UrlHistoryManager.addUrl("https://www.spelling.example/", "Spelling")

        UrlHistoryManager.deleteUrl("spelling.example")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("spelling"))
    }

    @Test
    fun `history written before shared host normalization is repaired on load`() {
        tempFile.writeText(
            """
            [
              {
                "url": "https://www.legacy.example/",
                "title": "Legacy",
                "domain": "www.legacy.example",
                "visitCount": 3,
                "lastVisited": 1
              }
            ]
            """.trimIndent(),
        )

        UrlHistoryManager.loadHistory()

        assertEquals("legacy.example", UrlHistoryManager.getSuggestions("legacy").single().domain)
    }

    @Test
    fun `an empty store answers nothing rather than everything`() {
        UrlHistoryManager.addUrl("https://example.com/", "Example")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions(""))
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("   "))
    }

    @Test
    fun `saving keeps the file parseable and the entries intact`() {
        UrlHistoryManager.addUrl("https://persisted.example/page", "Persisted")
        runBlocking { UrlHistoryManager.saveHistory() }

        assertTrue(tempFile.exists())
        UrlHistoryManager.loadHistory()

        val reloaded = UrlHistoryManager.getSuggestions("persisted").single()
        assertEquals("https://persisted.example/page", reloaded.url)
        assertEquals("Persisted", reloaded.title)
        assertNull(UrlHistoryManager.getSuggestions("nothing-here").firstOrNull())
    }
}
