package ai.rever.boss.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the two decisions behind [HighQualityFaviconService] that decide whether a favourite
 * tile shows the right icon: which host gets asked about, and whether Google's answer is an
 * answer at all.
 *
 * Both are pure and cheap. The fetch itself is not covered here - it needs the network, and what
 * it does with a response is the placeholder check plus "can ImageIO decode it".
 */
class HighQualityFaviconServiceTest {
    @Test
    fun `host is extracted from an ordinary url`() {
        assertEquals("github.com", extractFaviconHost("https://github.com/rever/boss"))
        assertEquals("github.com", extractFaviconHost("http://www.github.com"))
        assertEquals(
            "mail.google.com",
            extractFaviconHost("https://mail.google.com/mail/u/0/#inbox"),
        )
    }

    /** Google keys on host alone and 404s on `localhost:3000`, so a dev server got no icon. */
    @Test
    fun `port is not part of the host`() {
        assertEquals("localhost", extractFaviconHost("http://localhost:3000/app"))
        assertEquals("dev.risalabs.ai", extractFaviconHost("https://dev.risalabs.ai:8443"))
    }

    @Test
    fun `credentials are not part of the host`() {
        assertEquals("example.com", extractFaviconHost("https://user:pw@example.com/x"))
    }

    /** An IPv6 literal's colons are inside the brackets and are not a port. */
    @Test
    fun `ipv6 literal survives port stripping`() {
        assertEquals("[::1]", extractFaviconHost("http://[::1]:8080/"))
    }

    /**
     * A non-http URL has no host Google can answer for. This used to yield `file:` and spend a
     * round trip being told so.
     */
    @Test
    fun `non-http urls have no host to ask about`() {
        assertNull(extractFaviconHost("file:///Users/someone/notes.md"))
        assertNull(extractFaviconHost("boss://plugin/editor"))
        assertNull(extractFaviconHost("/just/a/path"))
        assertNull(extractFaviconHost(""))
    }

    @Test
    fun `query and fragment are not part of the host`() {
        assertEquals(
            "google.com",
            extractFaviconHost("https://www.google.com?q=risa"),
        )
        assertEquals("example.com", extractFaviconHost("https://example.com#top"))
    }

    /**
     * Google answers "no favicon here" with HTTP 200 and a 16x16 globe, identical for every
     * unknown host. Caching that is what made unrelated hosts share one anonymous icon.
     *
     * The fixture is the real 726-byte payload, captured from the service; a fingerprint test
     * against a re-encoded copy would prove nothing about the bytes actually arriving.
     */
    @Test
    fun `google's no-icon placeholder is recognised`() {
        val placeholder =
            checkNotNull(javaClass.getResourceAsStream("/google-no-icon-placeholder.png")) {
                "fixture missing from the test resources"
            }.use { it.readBytes() }
        assertTrue(isNoIconPlaceholder(placeholder))
    }

    @Test
    fun `a real icon is not mistaken for the placeholder`() {
        assertFalse(isNoIconPlaceholder(ByteArray(726) { 0 }))
        assertFalse(isNoIconPlaceholder(ByteArray(0)))
    }
}
