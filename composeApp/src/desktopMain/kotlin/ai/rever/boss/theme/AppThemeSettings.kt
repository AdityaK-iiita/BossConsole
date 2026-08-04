package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import kotlinx.serialization.Serializable

/**
 * Persisted host theme preference. `appThemeId` matches a [BossThemes] id
 * ("blueprint", "blueprint-light", "operator", "daylight", "clean"). Stored at
 * ~/.boss/app-theme-settings.json.
 *
 * Note that `BossConsoleRust` reads and writes this same file, so its
 * `ThemeId::DEFAULT` must stay in lockstep with [BossThemes.DEFAULT_ID] — a
 * settings file that omits `appThemeId` means "the default", and the two apps
 * would otherwise disagree about which theme that is.
 */
@Serializable
data class AppThemeSettings(
    val appThemeId: String = BossThemes.DEFAULT_ID,
)
