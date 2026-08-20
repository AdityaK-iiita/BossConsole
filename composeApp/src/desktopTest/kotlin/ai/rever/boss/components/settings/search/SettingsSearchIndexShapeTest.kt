package ai.rever.boss.components.settings.search

import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.displayName
import ai.rever.boss.components.settings.sidebar.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Properties of the index that hold regardless of what the sources currently say. */
class SettingsSearchIndexShapeTest {
    /**
     * The guard the whole (section, group, label) identity exists for.
     *
     * Performance declares "Warning Threshold" and "Critical Threshold" under both "Memory
     * Thresholds" and "CPU Thresholds", and Browser Engine declares "Rendering mode" under both
     * "Rendering" and "Effective Chromium command line". Had any of those been written without its
     * group, two entries would collapse to one key here - and in the window, one search result
     * would scroll to one row while lighting up two.
     */
    @Test
    fun `no two entries share a result key`() {
        val duplicates =
            SettingsSearchIndex.builtIn
                .groupBy { it.resultKey }
                .filterValues { it.size > 1 }

        if (duplicates.isNotEmpty()) {
            fail(
                "These search entries collide on (section, group, label), so they cannot be told " +
                    "apart when navigating or highlighting: ${duplicates.keys.sorted()}",
            )
        }
    }

    /** A new section must not be silently unsearchable. Sections with no controls still get one entry. */
    @Test
    fun `every section appears in the index`() {
        val covered = SettingsSearchIndex.builtIn.mapNotNull { it.section }.toSet()
        val missing = SettingsSection.entries.toSet() - covered

        assertTrue(
            missing.isEmpty(),
            "These settings sections cannot be found by search at all: ${missing.map { it.name }.sorted()}. " +
                "Add entries for their controls, or a `delegated(...)` line if the host does not own the page.",
        )
    }

    /**
     * Recomputes the templated family rather than restating it.
     *
     * `WindowAppearanceSettings` builds these labels with `"Show ${bar.displayName()}"` over
     * `ChromeBar.entries`, so the index holds four literals that no source file contains verbatim -
     * which is why the drift test has to exempt them. Adding a fifth bar has to fail somewhere, and
     * this is that somewhere.
     */
    @Test
    fun `the Bars group lists exactly one entry per ChromeBar`() {
        val expected = ChromeBar.entries.map { "Show ${it.displayName()}" }.sorted()
        val actual =
            SettingsSearchIndex.builtIn
                .filter { it.section == SettingsSection.WINDOW_APPEARANCE && it.group == "Bars" }
                .map { it.label }
                .filter { it.startsWith("Show ") }
                .sorted()

        assertEquals(expected, actual, "the indexed Bars toggles have drifted from ChromeBar.entries")
    }

    /**
     * The four sections whose pages belong to other modules own no host controls, so they must stay
     * section-level. A hand-added BossTerm label here would produce a result that navigates to the
     * Terminal page and then highlights nothing, because no host control carries it.
     */
    @Test
    fun `delegated sections carry a single non-highlightable entry`() {
        val delegated =
            setOf(
                SettingsSection.TERMINAL,
                SettingsSection.BOSS_EDITOR,
                SettingsSection.LANGUAGE_SERVERS,
                SettingsSection.LLM_PROVIDERS,
            )

        delegated.forEach { section ->
            val entries = SettingsSearchIndex.builtIn.filter { it.section == section }
            assertEquals(1, entries.size, "${section.name} should have exactly one section-level entry")
            assertTrue(
                entries.single().highlightable.not(),
                "${section.name} is rendered by another module, so its entry must not claim a control to highlight",
            )
        }
    }

    /**
     * Shortcuts is host code, not a delegated panel, and must carry its real controls.
     *
     * It was filed as delegated at first. That single mis-classification made "tab switching" find
     * nothing AND took the section out of the drift guard, so no test objected. Pinning the actual
     * controls is what stops it silently reverting to a keywords-only stub.
     */
    @Test
    fun `Shortcuts indexes its tab-switching controls`() {
        val labels = SettingsSearchIndex.builtIn.filter { it.section == SettingsSection.KEYMAP }.map { it.label }

        assertTrue("Positional" in labels, "the tab-switching chips are missing from the index: $labels")
        assertTrue("Most recently used" in labels, "the tab-switching chips are missing from the index: $labels")
    }

    /**
     * A curated entry has no `label =` line behind it, so the staleness check has to skip it. Keeping
     * that set small matters: every curated entry is one the drift guard cannot verify.
     */
    @Test
    fun `only section-level catch-alls are curated`() {
        val curated = SettingsSearchIndex.builtIn.filter { it.curated }
        val offenders = curated.filter { it.label != it.section?.displayName || it.group != null }

        assertTrue(
            offenders.isEmpty(),
            "these entries opt out of the staleness check without being section-level catch-alls: " +
                offenders.map { it.resultKey },
        )
    }

    @Test
    fun `keywords are lowercase and never repeat the label`() {
        SettingsSearchIndex.builtIn.forEach { entry ->
            entry.keywords.forEach { keyword ->
                assertEquals(keyword.lowercase(), keyword, "keyword '$keyword' on '${entry.label}' is not lowercase")
                assertTrue(keyword.isNotBlank(), "blank keyword on '${entry.label}'")
                assertTrue(
                    !keyword.equals(entry.label, ignoreCase = true),
                    "keyword '$keyword' just repeats the label '${entry.label}'",
                )
            }
        }
    }

    /** A plugin page is navigable by id and has nothing the host could scroll to. */
    @Test
    fun `a plugin page entry targets its page and claims no control`() {
        val entry = pluginPageEntry("jupyter-notebook", "Jupyter", "Kernel and notebook settings")

        assertEquals("jupyter-notebook", entry.pluginPageId)
        assertEquals(null, entry.section)
        assertTrue(entry.highlightable.not())
        assertEquals("Plugins", entry.breadcrumb)
    }
}
