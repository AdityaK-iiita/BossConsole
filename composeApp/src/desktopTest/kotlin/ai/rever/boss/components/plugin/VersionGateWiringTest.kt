package ai.rever.boss.components.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the recovery path is actually connected end to end.
 *
 * A **source** test, and it proves less than a behavioural one. It exists for the four joins a unit
 * test cannot reach: a composable mounted in a window's dialog host, an `actual` bridge that talks
 * to `PluginStoreSetup`, a startup registration in `main`, and a catch block inside the plugin
 * manager's load lock. The decisions themselves are tested properly in
 * [VersionGateTranslationTest], [PluginVersionRemedyLabelTest] and [PluginRollbackStoreTest].
 *
 * Each join was a silent failure on its own: without the snapshot there is nothing to revert to,
 * without the record no dialog appears, without the registration the dialog has no remedies, and
 * without the mount none of it is on screen. All four together are what turns one ERROR line in
 * `~/.boss/logs` into a button.
 */
class VersionGateWiringTest {
    private fun source(relative: String): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
        val file = File(assertNotNull(root, "could not locate the repository root"), relative)
        assertTrue(file.isFile, "missing source file: $relative")
        return file.readText()
    }

    @Test
    fun `the plugin manager records a version-floor refusal`() {
        val manager =
            source("composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt")
        assertTrue(
            manager.contains("versionGateFor(error)?.let(PluginVersionGateRegistry::record)"),
            "a refused plugin is back to vanishing with only a log line",
        )
    }

    @Test
    fun `the record happens before the failure returns`() {
        // Ordering: after the `return@withLock` it is unreachable, and the test above would still
        // pass on the string alone.
        val manager =
            source("composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt")
        val tail = manager.substringAfter("versionGateFor(error)?.let(PluginVersionGateRegistry::record)")
        assertTrue(
            tail.contains("return@withLock Result.failure(error ?: Exception(\"Unknown error\"))"),
            "the refusal is recorded after the load failure has already returned",
        )
    }

    @Test
    fun `the update path snapshots the jar it is about to replace`() {
        // Without this there is no way back at all. The update writes a NEW version-named file and
        // reconcile then deletes the old one, so the working jar stops existing the moment the
        // update succeeds.
        val bridge =
            source("composeApp/src/desktopMain/kotlin/ai/rever/boss/components/plugin/PluginUpdateBridge.kt")
        assertTrue(
            bridge.contains("PluginRollbackStore.snapshot(pluginDir, pluginId, installedJar)"),
            "the update no longer keeps the jar it replaces, so Revert has nothing to restore",
        )
    }

    @Test
    fun `the snapshot is taken before anything downloads`() {
        // After `mgr.updatePlugin` the old jar may already be gone: that call unloads, promotes and
        // then reconciles. A snapshot there would copy nothing and report success.
        val bridge =
            source("composeApp/src/desktopMain/kotlin/ai/rever/boss/components/plugin/PluginUpdateBridge.kt")
        val snapshot = bridge.indexOf("PluginRollbackStore.snapshot(")
        val update = bridge.indexOf("mgr.updatePlugin(")
        assertTrue(snapshot in 0 until update, "the snapshot runs after the update has already replaced the jar")
    }

    @Test
    fun `the remedy resolver is registered at startup`() {
        // Before any plugin loads, because the refusal happens during startup plugin loading. A
        // gate recorded before this runs sits in the registry with nothing able to act on it.
        val main = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt")
        assertTrue(
            main.contains("PluginVersionRemedyAccess") && main.contains("DesktopPluginVersionRemedyResolver"),
            "the dialog would render with no remedies because nothing populated the holder",
        )
    }

    @Test
    fun `the dialog is mounted in the app dialog host`() {
        val dialogs = source("composeApp/src/commonMain/kotlin/ai/rever/boss/app/BossAppDialogs.kt")
        assertTrue(dialogs.contains("PluginVersionGateHost("), "the recovery dialog is never shown")
        assertTrue(
            dialogs.contains("remedyResolver = PluginVersionRemedyAccess.current()"),
            "the mounted dialog is not connected to the desktop resolver",
        )
    }
}
