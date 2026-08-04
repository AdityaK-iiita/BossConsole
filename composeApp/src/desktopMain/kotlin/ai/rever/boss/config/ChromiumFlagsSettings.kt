package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The config keys the Chromium engine tunables are read from, named once.
 *
 * Every one of these was an environment variable before Settings existed, and the
 * variables keep working — this object is the shared vocabulary between the persisted
 * settings, the startup bridge that publishes them, and the Settings UI that shows
 * which of them the environment has taken over. Three copies of these strings is three
 * chances for a setting to write a key nothing reads.
 */
internal object ChromiumFlagKeys {
    const val RENDERING_MODE = "BOSS_RENDERING_MODE"
    const val SKIKO_RENDER_API = "BOSS_SKIKO_RENDER_API"
    const val TOP_INSET_DP = "BOSS_BROWSER_TOP_INSET_DP"
    const val PREWARM = "BOSS_BROWSER_PREWARM"
    const val RENDERER_PROCESS_LIMIT = "BOSS_RENDERER_PROCESS_LIMIT"
    const val SKIA_GRAPHITE = "BOSS_ENABLE_SKIA_GRAPHITE"
    const val DISABLE_SANDBOX = "BOSS_CHROMIUM_DISABLE_SANDBOX"
    const val EXTRA_SWITCHES = "BOSS_CHROMIUM_EXTRA_SWITCHES"
    const val REMOTE_DEBUGGING_PORT = "BOSS_BROWSER_REMOTE_DEBUGGING_PORT"

    /**
     * Keys [ChromiumFlagsSettingsManager.applyToSystemProperties] publishes. Notably
     * NOT [REMOTE_DEBUGGING_PORT]: that one is read straight off the settings object by
     * FluckEngine, so it can never arrive from local.properties or an embedded build
     * config. See [ChromiumFlagsSettings.remoteDebuggingPort].
     */
    val PUBLISHED =
        listOf(
            RENDERING_MODE,
            SKIKO_RENDER_API,
            TOP_INSET_DP,
            PREWARM,
            RENDERER_PROCESS_LIMIT,
            SKIA_GRAPHITE,
            DISABLE_SANDBOX,
            EXTRA_SWITCHES,
        )

    /**
     * Skiko backends [SKIKO_RENDER_API] accepts, shared with the Settings dropdown so it
     * cannot offer a value main.kt will reject.
     *
     * Validated against an allowlist rather than forwarded raw because main.kt applies
     * this before AWT and Skiko initialise: an unrecognised backend surfaces as a
     * startup crash with no BOSS log line, on exactly the GPU-less RDP/VM machines the
     * pin exists to help.
     */
    val SKIKO_RENDER_APIS = listOf("DIRECT3D", "OPENGL", "METAL", "SOFTWARE_FAST", "SOFTWARE")
}

/**
 * Chromium engine flags chosen in Settings > Browser Engine, persisted to
 * ~/.boss/chromium-flags.json.
 *
 * **Every field is nullable, and null means "no opinion"** — follow the platform
 * default, or whatever the environment says. That is not the same as false: a user who
 * has never opened this screen must get byte-identical engine options to the ones they
 * got before it existed, and a `Boolean` defaulting to false would instead silently
 * turn off flags (`--no-pings`, VA-API decode) that ship on. The nullable form also
 * makes "reset this one row" expressible without knowing the platform's answer.
 *
 * Nothing here applies to a running engine. Chromium's options are fixed when the
 * engine is built, once per process, and the heavyweight-overlay routing is decided at
 * startup from the rendering mode — so the Settings UI offers a restart rather than
 * pretending to be live.
 *
 * @property renderingMode HARDWARE / OFF_SCREEN spelling honoured by
 *   [JxBrowserConfig.resolveRenderingMode]; unrecognised values fall back to the
 *   platform default there rather than being rejected here, so one parser decides.
 * @property remoteDebuggingPort DevTools port, or null for off. Deliberately excluded
 *   from [ChromiumFlagKeys.PUBLISHED] and read directly by FluckEngine: an open
 *   DevTools port is full control of the browser profile, and routing it through
 *   ConfigLoader would let a line in someone's local.properties enable it for every
 *   future run of that checkout. Reachable only from the environment, or from this
 *   file — which the UI writes only behind a confirmation.
 */
@Serializable
data class ChromiumFlagsSettings(
    val renderingMode: String? = null,
    val skikoRenderApi: String? = null,
    val topInsetDp: Int? = null,
    val browserPrewarm: Boolean? = null,
    val rendererProcessLimit: Int? = null,
    val enableSkiaGraphite: Boolean? = null,
    val disableSandbox: Boolean? = null,
    val diskCacheMb: Int? = null,
    val noPings: Boolean? = null,
    val disableDomainReliability: Boolean? = null,
    val disableWinOcclusion: Boolean? = null,
    val enableVaapi: Boolean? = null,
    val remoteDebuggingPort: Int? = null,
    val extraSwitches: String? = null,
) {
    /** Whether the user has expressed any opinion at all — drives "Reset" being offered. */
    val isDefault: Boolean get() = this == ChromiumFlagsSettings()

    /**
     * The value to publish for [key], or null to publish nothing.
     *
     * A `when` over the key rather than a map built per call, so adding a field to this
     * class without adding it here is a compile-time `when` that no longer covers
     * [ChromiumFlagKeys.PUBLISHED] — caught by ChromiumFlagsSettingsTest rather than by
     * a user wondering why their setting does nothing.
     */
    internal fun publishedValue(key: String): String? =
        when (key) {
            ChromiumFlagKeys.RENDERING_MODE -> renderingMode
            ChromiumFlagKeys.SKIKO_RENDER_API -> skikoRenderApi
            ChromiumFlagKeys.TOP_INSET_DP -> topInsetDp?.toString()
            ChromiumFlagKeys.PREWARM -> browserPrewarm?.toString()
            ChromiumFlagKeys.RENDERER_PROCESS_LIMIT -> rendererProcessLimit?.toString()
            ChromiumFlagKeys.SKIA_GRAPHITE -> enableSkiaGraphite?.toString()
            ChromiumFlagKeys.DISABLE_SANDBOX -> disableSandbox?.toString()
            ChromiumFlagKeys.EXTRA_SWITCHES -> extraSwitches?.takeIf { it.isNotBlank() }
            else -> null
        }
}

/**
 * Persistence and startup publication for [ChromiumFlagsSettings].
 *
 * Loaded synchronously in `init`, matching [BrowserEngineSettingsManager]: main.kt
 * publishes these before AWT and Skiko initialise and long before the first frame, so
 * an async load would race the very reads it exists to feed.
 */
object ChromiumFlagsSettingsManager {
    private val logger = BossLogger.forComponent("ChromiumFlagsSettingsManager")
    private val settingsFile = BossDirectories.resolve("chromium-flags.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * The settings this process started with.
     *
     * Every flag here is restart-scoped, so this is what "needs a restart" is measured
     * against: any difference from it is a change the running engine cannot have picked
     * up. Comparing against the live engine's switch list instead would miss the flags
     * that are not switches at all — the sandbox opt-out, the Skiko backend, the
     * DevTools port, prewarm — and would have nothing to say before the engine boots.
     */
    val bootSettings: ChromiumFlagsSettings = loadSync()

    private val _currentSettings = MutableStateFlow(bootSettings)
    val currentSettings: StateFlow<ChromiumFlagsSettings> = _currentSettings.asStateFlow()

    private fun loadSync(): ChromiumFlagsSettings =
        try {
            if (settingsFile.exists()) {
                json.decodeFromString<ChromiumFlagsSettings>(settingsFile.readText())
            } else {
                ChromiumFlagsSettings()
            }
        } catch (e: Exception) {
            // Defaults, not a crash: this file decides how the browser composites, and a
            // hand-edited or truncated one must not be able to stop the app booting.
            logger.warn(LogCategory.BROWSER, "Error loading Chromium flag settings, using defaults", error = e)
            ChromiumFlagsSettings()
        }

    /**
     * Publish the persisted flags as system properties so every existing read site sees
     * them through [ConfigLoader] with no change to how it resolves anything.
     *
     * [ConfigLoader.resolve] already ranks **env > system property > local.properties >
     * embedded > default**, so slotting settings in at the system-property tier gives
     * two properties worth stating plainly, because users will hit both:
     *
     *  - **An environment variable still wins.** Debugging one session by exporting a
     *    variable keeps working, and keeps working the way it always did.
     *  - **Settings beat local.properties.** A developer's checked-out defaults no
     *    longer silently outrank a choice made in the app.
     *
     * A key the environment already owns is skipped rather than set, purely so the log
     * says so — [ConfigLoader] would rank the env value first either way. The Settings
     * UI surfaces the same conflict per row, since otherwise a user with
     * BOSS_RENDERING_MODE exported watches a dropdown do nothing.
     *
     * Must run before anything reads these keys — before the Skiko block in main.kt
     * (which reads [ChromiumFlagKeys.SKIKO_RENDER_API] before AWT starts) and before
     * the first touch of [JxBrowserConfig.renderingMode], whose `by lazy` caches
     * forever.
     */
    fun applyToSystemProperties() {
        val settings = _currentSettings.value
        val wanted = ChromiumFlagKeys.PUBLISHED.mapNotNull { key -> settings.publishedValue(key)?.let { key to it } }
        val (envOwned, toPublish) = wanted.partition { (key, _) -> System.getenv(key) != null }

        val published = toPublish.toMap()
        published.forEach { (key, value) -> System.setProperty(key, value) }

        if (published.isNotEmpty()) {
            // An audit trail on a par with the extra-switches one in FluckEngine: these
            // values change how the browser composites and how hardened it is, so a
            // session should say what it is running with.
            logger.info(
                LogCategory.BROWSER,
                "Applied Chromium flag settings",
                mapOf("settings" to published.entries.joinToString(" ") { "${it.key}=${it.value}" }),
            )
        }
        if (envOwned.isNotEmpty()) {
            logger.info(
                LogCategory.BROWSER,
                "Chromium flag settings ignored for keys set in the environment",
                mapOf("keys" to envOwned.joinToString(" ") { it.first }),
            )
        }
    }

    /**
     * The environment's value for [key], or null. Used by the Settings UI to say a row
     * is overridden instead of letting it look broken. Reads the environment ONLY: a
     * system property here would report this object's own publication back to it.
     */
    fun envOverride(key: String): String? = System.getenv(key)

    /**
     * What [key] would resolve to on the next launch given [settings] — **env first,
     * then the setting**, which is the same order [applyToSystemProperties] produces.
     *
     * Exists so the "next launch" command-line preview cannot drift from the real
     * resolution. It deliberately does not consult system properties: those hold the
     * values published from [bootSettings] at startup, so a preview reading them would
     * show what this session already has rather than what the user just chose. It also
     * skips local.properties and the embedded config — a preview that silently swapped
     * in a checkout-level default would misreport the effect of the visible setting.
     */
    internal fun previewValue(
        settings: ChromiumFlagsSettings,
        key: String,
    ): String? = System.getenv(key) ?: settings.publishedValue(key)

    suspend fun updateSettings(settings: ChromiumFlagsSettings) =
        withContext(Dispatchers.IO) {
            _currentSettings.value = settings
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(json.encodeToString(ChromiumFlagsSettings.serializer(), settings))
                logger.debug(LogCategory.BROWSER, "Chromium flag settings saved")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving Chromium flag settings", error = e)
            }
        }

    suspend fun resetToDefault() = updateSettings(ChromiumFlagsSettings())
}
