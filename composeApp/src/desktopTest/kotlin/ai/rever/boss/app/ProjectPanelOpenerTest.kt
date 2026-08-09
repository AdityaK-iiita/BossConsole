package ai.rever.boss.app

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Windows comes up as a plain browser, so nothing may open a panel there on its
 * own - not Codebase, not Run Configurations, and not a plugin panel behind
 * either of them.
 *
 * The platform branch is driven explicitly through the `autoOpen` parameter so
 * both halves run on every CI leg; the host-resolved value is asserted
 * separately, since that is the only check that this OS is wired to the right
 * branch at all.
 */
class ProjectPanelOpenerTest {
    /** Unique per case: [PanelEventBus] replays its last open event to new collectors. */
    private fun windowId(case: String) = "test-window-$case"

    @Test
    fun `non-windows opens codebase and run configurations`() =
        runTest {
            val window = windowId("auto-open")
            val collected =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        PanelEventBus.panelOpenEvents
                            .filter { it.sourceWindowId == window }
                            .take(2)
                            .toList()
                    }
                }
            // Let the collector subscribe before emitting: replay is 1, so a late
            // subscriber would miss the first of the two events.
            yield()

            openProjectPanels(window, autoOpen = true)

            val events = assertNotNull(collected.await(), "expected both panels to open")
            assertEquals(listOf(PanelIds.CODEBASE, PanelIds.RUN_CONFIGURATIONS), events.map { it.panelId })
        }

    @Test
    fun `windows opens nothing`() =
        runTest {
            val window = windowId("suppressed")
            val collected =
                async {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        PanelEventBus.panelOpenEvents.first { it.sourceWindowId == window }
                    }
                }
            yield()

            openProjectPanels(window, autoOpen = false)

            assertNull(collected.await(), "no panel may open by itself on Windows")
        }

    @Test
    fun `the policy suppresses auto-open on windows only`() {
        assertTrue(StartupPanelPolicy.autoOpensProjectPanelsFor(isWindows = false))
        assertFalse(StartupPanelPolicy.autoOpensProjectPanelsFor(isWindows = true))
    }

    @Test
    fun `host resolution follows this platform`() {
        assertEquals(!SystemUtils.isWindows, StartupPanelPolicy.autoOpensProjectPanels)
    }

    /**
     * The three startup effects that open project panels must keep routing
     * through [openProjectPanels]. A fourth call site written straight against
     * the bus would open panels on Windows again and no behavioural test would
     * catch it, because those effects only run inside a composed window.
     */
    @Test
    fun `startup effects never open a panel directly`() {
        val source = File(repoRoot, "composeApp/src/commonMain/kotlin/ai/rever/boss/app/BossAppStartupEffects.kt")
        assertTrue(source.isFile, "source moved: ${source.absolutePath}")
        val text = source.readText()

        // Code lines only: a KDoc or comment naming the call is not a call, and this guard
        // firing on prose would be a false positive nobody could fix except by rewording.
        val code =
            text
                .lines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")

        assertEquals(
            0,
            Regex("""PanelEventBus\.openPanel\(""").findAll(code).count(),
            "BossAppStartupEffects must open project panels via openProjectPanels(), " +
                "so the Windows suppression cannot be bypassed",
        )
        assertTrue(code.contains("openProjectPanels("), "the opener seam disappeared from BossAppStartupEffects")
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L

        /** Same repo-root walk as WindowsArm64SourceIsolationTest - the test CWD is not pinned. */
        val repoRoot: File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
                ?: error("could not locate the repository root from ${File("").absolutePath}")
    }
}
