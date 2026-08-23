package ai.rever.boss.components.plugin

/**
 * Plugins whose job another plugin has taken over, and which must therefore never be *offered*
 * for install again.
 *
 * Separate from `RetiredPlugins` (desktopMain), which owns the uninstall of one already on disk.
 * The ids live here because the surfaces that offer plugins are split across source sets: the
 * first-run wizard is desktopMain, the home screen's tool grid is commonMain, and both filter
 * `PluginDependencyResolution.NOT_USER_INSTALLABLE` in the same spirit.
 *
 * **Why the host filters at all, when the fix is a database row.** Unlisting the store row is a
 * manual action outside this repo and outside CI. Until it happens - and it may never happen on a
 * self-hosted store - the store still returns the plugin, so a user can install it, have it swept
 * away with a notice at the next launch, install it again, and so on. Filtering here makes the
 * retirement hold regardless of what the store says, which is what stops it being a one-way door
 * in the wrong direction.
 *
 * `RetiredPluginsTest` pins this against `RetiredPlugins.ALL`, so a retirement added there
 * without a matching entry here fails rather than quietly staying installable.
 */
object RetiredPluginIds {
    val ALL: Set<String> = setOf("ai.rever.boss.plugin.dynamic.usersecretlist")
}
