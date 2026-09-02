package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Service for fetching high-quality favicons for the dashboard.
 * Uses Google's favicon service to get larger icons (up to 128px).
 * Falls back to standard favicon cache if high-quality version unavailable.
 *
 * Performance optimizations:
 * - Async HTTP with Ktor client (non-blocking)
 * - Reduced timeouts (2.5s) for faster failure detection
 * - Concurrency limit (3 simultaneous fetches) to prevent network flooding
 * - Size-capped cache, oldest fetch evicted first, entries expiring after a fortnight
 * - Cache-first approach to minimize network requests
 */
object HighQualityFaviconService {
    private val logger = BossLogger.forComponent("HighQualityFaviconService")
    private const val HQ_CACHE_DIR_NAME = "favicon-hq-cache"

    // Requested, not promised: Google honours it for some hosts and serves 32px for others
    // (every google.com subdomain, among them), so a tile must cope with whatever comes back.
    private const val ICON_SIZE = 128
    private const val REQUEST_TIMEOUT_MS = 2500L
    private const val MAX_CONCURRENT_FETCHES = 3
    private const val MAX_CACHE_SIZE = 200 // Maximum number of cached favicons
    private const val CACHE_EVICTION_COUNT = 50 // Number of items to evict when limit reached

    /**
     * How long a fetched icon is trusted before it is fetched again.
     *
     * A favicon here is a guess about a host, and a wrong guess used to be permanent: nothing
     * ever refetched, so a site that had no icon on the day it was first opened kept whatever
     * Google said then for as long as the entry survived eviction.
     */
    private const val MAX_CACHE_AGE_MS = 14L * 24 * 60 * 60 * 1000

    // Semaphore to limit concurrent network requests
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    // Mutex for thread-safe cache operations
    private val cacheMutex = Mutex()

    // Ktor HTTP client with connection pooling
    private val httpClient by lazy {
        HttpClient(CIO) {
            engine {
                requestTimeout = REQUEST_TIMEOUT_MS
                endpoint {
                    connectTimeout = REQUEST_TIMEOUT_MS
                    socketTimeout = REQUEST_TIMEOUT_MS
                }
            }
        }
    }

    private val cacheDir: File by lazy {
        val dir = BossDirectories.resolve("cache/$HQ_CACHE_DIR_NAME")
        dir.mkdirs()
        dir
    }

    /**
     * Get a high-quality favicon for a URL.
     * First checks HQ cache, then fetches from Google if needed.
     * Falls back to standard favicon cache if Google service fails.
     *
     * Uses semaphore to limit concurrent network requests to MAX_CONCURRENT_FETCHES.
     *
     * @param url The page URL to get favicon for
     * @param standardCacheKey The cache key from the standard favicon cache (fallback)
     * @return ai.rever.boss.plugin.api.TabIcon.Image if found, null otherwise
     */
    suspend fun getHighQualityFavicon(
        url: String,
        standardCacheKey: String?,
    ): ai.rever.boss.plugin.api.TabIcon.Image? {
        return withContext(Dispatchers.IO) {
            try {
                val domain = extractFaviconHost(url) ?: return@withContext loadStandardFavicon(standardCacheKey)
                val cacheKey = generateCacheKey(domain)

                // Check HQ cache first (no semaphore needed for local cache)
                val cached = loadFromCache(cacheKey)
                if (cached != null) {
                    return@withContext cached
                }

                // Try to fetch from Google's favicon service (with concurrency limit)
                val fetched =
                    fetchSemaphore.withPermit {
                        fetchFromGoogle(domain, cacheKey)
                    }
                if (fetched != null) {
                    return@withContext fetched
                }

                // Fall back to standard favicon
                loadStandardFavicon(standardCacheKey)
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.BROWSER,
                    "HQ favicon fetch failed - falling back to standard favicon",
                    mapOf("error" to e.toString()),
                )
                loadStandardFavicon(standardCacheKey)
            }
        }
    }

    /**
     * Generate cache key for domain.
     */
    private fun generateCacheKey(domain: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(domain.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Load favicon from HQ cache, unless the entry is older than [MAX_CACHE_AGE_MS].
     *
     * **Reads no longer touch the file.** The mtime was bumped on every read to order an LRU,
     * which had two costs: the age this TTL needs became unknowable, and the entries shown most
     * often - including a wrong one on a favourite tile - were the ones eviction could never
     * reach. Eviction is therefore oldest-fetched-first, which for 200 icons that all expire in
     * a fortnight anyway is the same set of files in a slightly different order.
     */
    private fun loadFromCache(cacheKey: String): ai.rever.boss.plugin.api.TabIcon.Image? {
        val cacheFile = File(cacheDir, "$cacheKey.png")
        if (!cacheFile.exists()) return null

        if (System.currentTimeMillis() - cacheFile.lastModified() > MAX_CACHE_AGE_MS) {
            // Delete rather than merely ignore, so a host that has since dropped its favicon
            // does not keep serving this entry from disk on every later miss.
            cacheFile.delete()
            return null
        }

        return try {
            val bufferedImage = ImageIO.read(cacheFile) ?: return null
            val imageBitmap = bufferedImage.toComposeImageBitmap()
            ai.rever.boss.plugin.api.TabIcon
                .Image(BitmapPainter(imageBitmap))
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Failed to read cached HQ favicon - treating as cache miss",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Fetch high-quality favicon from Google's service using async Ktor client.
     * URL format: https://www.google.com/s2/favicons?domain=example.com&sz=128
     */
    private suspend fun fetchFromGoogle(
        domain: String,
        cacheKey: String,
    ): ai.rever.boss.plugin.api.TabIcon.Image? {
        val googleUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=$ICON_SIZE"

        return try {
            val response =
                httpClient.get(googleUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                }

            if (response.status == HttpStatusCode.OK) {
                val bytes = response.readRawBytes()

                // Google has no icon for this host. Declining its placeholder is the useful
                // answer: the caller then keeps the page's own favicon, or the tile's letter,
                // either of which identifies the site better than a globe every stranger shares.
                val placeholder = isNoIconPlaceholder(bytes)
                if (placeholder) {
                    logger.debug(
                        LogCategory.NETWORK,
                        "Google has no favicon for host - declining its placeholder",
                        mapOf("domain" to domain),
                    )
                }

                val bufferedImage = if (placeholder) null else ImageIO.read(ByteArrayInputStream(bytes))

                if (bufferedImage != null) {
                    // No minimum size. The floor was 32px, which rejected every genuine 16px
                    // favicon - the host then showed a letter while the real icon sat unused. A
                    // 16px icon in a 22dp tile is soft; it is still the right site.
                    evictIfNeeded()

                    // Save to cache
                    val cacheFile = File(cacheDir, "$cacheKey.png")
                    ImageIO.write(bufferedImage, "PNG", cacheFile)

                    val imageBitmap = bufferedImage.toComposeImageBitmap()
                    ai.rever.boss.plugin.api.TabIcon
                        .Image(BitmapPainter(imageBitmap))
                } else {
                    // Not an image we can decode. Nothing to cache.
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.NETWORK,
                "HQ favicon fetch from Google failed - falling back",
                mapOf("domain" to domain, "error" to e.toString()),
            )
            null
        }
    }

    /**
     * Evict oldest entries if cache exceeds MAX_CACHE_SIZE.
     * Uses file modification time, which is now the FETCH time - see [loadFromCache] for why
     * reads no longer bump it, and what that changes about the eviction order.
     */
    private suspend fun evictIfNeeded() {
        cacheMutex.withLock {
            val files = cacheDir.listFiles() ?: return

            if (files.size >= MAX_CACHE_SIZE) {
                // Sort by last modified (oldest first) and delete oldest entries
                val toDelete =
                    files
                        .sortedBy { it.lastModified() }
                        .take(CACHE_EVICTION_COUNT)

                toDelete.forEach { file ->
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        // Deletion errors are non-fatal - entry will be retried on next eviction
                        logger.debug(
                            LogCategory.FILE,
                            "Failed to evict HQ favicon cache entry",
                            mapOf("file" to file.name, "error" to e.toString()),
                        )
                    }
                }
            }
        }
    }

    /**
     * Load from standard favicon cache as fallback.
     */
    private fun loadStandardFavicon(cacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image? {
        if (cacheKey == null) return null
        return FaviconCache.loadFavicon(cacheKey)
    }

    /**
     * Clear the HQ favicon cache.
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // Cache clearing is best-effort - stale entries are harmless
            logger.debug(LogCategory.FILE, "Failed to clear HQ favicon cache", mapOf("error" to e.toString()))
        }
    }

    /**
     * Get cache stats.
     */
    fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles() ?: return Pair(0, 0L)
        return Pair(files.size, files.sumOf { it.length() })
    }

    /**
     * Cleanup resources when no longer needed.
     */
    fun close() {
        httpClient.close()
    }
}

/**
 * The host Google should be asked about, or null when there is nothing to ask.
 *
 * Only http(s) URLs have a host Google can answer for. Everything else - `file:///…`, `boss://`,
 * a bare path - used to reach here as a "domain" of whatever sat before the first slash (`file:`
 * for a local file), which spends a network round trip to be told no.
 *
 * The port goes too. Google's service keys on host alone and 404s on `localhost:3000`, so a dev
 * server got no icon at all while plain `localhost` would have answered.
 *
 * Every step is a `substringBefore`/`substringAfter`, none of which throw on any input, so there
 * is nothing here to catch - the previous version's try/catch was unreachable.
 */
internal fun extractFaviconHost(url: String): String? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null

    return url
        .substringAfter("://")
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

/** Drops a `:port`, leaving an IPv6 literal's own bracketed colons alone. */
private fun stripPort(host: String): String =
    when {
        host.startsWith('[') && host.contains(']') -> host.substringBefore(']') + "]"
        host.startsWith('[') -> host
        else -> host.substringBefore(':')
    }

/**
 * SHA-256 of the icon Google returns when it has NO favicon for a host.
 *
 * It arrives as HTTP 200 with a 16x16 grey globe - identical bytes for every unknown host,
 * whatever `sz` was asked for - so nothing about the response says "miss" except the payload.
 * Caching it is what made unrelated hosts share one anonymous globe and never re-check.
 *
 * Verified against two unrelated nonexistent hosts at sz=16/32/64/128: the same 726 bytes
 * each time. A fingerprint is inherently a bet on Google not changing the asset; the cost of
 * losing that bet is only that one placeholder gets cached again, which is where this
 * started, so it fails no worse than not checking.
 */
private const val NO_ICON_PLACEHOLDER_SHA256 =
    "59bfe9bc385ad69f50793ce4a53397316d7a875a7148a63c16df9b674c6cda64"

/**
 * Whether these bytes are Google's "no favicon for this host" globe.
 *
 * See [NO_ICON_PLACEHOLDER_SHA256] for why the check is on the payload
 * rather than on the status code.
 */
internal fun isNoIconPlaceholder(bytes: ByteArray): Boolean {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) } == NO_ICON_PLACEHOLDER_SHA256
}
