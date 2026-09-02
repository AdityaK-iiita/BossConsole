package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Resolves the icon for a page, from the two sources that can supply one.
 *
 * **The page's own favicon wins; Google is only a fallback.** That order is the whole correctness
 * of this service, and it used to be the other way round:
 *
 * - `FaviconCache` is keyed on the FULL page URL and holds what the tab itself served. Right by
 *   construction, and absent for anything that was never a browser tab.
 * - Google's `s2/favicons` service is asked about a HOST. It fills the gap the first source
 *   leaves, and it is a guess.
 *
 * Asking Google FIRST was the bug: a guess about a host overwrote a known-correct per-page icon.
 * Google resolves subdomains to their parent - `mail.google.com`, `docs.google.com` and
 * `accounts.google.com` all return the same 32px Google "G" - so every Google-property bookmark
 * came out identical, and one on a `google.com/url?q=…` redirect link came out as Google rather
 * than as the site it opens. Neither is recoverable from a host; both were already sitting
 * correct in the standard cache.
 *
 * The order lives here rather than at a call site because all three surfaces want it: the
 * Favorites shelf (`TabBarFavorites`), the dashboard's recent-page cards (`BrowserPageCard`) and
 * the screen-capture picker. Fixing one file left a Gmail entry showing the generic G in the
 * other two.
 *
 * **What that costs, stated plainly:** the dashboard draws its result at 36dp, so a page whose
 * own favicon is 16px is now softer there than Google's 128px guess about its host was. Right
 * site over sharp icon, the same trade as dropping the 32px floor in [decodeUsableIcon].
 *
 * A page whose icon this resolves from cache no longer tells Google which site it is at all,
 * which on the most-used surfaces is most of the requests this service used to make.
 *
 * Performance:
 * - Async HTTP with Ktor client (non-blocking), everything on [Dispatchers.IO]
 * - Reduced timeouts (2.5s) for faster failure detection
 * - Concurrency limit (3 simultaneous fetches) to prevent network flooding
 * - A definite "no icon" answer is remembered ([FaviconMissMemory]) rather than re-asked
 * - Size-capped cache, oldest fetch evicted first, entries expiring after a fortnight
 */
object HighQualityFaviconService {
    private val logger = BossLogger.forComponent("HighQualityFaviconService")

    // Requested, not promised: Google honours it for some hosts and serves 32px for others
    // (every google.com subdomain, among them), so a tile must cope with whatever comes back.
    private const val ICON_SIZE = 128
    private const val REQUEST_TIMEOUT_MS = 2500L
    private const val MAX_CONCURRENT_FETCHES = 3

    // Semaphore to limit concurrent network requests
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

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

    /**
     * The icon for a page: its own cached favicon if there is one, else Google's guess about its
     * host.
     *
     * **Everything blocking is inside [withContext], and callers rely on that.** A `LaunchedEffect`
     * does not move you off the composition dispatcher, so a caller that read the standard cache
     * itself would decode a PNG per tile on the UI thread.
     *
     * @param url the page URL, or null for a tab that is not a page - a terminal, a file. There is
     *   no host to guess from, so such a tab gets its cached icon or nothing.
     * @param standardCacheKey the key into the standard favicon cache, i.e. the page's own icon
     * @return the icon, or null when neither source has one
     */
    suspend fun getHighQualityFavicon(
        url: String?,
        standardCacheKey: String?,
    ): TabIcon.Image? =
        resolve(
            url = url,
            standardCacheKey = standardCacheKey,
            pageIcon = ::loadStandardFavicon,
            hostGuess = { hostIcon(it) },
        )

    /**
     * [getHighQualityFavicon] with both sources injected, so the ORDER between them is pinned by a
     * test rather than by review. It is the one thing in this file that must not quietly flip
     * back, and inside a composable - where it used to live - it was unpinnable.
     *
     * Swapping the two calls below fails `the page's own icon wins and Google is not even asked`.
     * The second half of that name is what a "which icon wins" assertion alone would miss: a page
     * whose icon is already cached must not tell a third party which site it is.
     */
    internal suspend fun resolve(
        url: String?,
        standardCacheKey: String?,
        pageIcon: (String?) -> TabIcon.Image?,
        hostGuess: suspend (String?) -> TabIcon.Image?,
    ): TabIcon.Image? =
        withContext(Dispatchers.IO) {
            try {
                pageIcon(standardCacheKey) ?: hostGuess(url)
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.BROWSER,
                    "Favicon resolution failed - the caller shows its fallback",
                    mapOf("error" to e.toString()),
                )
                null
            }
        }

    /**
     * Google's guess about the host behind [url], from cache when it is fresh.
     *
     * A stale entry is offered last rather than discarded: being offline should cost sharpness,
     * not the icon. It still gets replaced the moment a fetch succeeds.
     */
    private suspend fun hostIcon(url: String?): TabIcon.Image? {
        val host = FaviconHost.of(url) ?: return null
        val cacheKey = HqFaviconDiskCache.keyFor(host)
        val cached = HqFaviconDiskCache.load(cacheKey)
        val now = System.currentTimeMillis()
        val fresh = cached?.takeUnless { FaviconFreshness.isEntryExpired(it.fetchedAtMs, now) }

        val fetched =
            if (fresh != null || FaviconMissMemory.remembers(host, now)) {
                null
            } else {
                fetchSemaphore.withPermit { fetchFromGoogle(host, cacheKey) }
            }

        return fresh?.icon ?: fetched ?: cached?.icon
    }

    /**
     * Fetch from Google's service and cache what comes back.
     *
     * A *definite* "no icon" - the placeholder, or a payload that will not decode - is recorded in
     * [FaviconMissMemory]. A transient one is not: [requestIcon] returns null for a timeout, an
     * unreachable Google or a rate-limit, and remembering those would suppress the retry for
     * hours after the network came back.
     */
    private suspend fun fetchFromGoogle(
        host: String,
        cacheKey: String,
    ): TabIcon.Image? {
        val bytes = requestIcon(host) ?: return null
        val image = decodeUsableIcon(bytes, host)

        return if (image == null) {
            FaviconMissMemory.record(host)
            null
        } else {
            HqFaviconDiskCache.save(cacheKey, image)
            TabIcon.Image(BitmapPainter(image.toComposeImageBitmap()))
        }
    }

    /**
     * The bytes Google served for [host], or null when it did not answer.
     *
     * URL format: `https://www.google.com/s2/favicons?domain=example.com&sz=128`
     */
    private suspend fun requestIcon(host: String): ByteArray? =
        try {
            val response =
                httpClient.get("https://www.google.com/s2/favicons?domain=$host&sz=$ICON_SIZE") {
                    headers {
                        append(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                }
            if (response.status == HttpStatusCode.OK) response.readRawBytes() else null
        } catch (e: Exception) {
            logger.debug(
                LogCategory.NETWORK,
                "HQ favicon fetch from Google failed - falling back",
                mapOf("host" to host, "error" to e.toString()),
            )
            null
        }

    /**
     * The icon in [bytes], or null when Google's answer is not one.
     *
     * Two ways it is not. Its placeholder globe, which it serves with HTTP 200 for any host it
     * knows nothing about, so the payload is the only thing that says "miss" - declining it is
     * the useful answer, because the caller then keeps the page's own favicon or the tile's
     * letter, either of which identifies the site better than a globe every stranger shares. And
     * a payload ImageIO cannot decode.
     *
     * **No minimum size.** The floor was 32px, which rejected every genuine 16px favicon and left
     * the host showing a letter while the real icon sat unused.
     */
    private fun decodeUsableIcon(
        bytes: ByteArray,
        host: String,
    ): BufferedImage? {
        if (GoogleNoIconPlaceholder.matches(bytes)) {
            logger.debug(
                LogCategory.NETWORK,
                "Google has no favicon for host - declining its placeholder",
                mapOf("host" to host),
            )
            return null
        }

        return try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            logger.debug(
                LogCategory.NETWORK,
                "Google's favicon response would not decode",
                mapOf("host" to host, "error" to e.toString()),
            )
            null
        }
    }

    /** The page's own favicon, captured from the tab that served it. */
    private fun loadStandardFavicon(cacheKey: String?): TabIcon.Image? {
        if (cacheKey == null) return null
        return FaviconCache.loadFavicon(cacheKey)
    }

    /** Clear the HQ favicon cache, remembered misses included. */
    fun clearCache() {
        HqFaviconDiskCache.clear()
        FaviconMissMemory.forget()
    }

    /** Entry count and total bytes of the HQ cache. */
    fun getCacheStats(): Pair<Int, Long> = HqFaviconDiskCache.stats()

    /**
     * Cleanup resources when no longer needed.
     */
    fun close() {
        httpClient.close()
    }
}
