package ai.rever.boss.components.settings.sections

/**
 * The plugins that serve a settings section.
 *
 * One place, because each id is asked twice for different things - which plugin's absence the
 * section explains, and which plugin the Install button downloads - and a drift between the two
 * would have a section describe one plugin while offering to install another, with nothing
 * failing. Two sections already share the editor id for the same reason.
 *
 * These ids also appear in `SystemPluginManifestService` and the first-run wizard's
 * `PluginListProvider`. Those copies predate this and answer different questions (what to bundle,
 * what to offer at first run); folding all of them into one holder is worth doing and is a wider
 * change than this one.
 */
internal object SettingsPluginIds {
    /** Owns AI provider settings, and the credential vault they are stored in. */
    const val SECRET_MANAGER = "ai.rever.boss.plugin.dynamic.secretmanager"

    /** Bundles BossEditor, and serves both the editor and language-server sections. */
    const val EDITOR_TAB = "ai.rever.boss.plugin.dynamic.editortab"
}
