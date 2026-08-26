package ai.rever.boss.window

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of window appearance settings manager.
 * Manages loading and saving of window appearance settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/window-appearance-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object WindowAppearanceSettingsManager {
    private val logger = BossLogger.forComponent("WindowAppearanceSettingsManager")
    private val settingsFile = BossDirectories.resolve("window-appearance-settings.json")

    /**
     * Internal, not private, so a test can encode with the REAL instance.
     *
     * `encodeDefaults` is left at its default of false, and three chrome flags now depend on that:
     * a field equal to its default is not written, so a file that never mentions a bar picks up a
     * changed default instead of being pinned to the old one for ever. Someone switching
     * `encodeDefaults` on for an unrelated reason would strand every existing install on the old
     * chrome, silently and only on the next release. `WindowAppearanceEncodeDefaultsTest` fails
     * if that happens.
     */
    internal val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _currentSettings = MutableStateFlow(WindowAppearanceSettings())
    actual val currentSettings: StateFlow<WindowAppearanceSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses platform-specific defaults and saves them.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val loaded = json.decodeFromString<WindowAppearanceSettings>(content)
                // A file written by an older build may need moving to this one's defaults. See
                // WindowAppearanceMigrations - changing the defaults alone would reach new
                // installs only, because this manager writes the whole object on every save.
                val migrated = WindowAppearanceMigrations.migrate(loaded)
                _currentSettings.value = migrated ?: loaded
                if (migrated != null) {
                    // Written back immediately, so the step is not re-applied on every launch -
                    // and so a value the user changes afterwards is never overwritten by it.
                    runCatching {
                        settingsFile.writeText(json.encodeToString(WindowAppearanceSettings.serializer(), migrated))
                    }.onFailure { e ->
                        logger.warn(LogCategory.SYSTEM, "Could not write migrated settings", error = e)
                    }
                }
                logger.debug(LogCategory.SYSTEM, "Loaded settings", mapOf("path" to settingsFile.absolutePath))
            } else {
                // First run - create default settings file with platform-specific defaults
                val defaults = getDefaultSettings()
                _currentSettings.value = defaults

                // Save default settings to file
                try {
                    val content = json.encodeToString(WindowAppearanceSettings.serializer(), defaults)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default settings", mapOf("path" to settingsFile.absolutePath))
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default settings file", error = e)
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load settings", error = e)
            _currentSettings.value = getDefaultSettings()
        }
    }

    /**
     * Save current settings to disk asynchronously.
     */
    private suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(WindowAppearanceSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.SYSTEM, "Settings saved", mapOf("path" to settingsFile.absolutePath))
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Failed to save settings", error = e)
            }
        }

    /**
     * Update the current settings and save to disk asynchronously.
     */
    actual suspend fun updateSettings(settings: WindowAppearanceSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    actual fun getDefaultSettings(): WindowAppearanceSettings {
        // The title row is ON for macOS and off elsewhere. macOS draws the close / minimise / zoom
        // buttons over the window's content (`apple.awt.fullWindowContent`), and this row is what
        // holds them; on Windows and Linux the OS draws its own frame and the row is just a bar.
        //
        // The branch lives here rather than in the class default, which has to stay false so that
        // a Windows or Linux file - which does not mention the field either - keeps the row off.
        //
        // Stamped current: a fresh file is already on this build's defaults and must not be
        // migrated on the next launch as though it were an older one.
        return WindowAppearanceSettings(
            showTitleBar = SystemUtils.isMacOS,
            settingsVersion = WindowAppearanceSettings.CURRENT_SETTINGS_VERSION,
        )
    }
}
