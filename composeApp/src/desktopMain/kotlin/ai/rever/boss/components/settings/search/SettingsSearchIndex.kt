package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.sidebar.SettingsSection

/**
 * One searchable thing in the Settings window.
 *
 * Identity is ([section], [group], [label]) and **never [label] alone**. Performance carries
 * "Warning Threshold" and "Critical Threshold" twice each - once under "Memory Thresholds" and once
 * under "CPU Thresholds" - so a label-only key would send the user to the wrong control half the
 * time and light up both when it got there.
 *
 * @param label the control's `label =`, or the group's `title =` for a group-header entry.
 * @param section the built-in section that owns it, or null when this is a plugin-contributed page.
 * @param group the enclosing `SettingsSection(title = ...)`, or null for a group header itself.
 * @param keywords words a user might search that the label does not contain ("passkey" for
 *   "Platform Authenticator"). Deliberately scored below a label hit - see [SettingsSearchMatcher].
 * @param pluginPageId set instead of [section] for a plugin page, which navigates by page id.
 * @param highlightable false when landing on the section is all this entry can do - either the page
 *   belongs to another module, or the control is built from a local composable that carries no
 *   search target (the Shortcuts tab-switching chips live in commonMain, which cannot reach the
 *   desktop-only highlight modifier at all).
 * @param curated true when this entry was written by hand rather than read off a `label =` literal,
 *   so the drift test's staleness check must skip it - there is no source line for it to find. Only
 *   for section-level catch-alls and for sections the scanner cannot see.
 * @param context a heading the control sits under on screen that is not a `SettingsSection`, used
 *   for the breadcrumb only. Shortcuts groups its tab-switching chips under a plain `Text`, so
 *   [group] must stay null to match what the scanner computes - without this, the result row reads
 *   "Positional / Shortcuts", which tells the reader nothing about what it does.
 */
data class SettingsSearchEntry(
    val label: String,
    val section: SettingsSection? = null,
    val group: String? = null,
    val keywords: List<String> = emptyList(),
    val pluginPageId: String? = null,
    val highlightable: Boolean = true,
    val curated: Boolean = false,
    val context: String? = null,
) {
    init {
        require(section != null || pluginPageId != null) {
            "a search entry must name either a built-in section or a plugin page: $label"
        }
    }

    /** "Browser > User Agent", or just "Browser" for a group header. Shown under the result. */
    val breadcrumb: String
        get() =
            listOfNotNull(section?.displayName ?: PLUGIN_BREADCRUMB, group ?: context)
                .joinToString(" > ")

    /**
     * Stable identity for a `LazyColumn` key.
     *
     * Spelled out rather than left to the data class's own `hashCode`, because a list key has to
     * stay stable across recompositions and a collision silently reuses the wrong row's state. It
     * is the same triple the highlight matches on, so if two entries ever collide here they would
     * also have highlighted each other.
     */
    val resultKey: String
        get() = "${section?.name ?: pluginPageId}|${group.orEmpty()}|$label"

    companion object {
        const val PLUGIN_BREADCRUMB = "Plugins"
    }
}

/**
 * Everything the Settings window can be searched for.
 *
 * Hand-declared rather than harvested, and held honest by `SettingsSearchIndexDriftTest`, which
 * scans `settings/sections/` in both directions: it fails when a section gains a label this file
 * does not list, and again when this file names a label the sources no longer contain. The second
 * catches the worse failure, which is what a rename produces - a result that still appears, still
 * navigates, and then highlights nothing.
 *
 * Plugin pages are deliberately absent. They are merged in at query time from
 * `SettingsPageRegistryImpl.visiblePages()`, which is the only way to respect RBAC and plugin
 * lifecycle - see [pluginPageEntry].
 */
object SettingsSearchIndex {
    /**
     * Every built-in entry, declared one section at a time in `SettingsSearchEntries.kt`.
     *
     * The declarations live in a sibling file so that this one stays the model and the contract,
     * and a diff that adds a setting touches only the data.
     */
    val builtIn: List<SettingsSearchEntry> get() = builtInEntries
}

/** Builds a plugin-page entry. Plugins supply only a name and a description, so that is all we index. */
fun pluginPageEntry(
    pageId: String,
    displayName: String,
    description: String,
): SettingsSearchEntry =
    SettingsSearchEntry(
        label = displayName,
        pluginPageId = pageId,
        // Lowercased and de-punctuated to match the convention the built-in keywords follow, and
        // short words dropped: a description is prose, so "the" and "and" would match everything.
        keywords =
            description
                .split(" ")
                .map { it.lowercase().trim(',', '.', '(', ')') }
                .filter { it.length > 3 },
        highlightable = false,
    )
