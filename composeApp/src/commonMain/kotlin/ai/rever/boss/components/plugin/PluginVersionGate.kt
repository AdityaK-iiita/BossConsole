package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginApiLevelException
import ai.rever.boss.plugin.loader.PluginBossVersionException
import ai.rever.boss.utils.AppVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A plugin the host refused to load because of a version floor, and what would actually fix it.
 *
 * **Why this exists.** `DynamicPluginLoader` refuses a plugin whose `minBossVersion` or
 * `minApiVersion` exceeds what this build provides, which is correct - running it would fail at some
 * arbitrary call site instead. But the refusal only reached an ERROR line in the log, so from the
 * user's side a plugin simply stopped existing. That is bad for any plugin and worse for a
 * `systemPlugin`: fluck-browser is the browser tab, so the observable symptom of a version floor was
 * "my browser is gone", with the reason available only to someone reading `~/.boss/logs`.
 *
 * It happened for real. fluck-browser 1.2.22 shipped requiring BOSS 9.4.23 while 9.4.22 was the
 * current release, and every host that took the plugin update lost its browser tab:
 *
 * ```
 * PluginBossVersionException: Plugin requires BOSS version 9.4.23 or later,
 *                             but current version is 9.4.22
 * ```
 *
 * Recovering meant knowing that the jar had been overwritten in place, finding the previous release,
 * and putting it back by hand. None of that is reasonable to expect, and all of it is knowable from
 * the exception the loader already throws.
 *
 * This models the refusal and the ways out. It is deliberately pure - no store, no updater, no
 * files - so the question "what should we offer the user" is answerable in a test.
 */

/** Which floor the plugin failed, and the two versions involved. */
sealed interface PluginVersionGate {
    val pluginId: String
    val displayName: String

    /** What the plugin asks for. */
    val required: String

    /** What this build has. */
    val current: String

    /** The plugin needs a newer BOSS. Fixed by updating the app, or by going back a plugin version. */
    data class NeedsNewerHost(
        override val pluginId: String,
        override val displayName: String,
        override val required: String,
        override val current: String,
    ) : PluginVersionGate

    /**
     * The plugin needs a newer plugin API layer.
     *
     * Distinct from [NeedsNewerHost] because the fix is different and much cheaper: the api layer is
     * itself a plugin, hot-swappable without a restart, so this is resolvable in place. Conflating
     * the two would send a user to download a whole application update for something the store can
     * settle in seconds.
     */
    data class NeedsNewerApi(
        override val pluginId: String,
        override val displayName: String,
        override val required: String,
        override val current: String,
    ) : PluginVersionGate
}

/** Something the user can do about a [PluginVersionGate], with enough detail to label a button. */
sealed interface PluginVersionRemedy {
    /**
     * Update the application. Only offered when an update is actually available AND high enough -
     * "Update BOSS" that lands on a version still below the floor is worse than no button, because
     * the user pays for a restart and the plugin is still missing afterwards.
     */
    data class UpdateHost(
        val availableVersion: String,
    ) : PluginVersionRemedy

    /** Install a newer api plugin from the store. */
    data class UpdateApi(
        val availableVersion: String,
    ) : PluginVersionRemedy

    /**
     * Go back to the plugin version that was working.
     *
     * The remedy that always applies, because it does not depend on anything being published: the
     * jar this one replaced is kept aside precisely so there is a way back. Named with the version
     * so the button can say what it will do rather than "Downgrade".
     */
    data class RevertPlugin(
        val toVersion: String,
    ) : PluginVersionRemedy

    /**
     * Nothing can be offered, and the reason is worth showing.
     *
     * Reached when no update is published yet and no previous jar was kept - which is exactly the
     * position a user is in when a plugin's first release already overshoots their host. Saying so
     * beats an empty dialog or, worse, silence.
     */
    data class NothingAvailable(
        val reason: String,
    ) : PluginVersionRemedy
}

/**
 * What the host can offer for [gate], most useful first.
 *
 * @param hostUpdate the app version an update would install, or null when none is available.
 * @param apiUpdate the api-plugin version the store publishes, or null when it cannot be asked.
 * @param revertTo the plugin version kept aside from the last replacement, or null when none was.
 * @param satisfies whether a candidate version meets a required floor. Injected rather than
 *   reimplemented here: the loader already owns that comparison, and a second copy of version
 *   arithmetic is how the two drift into disagreeing about the same pair of numbers.
 */
fun remediesFor(
    gate: PluginVersionGate,
    hostUpdate: String?,
    apiUpdate: String?,
    revertTo: String?,
    satisfies: (required: String, candidate: String) -> Boolean,
): List<PluginVersionRemedy> {
    val remedies = mutableListOf<PluginVersionRemedy>()
    when (gate) {
        is PluginVersionGate.NeedsNewerHost -> {
            // Only when it would actually clear the floor. An update to 9.4.22 does not help a
            // plugin asking for 9.4.23, and offering it wastes a restart to end up here again.
            hostUpdate?.takeIf { satisfies(gate.required, it) }?.let {
                remedies += PluginVersionRemedy.UpdateHost(it)
            }
        }

        is PluginVersionGate.NeedsNewerApi -> {
            apiUpdate?.takeIf { satisfies(gate.required, it) }?.let {
                remedies += PluginVersionRemedy.UpdateApi(it)
            }
        }
    }
    // Always last, and always offered when it exists: it is the remedy that needs nothing published
    // and no restart, so it is the one that works when everything else is unavailable. Last rather
    // than first because going forward is better than going back when both are possible.
    revertTo?.let { remedies += PluginVersionRemedy.RevertPlugin(it) }

    if (remedies.isNotEmpty()) return remedies
    return listOf(
        PluginVersionRemedy.NothingAvailable(
            when (gate) {
                is PluginVersionGate.NeedsNewerHost -> {
                    "This needs BOSS ${gate.required}. You have ${gate.current}, " +
                        "no update is available yet, and no earlier version of the plugin was kept."
                }

                is PluginVersionGate.NeedsNewerApi -> {
                    "This needs plugin API ${gate.required}. You have ${gate.current}, " +
                        "the store has nothing newer, and no earlier version of the plugin was kept."
                }
            },
        ),
    )
}

/**
 * The version-floor refusals this session has seen, so something can offer a way out.
 *
 * A process-wide object rather than state on `DynamicPluginManager`, for the same reason
 * `PluginCrashRegistry` is one: the refusal happens during startup plugin loading, long before any
 * window exists to hold it, and the dialog that acts on it is mounted by whichever window opens
 * first. Passing it down would mean threading it through every construction path that can load a
 * plugin.
 *
 * Keyed by plugin id, so a plugin refused on every restart accumulates one entry rather than one
 * per attempt.
 */
object PluginVersionGateRegistry {
    private val _gates = MutableStateFlow<Map<String, PluginVersionGate>>(emptyMap())

    /** Refusals not yet dismissed or resolved, keyed by plugin id. */
    val gates: StateFlow<Map<String, PluginVersionGate>> = _gates.asStateFlow()

    fun record(gate: PluginVersionGate) {
        _gates.value = _gates.value + (gate.pluginId to gate)
    }

    /**
     * Forget the refusal for [pluginId].
     *
     * Called when a remedy has been applied AND when the user dismisses. Both, because a dialog
     * that reappears on every recomposition for a problem the user has decided to live with is
     * worse than the silence this replaced.
     */
    fun clear(pluginId: String) {
        if (_gates.value.containsKey(pluginId)) {
            _gates.value = _gates.value - pluginId
        }
    }

    /** For tests. Nothing in the app should need to drop every refusal at once. */
    internal fun reset() {
        _gates.value = emptyMap()
    }
}

/**
 * Translate a load failure into a [PluginVersionGate], or null when it is not a version floor.
 *
 * Null for everything else on purpose. A corrupt jar, a missing main class or a binary
 * incompatibility have no button that helps, and offering "Update BOSS" for them would send a user
 * through a download and a restart to arrive at the same failure.
 *
 * The display name falls back to the plugin id: the manifest is unavailable here, because the
 * refusal happens before the loader returns anything but the exception, and an id is a worse label
 * than a name but a far better one than nothing.
 */
internal fun versionGateFor(error: Throwable?): PluginVersionGate? =
    when (error) {
        is PluginBossVersionException -> {
            val id = error.pluginId
            val required = error.requiredVersion
            // Both, because a gate that cannot name what it needs cannot decide whether an
            // available update would clear the floor, and would offer one blind.
            if (id.isNullOrBlank() || required.isNullOrBlank()) {
                null
            } else {
                PluginVersionGate.NeedsNewerHost(
                    pluginId = id,
                    displayName = id,
                    required = required,
                    current = error.currentVersion ?: AppVersion.currentVersionString(),
                )
            }
        }

        is PluginApiLevelException -> {
            val id = error.pluginId
            val required = error.requiredVersion
            if (id.isNullOrBlank() || required.isNullOrBlank()) {
                null
            } else {
                PluginVersionGate.NeedsNewerApi(
                    pluginId = id,
                    displayName = id,
                    required = required,
                    // The api layer is a plugin, so "installed" is the right word and there is no
                    // build-time constant to fall back on the way there is for the app version.
                    current = error.installedVersion ?: "unknown",
                )
            }
        }

        else -> {
            null
        }
    }
