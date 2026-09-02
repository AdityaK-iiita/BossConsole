package ai.rever.boss.cache

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The host [HighQualityFaviconService] should ask Google about, or null when there is nothing to
 * ask.
 *
 * Only an http(s) page has a host Google can answer for. Everything else - `file:///…`,
 * `boss://`, `mailto:`, a bare path - used to reach the fetch as a "domain" of whatever sat
 * before the first slash (`file:` for a local file), which spends a network round trip to be told
 * no.
 *
 * Three things beyond the scheme are stripped, and two of them matter beyond tidiness:
 *
 * - **Credentials.** `https://user:pw@example.com/x` yielded `user:pw@example.com`, which went
 *   into the `domain=` query parameter of a request to `www.google.com` and was then MD5'd into a
 *   cache filename. A password does not belong in either place.
 * - **The fragment.** `https://example.com#top` was sent verbatim for the same reason.
 * - **The port.** Google's service keys on host alone and has nothing for `localhost:3000`, so a
 *   dev server got no icon at all while plain `localhost` would have answered.
 */
internal object FaviconHost {
    /**
     * A scheme-less `example.com/x` or `localhost:3000/app`.
     *
     * These do reach here: `NetscapeBookmarkParser` passes an export's `HREF` through verbatim
     * and nothing on the import path normalises a scheme onto it, so a hand-written or
     * third-party bookmark file can carry one. The old extraction resolved them by accident of
     * stripping a prefix that was not there.
     *
     * Deliberately narrow - a dotted name or `localhost`, an optional numeric port, then a `/`,
     * `?`, `#` or the end of the string. That rejects a path (`/Users/x`), an opaque scheme
     * (`mailto:`, `javascript:`, `about:blank`, `data:image/png;…`) and anything with a space in
     * it, none of which have a host to ask about, and all of which the old extraction sent.
     */
    private val BARE_AUTHORITY =
        Regex(
            """^(?:localhost|(?:[\w-]+\.)+[a-z]{2,})(?::\d+)?(?![^/?#])""",
            RegexOption.IGNORE_CASE,
        )

    fun of(url: String?): String? {
        val authority = authorityOf(url?.trim().orEmpty()) ?: return null
        return authority
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            // Credentials, if any, sit before the host and are not part of it.
            .substringAfterLast('@')
            .let(::stripPort)
            .removeSuffix(".")
            .lowercase()
            .removePrefix("www.")
            .ifBlank { null }
    }

    /**
     * The `host[:port][/path…]` part of [url], or null when [url] names no host.
     *
     * A leading `//` is scheme-relative and inherits the page's scheme, which for anything that
     * became a bookmark was http(s).
     */
    private fun authorityOf(url: String): String? =
        when {
            url.startsWith("//") -> url.removePrefix("//")
            url.contains("://") -> url.substringAfter("://").takeIf { isHttp(url.substringBefore("://")) }
            else -> BARE_AUTHORITY.find(url)?.value
        }

    private fun isHttp(scheme: String): Boolean = scheme.equals("http", true) || scheme.equals("https", true)

    /** Drops a `:port`, leaving an IPv6 literal's own bracketed colons alone. */
    private fun stripPort(host: String): String =
        when {
            host.startsWith('[') && host.contains(']') -> host.substringBefore(']') + "]"
            host.startsWith('[') -> host
            else -> host.substringBefore(':')
        }
}

/**
 * Google's "I have no favicon for this host" reply, recognised by its bytes.
 *
 * It arrives as HTTP 200 with a 16x16 grey globe - identical bytes for every unknown host,
 * whatever `sz` was asked for - so nothing about the response says "miss" except the payload.
 * Caching it is what made unrelated hosts share one anonymous globe and never re-check.
 */
internal object GoogleNoIconPlaceholder {
    /**
     * Verified against two unrelated nonexistent hosts at sz=16/32/64/128: the same 726 bytes
     * each time. A fingerprint is inherently a bet on Google not changing the asset; the cost of
     * losing that bet is only that one placeholder gets cached again, which is where this
     * started, so it fails no worse than not checking.
     */
    private const val SHA256 = "59bfe9bc385ad69f50793ce4a53397316d7a875a7148a63c16df9b674c6cda64"

    fun matches(bytes: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } == SHA256
    }
}

/**
 * How long a fetched icon is trusted before it is fetched again.
 *
 * A favicon from this service is a guess about a host, and a wrong guess used to be permanent:
 * nothing ever refetched, so a site that had no icon on the day it was first opened kept whatever
 * Google said then for as long as the entry survived eviction.
 *
 * Note `FaviconCache.cleanupStaleEntries` ages the standard cache out after 30 days, so the two
 * favicon caches now expire on different clocks. Deliberate: the standard cache holds what a page
 * actually served, which does not go stale by being a guess.
 */
internal object FaviconFreshness {
    const val MAX_CACHE_AGE_MS = 14L * 24 * 60 * 60 * 1000

    fun isEntryExpired(
        fetchedAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - fetchedAtMs > MAX_CACHE_AGE_MS
}

/**
 * Hosts Google has answered "no icon" for, and when it answered.
 *
 * Declining the placeholder removed the only negative cache this service had. With nothing
 * written for a miss, every re-entry of a tile into composition - toggling the shelf, each launch
 * - spent another request with a 2.5s timeout to learn the same thing, and for the dashboard and
 * the capture picker that is the common path rather than the rare one.
 *
 * In memory rather than on disk: it is cheap to rebuild, and it must not outlive a host adding a
 * favicon by long, so [MISS_MEMORY_MS] is deliberately a small fraction of
 * [FaviconFreshness.MAX_CACHE_AGE_MS].
 *
 * Only a definite answer is recorded. A timeout or an unreachable Google is not a miss - see
 * `HighQualityFaviconService.fetchFromGoogle` - because remembering one would suppress the retry
 * for hours after the network came back.
 */
internal object FaviconMissMemory {
    const val MISS_MEMORY_MS = 6L * 60 * 60 * 1000

    /** Bounded so a long session browsing icon-less hosts cannot grow this without limit. */
    private const val MAX_REMEMBERED = 500

    private val misses = ConcurrentHashMap<String, Long>()

    fun record(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (misses.size >= MAX_REMEMBERED) {
            misses.values.removeIf { nowMs - it > MISS_MEMORY_MS }
            // Nothing had expired, so there is no cheap subset to drop. The whole map costs at
            // most one extra request per host to rebuild.
            if (misses.size >= MAX_REMEMBERED) misses.clear()
        }
        misses[host] = nowMs
    }

    fun remembers(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = misses[host]?.let { nowMs - it <= MISS_MEMORY_MS } == true

    fun forget() {
        misses.clear()
    }
}
