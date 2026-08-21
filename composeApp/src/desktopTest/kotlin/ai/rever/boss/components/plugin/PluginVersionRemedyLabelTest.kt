package ai.rever.boss.components.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The button labels, and the version comparison behind which buttons appear at all.
 *
 * Every label names a version on purpose. "Downgrade" does not say what you get, and "Update BOSS"
 * does not say whether it would even help - which is the distinction [remediesFor] exists to make.
 */
class PluginVersionRemedyLabelTest {
    @Test
    fun `every label names a version`() {
        assertEquals("Update BOSS to 9.4.23", remedyLabel(PluginVersionRemedy.UpdateHost("9.4.23")))
        assertEquals("Update the plugin API to 1.0.83", remedyLabel(PluginVersionRemedy.UpdateApi("1.0.83")))
        assertEquals("Go back to version 1.2.21", remedyLabel(PluginVersionRemedy.RevertPlugin("1.2.21")))
    }

    @Test
    fun `the nothing-available label is its own explanation`() {
        val reason = "This needs BOSS 9.9.9."
        assertEquals(reason, remedyLabel(PluginVersionRemedy.NothingAvailable(reason)))
    }

    @Test
    fun `the recovery satisfies check matches the loader`() {
        // `Version.parse` plus `>=`, which is what DynamicPluginLoader.isBossVersionCompatible does.
        // Equality has to pass: 9.4.23 is what a plugin requiring 9.4.23 was built for.
        assertTrue(PluginVersionGateRecovery.satisfies("9.4.23", "9.4.23"))
        assertTrue(PluginVersionGateRecovery.satisfies("9.4.23", "9.4.24"))
        assertTrue(PluginVersionGateRecovery.satisfies("9.4.23", "10.0.0"))
        assertTrue(!PluginVersionGateRecovery.satisfies("9.4.23", "9.4.22"))
        assertTrue(!PluginVersionGateRecovery.satisfies("9.5.0", "9.4.23"))
    }

    @Test
    fun `an unparseable version fails open, as the loader does`() {
        // Not leniency for its own sake: the loader lets a plugin through when it cannot parse a
        // floor, so a remedy that refused here would hide a button that actually works.
        assertTrue(PluginVersionGateRecovery.satisfies("not-a-version", "9.4.22"))
        assertTrue(PluginVersionGateRecovery.satisfies("9.4.23", "not-a-version"))
    }

    @Test
    fun `a host update below the floor is not offered`() {
        // The distinction the whole dialog turns on. An update that lands below the floor costs a
        // download and a restart to arrive back at this same dialog.
        val gate = PluginVersionGate.NeedsNewerHost("com.example.plugin", "Example", "9.4.23", "9.4.22")
        val remedies =
            remediesFor(
                gate = gate,
                hostUpdate = "9.4.22",
                apiUpdate = null,
                revertTo = null,
                satisfies = PluginVersionGateRecovery::satisfies,
            )
        assertTrue(
            remedies.single() is PluginVersionRemedy.NothingAvailable,
            "offered an update that would not clear the floor: $remedies",
        )
    }

    @Test
    fun `a host update at the floor is offered first`() {
        val gate = PluginVersionGate.NeedsNewerHost("com.example.plugin", "Example", "9.4.23", "9.4.22")
        val remedies =
            remediesFor(
                gate = gate,
                hostUpdate = "9.4.23",
                apiUpdate = null,
                revertTo = "1.2.21",
                satisfies = PluginVersionGateRecovery::satisfies,
            )
        assertEquals(
            listOf(
                PluginVersionRemedy.UpdateHost("9.4.23"),
                PluginVersionRemedy.RevertPlugin("1.2.21"),
            ),
            remedies,
            "going forward should be offered before going back",
        )
    }

    @Test
    fun `reverting is offered when nothing is published`() {
        // The remedy that needs no download and no restart, so it is what works when everything
        // else is unavailable - which is the position the fluck-browser 1.2.22 incident put people
        // in for the hours before 9.4.23 existed.
        val gate = PluginVersionGate.NeedsNewerHost("com.example.plugin", "Example", "9.4.23", "9.4.22")
        val remedies =
            remediesFor(
                gate = gate,
                hostUpdate = null,
                apiUpdate = null,
                revertTo = "1.2.21",
                satisfies = PluginVersionGateRecovery::satisfies,
            )
        assertEquals(listOf(PluginVersionRemedy.RevertPlugin("1.2.21")), remedies)
    }

    @Test
    fun `an api gate is not offered an app update`() {
        // An app release for a hot-swappable jar would be the wrong fix even when one exists.
        val gate = PluginVersionGate.NeedsNewerApi("com.example.plugin", "Example", "1.0.83", "1.0.80")
        val remedies =
            remediesFor(
                gate = gate,
                hostUpdate = "9.9.9",
                apiUpdate = "1.0.83",
                revertTo = null,
                satisfies = PluginVersionGateRecovery::satisfies,
            )
        assertEquals(listOf(PluginVersionRemedy.UpdateApi("1.0.83")), remedies)
    }
}
