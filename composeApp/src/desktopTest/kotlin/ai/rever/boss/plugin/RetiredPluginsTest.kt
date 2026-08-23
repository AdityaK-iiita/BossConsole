package ai.rever.boss.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers when a retired plugin is uninstalled and, mostly, when it is not.
 *
 * Every "not" here is a case where removing it would take away the only panel the user has for
 * the job: the replacement missing, the replacement's jar gone, or a replacement too old to have
 * absorbed the retired plugin's work. The decision therefore fails closed at every step, which
 * is the opposite of `satisfiesVersionFloor`'s own default, so it needs pinning rather than
 * trusting.
 */
class RetiredPluginsTest {
    private val removed = mutableListOf<String>()
    private val announced = mutableListOf<String>()

    private val retirement =
        RetiredPlugins.Retirement(
            pluginId = OLD,
            displayName = "Old Panel",
            replacementId = NEW,
            replacementDisplayName = "New Panel",
            minReplacementVersion = "1.2.17",
        )

    @Test
    fun `retires when the replacement is installed and new enough`() {
        val result = sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17")))

        assertEquals(listOf(OLD), result)
        assertEquals(listOf(OLD), removed)
        assertEquals(listOf("Old Panel is now part of New Panel"), announced)
    }

    @Test
    fun `retires when the replacement is newer still`() {
        val result = sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.3.0")))

        assertEquals(listOf(OLD), result)
    }

    @Test
    fun `keeps it when the replacement is not installed`() {
        // The case that matters most: deleting the retired plugin here leaves the user with no
        // panel at all for what it did.
        val result = sweep(installed = mapOf(OLD to entry(OLD)))

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
        assertTrue(announced.isEmpty(), "announced a removal that did not happen")
    }

    @Test
    fun `keeps it when the replacement predates the version that absorbed it`() {
        // An entry naming the replacement is not enough - that version does not have the
        // retired plugin's work in it yet, so both halves would be gone at once.
        val result = sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.16")))

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `keeps it when the replacement's version is unknown`() {
        // satisfiesVersionFloor answers TRUE for a blank version, by design: a gated update is
        // worse than an ungated one. Here the consequence runs the other way, so an unknown
        // version must read as "not ready" and wait for a launch that can prove otherwise.
        val result = sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = null)))

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `keeps it when the replacement has a row but no jar`() {
        // installPlugin records a DISABLED entry for a plugin it then rejected and deleted, so
        // an entry alone does not mean the replacement can run.
        val result =
            sweep(
                installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17")),
                jarExists = false,
            )

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `does nothing when the retired plugin was never installed`() {
        val result = sweep(installed = mapOf(NEW to entry(NEW, version = "1.2.17")))

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `the second launch is a no-op because the first removed the row`() {
        // Why this needs no "already done" flag: the sweep keys on the plugin being installed,
        // and PluginArtifactCleanup drops the installed.json row.
        val installed = mutableMapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17"))
        val hooks =
            RetiredPlugins.Hooks(
                installed = { installed[it] },
                jarExists = { true },
                remove = { id, _ ->
                    removed += id
                    installed.remove(id)
                },
                announce = { announced += it },
            )

        assertEquals(listOf(OLD), RetiredPlugins.sweep({ null }, { true }, listOf(retirement), hooks))
        assertEquals(emptyList(), RetiredPlugins.sweep({ null }, { true }, listOf(retirement), hooks))
        assertEquals(listOf(OLD), removed, "the second sweep removed it again")
    }

    @Test
    fun `keeps it when the replacement's version cannot be parsed`() {
        // satisfiesVersionFloor answers TRUE for anything SemanticVersion cannot read, by
        // design: for gating an update, ungated beats wrongly gated. Here the consequence runs
        // the other way, and these are the shapes a locally built or side-loaded jar actually
        // has. Without the explicit parse each of them deletes the user's only secrets panel.
        listOf("dev", "v1.2.17", "1.2.x", "1.0.0-", "1.0.0+", "latest").forEach { version ->
            removed.clear()
            announced.clear()

            val result = sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = version)))

            assertEquals(emptyList(), result, "retired against an unparseable version '$version'")
            assertTrue(removed.isEmpty(), "removed against an unparseable version '$version'")
        }
    }

    @Test
    fun `keeps it when the replacement is installed but disabled`() {
        // Two ways to get here, both real: the user disabled Secret Manager from the Toolbox, or
        // installPlugin recorded a DISABLED entry for a plugin hidden for lack of access. Both
        // leave the row and the jar in place, so without this the sweep deletes the retired
        // panel and the replacement is not running either.
        val result =
            sweep(installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17", enabled = false)))

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `keeps it when it would be restored at the next launch`() {
        // A bundled jar is re-copied at step 1 and a system plugin re-downloaded at step 2, both
        // before this sweep runs - so uninstalling one is a copy-then-delete loop on every
        // launch, with the notice firing each time. `ALL` is a list someone will append to
        // without reading the PR that added it.
        val result =
            sweep(
                installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17")),
                restoredAtNextLaunch = { "ships with BOSS and would be restored at the next launch" },
            )

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty())
        assertTrue(announced.isEmpty(), "announced a removal that did not happen")
    }

    @Test
    fun `a retirement whose removal throws does not stop the others`() {
        // sweep() logs and carries on: one entry failing must not drop the rest, or lose the ids
        // already removed - which the caller logs.
        val second =
            RetiredPlugins.Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.secondold",
                displayName = "Second Panel",
                replacementId = NEW,
                replacementDisplayName = "New Panel",
                minReplacementVersion = "1.2.17",
            )
        val installed =
            mapOf(
                OLD to entry(OLD),
                second.pluginId to entry(second.pluginId),
                NEW to entry(NEW, version = "1.2.17"),
            )

        val result =
            RetiredPlugins.sweep(
                restoredAtNextLaunch = { null },
                purgeArtifacts = { true },
                retirements = listOf(retirement, second),
                hooks =
                    RetiredPlugins.Hooks(
                        installed = { installed[it] },
                        jarExists = { true },
                        remove = { id, _ ->
                            if (id == OLD) error("disk is on fire") else removed += id
                        },
                        announce = { announced += it },
                    ),
            )

        assertEquals(listOf(second.pluginId), result)
        assertEquals(listOf(second.pluginId), removed)
    }

    @Test
    fun `two removals are announced in one message`() {
        // StatusMessageManager.showMessage cancels the previous message, so announcing per
        // retirement would show only the last - and the sweep is one-shot, so a missed notice
        // means the panel vanished with no explanation ever.
        val second =
            RetiredPlugins.Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.secondold",
                displayName = "Second Panel",
                replacementId = NEW,
                replacementDisplayName = "New Panel",
                minReplacementVersion = "1.2.17",
            )
        val installed =
            mapOf(
                OLD to entry(OLD),
                second.pluginId to entry(second.pluginId),
                NEW to entry(NEW, version = "1.2.17"),
            )

        RetiredPlugins.sweep(
            restoredAtNextLaunch = { null },
            purgeArtifacts = { true },
            retirements = listOf(retirement, second),
            hooks =
                RetiredPlugins.Hooks(
                    installed = { installed[it] },
                    jarExists = { true },
                    remove = { id, _ -> removed += id },
                    announce = { announced += it },
                ),
        )

        assertEquals(listOf("Old Panel and Second Panel are now part of New Panel"), announced)
    }

    @Test
    fun `a jar that cannot be removed leaves the row alone and announces nothing`() {
        // Dropping the row while a jar survives is worse than doing nothing: DefaultPlugin's
        // directory scan runs later in the same launch and installs any jar the manager does not
        // know about, so the panel returns in the session the user was told it left - and a fresh
        // row on load means the next launch sweeps and announces again, forever.
        val result =
            sweep(
                installed = mapOf(OLD to entry(OLD), NEW to entry(NEW, version = "1.2.17")),
                purgeArtifacts = { false },
            )

        assertEquals(emptyList(), result)
        assertTrue(removed.isEmpty(), "the row was dropped while the jar was still there")
        assertTrue(announced.isEmpty(), "announced a removal that did not happen")
    }

    @Test
    fun `two removals with different replacements are each attributed correctly`() {
        // noticeFor only exists for the multi-removal case, which is exactly the case where the
        // replacements can differ - and joining the names while taking the first replacement told
        // the user their panel moved somewhere it did not.
        val second =
            RetiredPlugins.Retirement(
                pluginId = "ai.rever.boss.plugin.dynamic.secondold",
                displayName = "Second Panel",
                replacementId = "ai.rever.boss.plugin.dynamic.othernew",
                replacementDisplayName = "Other Panel",
                minReplacementVersion = "2.0.0",
            )
        val installed =
            mapOf(
                OLD to entry(OLD),
                second.pluginId to entry(second.pluginId),
                NEW to entry(NEW, version = "1.2.17"),
                second.replacementId to entry(second.replacementId, version = "2.0.0"),
            )

        RetiredPlugins.sweep(
            restoredAtNextLaunch = { null },
            purgeArtifacts = { true },
            retirements = listOf(retirement, second),
            hooks =
                RetiredPlugins.Hooks(
                    installed = { installed[it] },
                    jarExists = { true },
                    remove = { id, _ -> removed += id },
                    announce = { announced += it },
                ),
        )

        assertEquals(
            listOf("Old Panel is now part of New Panel; Second Panel is now part of Other Panel"),
            announced,
        )
    }

    @Test
    fun `the shipped retirement names the plugin the wizard no longer installs`() {
        // Pins the pair rather than the mechanism: PluginListProvider dropped
        // `usersecretlist` and put `secretmanager` among the defaults in the same change, and
        // a retirement pointing somewhere else would silently uninstall the wrong plugin.
        val shipped = RetiredPlugins.ALL.single()
        assertEquals("ai.rever.boss.plugin.dynamic.usersecretlist", shipped.pluginId)
        assertEquals("ai.rever.boss.plugin.dynamic.secretmanager", shipped.replacementId)
        assertTrue(
            shipped.pluginId !in
                ai.rever.boss.components.wizard.plugin.PluginListProvider.DEFAULT_PLUGIN_IDS,
            "the wizard still installs a plugin this sweep uninstalls at the next launch",
        )
        assertTrue(
            shipped.replacementId in
                ai.rever.boss.components.wizard.plugin.PluginListProvider.DEFAULT_PLUGIN_IDS,
            "nothing takes the retired plugin's place in a fresh install",
        )
    }

    @Test
    fun `every retirement is also filtered out of what the store offers`() {
        // Two source sets, two lists: RetiredPlugins (desktopMain) uninstalls what is on disk,
        // RetiredPluginIds (commonMain) is what the wizard and the home grid refuse to offer. A
        // retirement added to one and not the other leaves the plugin installable, swept away at
        // the next launch, and installable again - which is the loop the filter exists to stop.
        assertEquals(
            RetiredPlugins.ALL.map { it.pluginId }.toSet(),
            ai.rever.boss.components.plugin.RetiredPluginIds.ALL,
        )
    }

    private fun sweep(
        installed: Map<String, PluginPersistence.InstalledPluginEntry>,
        jarExists: Boolean = true,
        restoredAtNextLaunch: (String) -> String? = { null },
        purgeArtifacts: (String) -> Boolean = { true },
        remove: (String, String) -> Unit = { id, _ -> removed += id },
    ): List<String> =
        RetiredPlugins.sweep(
            restoredAtNextLaunch = restoredAtNextLaunch,
            purgeArtifacts = purgeArtifacts,
            retirements = listOf(retirement),
            hooks =
                RetiredPlugins.Hooks(
                    installed = { installed[it] },
                    jarExists = { jarExists },
                    remove = remove,
                    announce = { announced += it },
                ),
        )

    private fun entry(
        pluginId: String,
        version: String? = "1.0.0",
        enabled: Boolean = true,
    ) = PluginPersistence.InstalledPluginEntry(
        pluginId = pluginId,
        jarPath = "/plugins/$pluginId.jar",
        enabled = enabled,
        installedVersion = version,
    )

    private companion object {
        const val OLD = "ai.rever.boss.plugin.dynamic.oldpanel"
        const val NEW = "ai.rever.boss.plugin.dynamic.newpanel"
    }
}
