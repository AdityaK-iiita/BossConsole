package ai.rever.boss.components.overlays

import ai.rever.boss.crash.pluginprobe.PluginProbeJar
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins that a context-menu action is invoked **inside its plugin's attribution
 * scope**, through the real `contextMenu` modifier.
 *
 * This is the one production seam plugin crash recovery rests on, and it is the
 * kind that fails silently. `PluginExecutionBoundaryTest` proves the attribution
 * machinery works; nothing proved it was *called*. Delete the `invokeAttributed`
 * call in `ContextMenu.kt` and every one of those tests still passes while every
 * plugin callback quietly becomes unattributed - and the next plugin crash takes
 * the whole app down again, which is the bug the feature exists to fix. Same shape
 * as the `noteRecoveryOutcome` wiring bug `WindowExceptionRoute` documents.
 *
 * The action is a **genuinely plugin-defined lambda**, loaded from a real jar
 * through a real `PluginClassLoader`. A host-owned lambda would attribute to
 * nobody whether or not the wiring exists, so the assertion would be vacuous - it
 * has to be a lambda the boundary can actually resolve.
 */
class ContextMenuAttributionTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val TARGET_TAG = "context-menu-target"
        const val ITEM_LABEL = "Plugin action"
        const val HOST_ITEM_LABEL = "Host action"
    }

    private val probe = PluginProbeJar.open(javaClass.classLoader)

    @After
    fun tearDown() = probe.close()

    @Test
    fun `a plugin's context-menu action runs inside that plugin's scope`() {
        // Read from INSIDE the plugin-owned callback: the sink is host-owned, so it
        // sees whatever scope the call was made in. Null means nobody established
        // one, which is exactly what deleting the wiring produces.
        var scopeInsideAction: String? = "never invoked"
        val pluginAction =
            probe.action(
                "probeReporter",
                Runnable { scopeInsideAction = PluginExecutionBoundary.currentPluginId() },
            )
        val items = listOf(ContextMenuItem(text = ITEM_LABEL, onClick = pluginAction))

        rule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(200.dp)
                        .testTag(TARGET_TAG)
                        .contextMenu(items = items),
            )
        }
        rule.onNodeWithTag(TARGET_TAG).performMouseInput { rightClick() }
        rule.waitForIdle()
        rule.onNodeWithText(ITEM_LABEL).performClick()
        rule.waitForIdle()

        assertEquals(
            PluginProbeJar.PLUGIN_ID,
            scopeInsideAction,
            "the menu must invoke a plugin action inside that plugin's attribution scope",
        )
    }

    @Test
    fun `the scope does not outlive the action`() {
        // The dispatching thread is pooled and long-lived, so a scope left behind
        // would blame this plugin for the next unrelated crash on it.
        //
        // Observed from a SECOND click rather than from runOnIdle: the scope is
        // thread-local and runOnIdle lands on the AWT event thread while the click
        // dispatches on the test worker, so a check there passes whether or not the
        // scope leaked - exactly the vacuity this suite exists to avoid. Verified,
        // not assumed: asserting the two threads matched failed with
        // "expected Test worker but was AWT-EventQueue-0".
        var pluginThread: Thread? = null
        var hostThread: Thread? = null
        var scopeOnSecondClick: String? = "never invoked"
        val pluginAction =
            probe.action("probeReporter", Runnable { pluginThread = Thread.currentThread() })
        val hostAction = {
            hostThread = Thread.currentThread()
            scopeOnSecondClick = PluginExecutionBoundary.currentPluginId()
        }
        val items =
            listOf(
                ContextMenuItem(text = ITEM_LABEL, onClick = pluginAction),
                ContextMenuItem(text = HOST_ITEM_LABEL, onClick = hostAction),
            )

        rule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(200.dp)
                        .testTag(TARGET_TAG)
                        .contextMenu(items = items),
            )
        }
        clickMenuItem(ITEM_LABEL)
        clickMenuItem(HOST_ITEM_LABEL)

        assertEquals(pluginThread, hostThread, "precondition: both actions ran on the same thread")
        assertEquals(null, scopeOnSecondClick, "the plugin's scope must not still be on that thread")
    }

    /** Right-click the target and pick [label]. */
    private fun clickMenuItem(label: String) {
        rule.onNodeWithTag(TARGET_TAG).performMouseInput { rightClick() }
        rule.waitForIdle()
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }
}
