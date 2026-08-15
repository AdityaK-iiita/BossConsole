package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.overlays.NativeContextMenuTestOverride
import ai.rever.boss.components.plugin.PluginBuildInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the panel header's menu and its build tag, neither of which had any coverage.
 *
 * These assertions are about what a person can see and act on, which is where the interesting
 * failures are: an item that reads as available but does nothing, a disabled item that still fires,
 * a tag that claims the wrong build. None of that is visible to a unit test of the model.
 */
class BossPanelTopBarMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val TITLE = "Run Configurations"
    }

    private var reloaded = 0
    private var uninstalled = 0
    private var tagClicked = 0

    /**
     * The rows are found as Compose nodes, so this must run against the drawn menu. A native menu is
     * an OS window with no Compose tree, which would make these fail on macOS and pass on CI for a
     * reason unrelated to what they assert.
     */
    @Before
    fun useDrawnMenu() {
        NativeContextMenuTestOverride.enabled = false
    }

    @After
    fun clearOverride() {
        NativeContextMenuTestOverride.enabled = null
    }

    private fun localBuild(
        version: String = "1.0.3",
        reloadStamp: Long? = null,
    ) = PluginBuildInfo(
        pluginId = "ai.rever.boss.plugin.dynamic.probe",
        displayName = "Probe",
        version = version,
        signedBytes = false,
        storeSourced = false,
        reloadStamp = reloadStamp,
    )

    private fun show(
        buildInfo: PluginBuildInfo? = null,
        uninstallEnabled: Boolean = true,
    ) {
        compose.setContent {
            BossPanelTopBar(
                title = TITLE,
                isHovered = true,
                onReloadPlugin = { reloaded++ },
                onUninstallPlugin = { uninstalled++ },
                uninstallEnabled = uninstallEnabled,
                onMinimize = {},
                buildInfo = buildInfo,
                onBuildTagClick = { tagClicked++ },
            )
        }
    }

    private fun openMenu() {
        compose.onNodeWithText(TITLE).performMouseInput { rightClick() }
    }

    @Test
    fun `the menu offers one reload action and no Restart Panel`() {
        show()
        openMenu()

        compose.onNodeWithText("Reload Panel").assertExists()
        compose.onNodeWithText("Restart Panel").assertDoesNotExist()
        compose.onNodeWithText("Reload Plugin").assertDoesNotExist()
    }

    @Test
    fun `Uninstall Plugin is offered and acts when enabled`() {
        show(uninstallEnabled = true)
        openMenu()

        compose.onNodeWithText("Uninstall Plugin").performClick()

        assertEquals(1, uninstalled)
    }

    @Test
    fun `a disabled Uninstall Plugin is still shown but cannot be acted on`() {
        // Shown-but-disabled is the whole point for system plugins: hiding it reads as the feature
        // being missing. The claim worth pinning is that it is inert, since a greyed row that still
        // fires would start an uninstall the manager refuses halfway through.
        show(uninstallEnabled = false)
        openMenu()

        compose.onNodeWithText("Uninstall Plugin").assertExists()
        compose.onNodeWithText("Uninstall Plugin").performClick()

        assertEquals(0, uninstalled, "a disabled menu row must not invoke its action")
    }

    @Test
    fun `a released build shows no tag and no version row`() {
        show(
            buildInfo =
                PluginBuildInfo(
                    pluginId = "p",
                    displayName = "Probe",
                    version = "1.0.3",
                    signedBytes = true,
                    storeSourced = true,
                    reloadStamp = null,
                ),
        )

        compose.onNodeWithText("DEBUG").assertDoesNotExist()
        compose.onNodeWithText("HOT").assertDoesNotExist()

        openMenu()
        compose.onNodeWithText("Version 1.0.3").assertDoesNotExist()
    }

    @Test
    fun `a local build shows a DEBUG tag next to the title and a clickable version row`() {
        show(buildInfo = localBuild())

        compose.onNodeWithText("DEBUG").assertExists()
        compose.onNodeWithContentDescription("Local build, not the store version: 1.0.3-debug").assertExists()

        openMenu()
        compose.onNodeWithText("Version 1.0.3-debug").performClick()

        assertEquals(1, tagClicked)
    }

    @Test
    fun `clicking the tag itself raises the store-version prompt`() {
        show(buildInfo = localBuild())

        compose.onNodeWithText("DEBUG").performClick()

        assertEquals(1, tagClicked)
    }

    @Test
    fun `a hot reloaded build reads as HOT and carries the reload stamp`() {
        val stamp = 1_754_890_231_447L
        show(buildInfo = localBuild(reloadStamp = stamp))

        compose.onNodeWithText("HOT").assertExists()

        openMenu()
        compose.onNodeWithText("Version 1.0.3-debug+$stamp").assertExists()
    }

    @Test
    fun `the title itself never carries the suffix`() {
        // The tag qualifies the title; it must not rewrite it. A suffixed title would also be
        // persisted into workspace layouts by the tab path and restored stale.
        show(buildInfo = localBuild(reloadStamp = 1L))

        compose.onNodeWithText(TITLE).assertExists()
        assertTrue(reloaded == 0 && uninstalled == 0)
    }
}
