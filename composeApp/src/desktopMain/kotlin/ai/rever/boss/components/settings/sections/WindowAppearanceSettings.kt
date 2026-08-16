package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.displayName
import ai.rever.boss.components.bars.isBarVisible
import ai.rever.boss.components.bars.withBarVisible
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.plugin.ui.menu.NativeContextMenus
import ai.rever.boss.window.TabWidthMode
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun WindowAppearanceSettings() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Determine platform default
    val os = System.getProperty("os.name").lowercase()
    val platformDefault =
        when {
            os.contains("mac") -> "Shown"
            os.contains("linux") -> "Hidden"
            os.contains("windows") -> "Hidden"
            else -> "Platform-dependent"
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Title Bar") {
            SettingsToggle(
                label = "Show Title Bar",
                checked = settings.showTitleBar,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.copy(showTitleBar = enabled),
                        )
                    }
                },
                description = "Display the \"Boss Console\" title bar at the top of the window",
            )

            SettingsInfoRow(
                label = "Platform Default",
                value = platformDefault,
                description = "The default setting for your operating system",
            )
        }

        BarsSection()

        NativeContextMenuSection()

        SettingsSection(title = "Tab Bar") {
            SettingsDropdown(
                label = "Tab Sizing",
                options = TabWidthMode.entries.map { it.displayName },
                selectedOption = settings.tabWidthMode.displayName,
                onOptionSelected = { selected ->
                    val mode = TabWidthMode.entries.first { it.displayName == selected }
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.copy(tabWidthMode = mode),
                        )
                    }
                },
                description =
                    "Shrink to Fit: tabs shrink evenly so they all stay visible, scrolling only " +
                        "when each is favicon-sized (Safari style). Fixed Width: tabs keep their natural width " +
                        "and the bar scrolls when they overflow.",
            )
        }
    }
}

/**
 * The four bar visibility flags, which each bar's right-click "Hide" and the View menu's checkmarks
 * write too.
 *
 * Surfaced here as well as in those two places because Settings is where someone looks for chrome
 * they cannot find. A bar hidden from its own context menu leaves nothing behind pointing at where
 * it went, and "it is gone and I cannot get it back" is the failure this whole set of toggles is
 * here to prevent.
 */
@Composable
private fun BarsSection() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsSection(title = "Bars") {
        ChromeBar.entries.forEach { bar ->
            SettingsToggle(
                label = "Show ${bar.displayName()}",
                checked = settings.isBarVisible(bar),
                onCheckedChange = { visible ->
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.withBarVisible(bar, visible),
                        )
                    }
                },
            )
        }

        SettingsInfoRow(
            label = "Applies to",
            value = "All windows",
            description =
                "These stay hidden until you switch them back on, in every window - hiding a bar " +
                    "from its right-click menu hides it everywhere. Focus Mode is separate: it " +
                    "hides bars temporarily and reveals them when you move the pointer to the edge.",
        )
    }
}

/**
 * macOS only - see `shouldUseNativeMenus` for why Windows and Linux stay on the drawn menus.
 * Offering a toggle where it does nothing would just be a lie, so the whole section is hidden.
 */
@Composable
private fun NativeContextMenuSection() {
    if (!NativeContextMenus.isSupported()) return
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsSection(title = "Menus") {
        SettingsToggle(
            label = "Native Context Menus",
            checked = settings.useNativeContextMenus,
            onCheckedChange = { enabled ->
                coroutineScope.launch {
                    WindowAppearanceSettingsManager.updateSettings(
                        settings.copy(useNativeContextMenus = enabled),
                    )
                }
            },
            description =
                "Use macOS's own right-click menus. They follow the system appearance rather " +
                    "than the BOSS theme, and are never hidden behind a web page. Off restores " +
                    "the BOSS-styled menus.",
        )
    }
}

private val TabWidthMode.displayName: String
    get() =
        when (this) {
            TabWidthMode.SHRINK_TO_FIT -> "Shrink to Fit"
            TabWidthMode.FIXED -> "Fixed Width"
        }
