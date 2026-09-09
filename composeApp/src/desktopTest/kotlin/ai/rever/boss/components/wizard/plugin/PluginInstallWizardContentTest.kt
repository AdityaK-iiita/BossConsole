package ai.rever.boss.components.wizard.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class PluginInstallWizardContentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `all failed installs are explained without claiming no selection`() {
        rule.setContent {
            Box(Modifier.size(636.dp, 420.dp)) {
                CompleteStepContent(0, listOf("terminal" to "Download failed"))
            }
        }
        rule.onNodeWithText("No tools were installed successfully").assertIsDisplayed()
        rule.onNodeWithText("Installation incomplete").assertIsDisplayed()
        rule.onNodeWithText("No tools were selected for installation").assertDoesNotExist()
        rule.onNodeWithText("You can retry installing these tools from the Toolbox").assertIsDisplayed()
    }

    @Test
    fun `long failure list scrolls to the last error and keeps recovery guidance visible`() {
        val failures = (1..30).map { "plugin$it" to "Download failed" }
        rule.setContent {
            Box(Modifier.size(636.dp, 420.dp)) {
                CompleteStepContent(2, failures)
            }
        }
        rule.onNodeWithText("• plugin30: Download failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("2 tools installed successfully").assertIsDisplayed()
        rule.onNodeWithText("You can retry installing these tools from the Toolbox").assertIsDisplayed()
    }

    @Test
    fun `empty selection has a distinct completion explanation`() {
        rule.setContent { CompleteStepContent(0) }
        rule.onNodeWithText("No tools were selected for installation").assertIsDisplayed()
        rule.onNodeWithText("No tools were installed successfully").assertDoesNotExist()
    }

    @Test
    fun `required label remains readable beside a long plugin name`() {
        val plugin =
            WizardPluginInfo(
                id = "required",
                name = "A very long plugin name ".repeat(8),
                description = "Required tool",
                version = "1.0.0",
                isMandatory = true,
            )
        rule.setContent {
            Box(Modifier.size(400.dp, 420.dp)) {
                CategoryStepContent(
                    category = PluginCategory.OTHER,
                    plugins = listOf(plugin),
                    isPluginSelected = { true },
                    onTogglePlugin = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }
        rule.onNodeWithText("Required").assertIsDisplayed()
    }
}
