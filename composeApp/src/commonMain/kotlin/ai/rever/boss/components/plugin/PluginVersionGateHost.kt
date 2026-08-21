package ai.rever.boss.components.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Shows a [PluginVersionGateDialog] for the first unresolved version-floor refusal, if any.
 *
 * Self-contained: it reads [PluginVersionGateRegistry] rather than taking state from the caller, so
 * mounting it is one line and no state class grows a field. That matches where the refusal comes
 * from - startup plugin loading, before any window exists - and means whichever window opens first
 * asks the question.
 *
 * **One at a time.** A version bump can refuse several plugins at once (a whole batch declaring the
 * same new floor), and stacking dialogs would be unusable. Resolving or dismissing the first brings
 * up the next.
 *
 * @param remedyResolver injected so the remedy inputs - an app update, an api version, a kept jar -
 *   can be supplied by the desktop layer that can reach them, and stubbed in a test.
 */
@Composable
fun PluginVersionGateHost(
    manager: DynamicPluginManager?,
    remedyResolver: PluginVersionRemedyResolver?,
) {
    val gates by PluginVersionGateRegistry.gates.collectAsState()
    val gate = gates.values.firstOrNull()
    // One guard for all three absences. A refusal with no manager or no resolver is not renderable
    // and stays in the registry until something can act on it, so this is a plain absence rather
    // than an error path. Parameters are vals, so everything below reads them non-null.
    if (gate == null || manager == null || remedyResolver == null) return

    val scope = rememberCoroutineScope()
    var busy by remember(gate.pluginId) { mutableStateOf(false) }
    var error by remember(gate.pluginId) { mutableStateOf<String?>(null) }

    // Resolved asynchronously because one of the three inputs is a store request. Until it arrives
    // the dialog is not shown at all rather than shown with no buttons: an empty dialog that fills
    // in a moment later reads as a bug, and there is no hurry - the plugin has already failed.
    val remedies by
        produceState<List<PluginVersionRemedy>?>(initialValue = null, gate) {
            value = remedyResolver.resolve(gate)
        }
    val resolved = remedies ?: return

    PluginVersionGateDialog(
        gate = gate,
        remedies = resolved,
        busy = busy,
        error = error,
        onDismiss = {
            // Dismissal forgets the refusal for this session. A dialog that comes back on every
            // recomposition for a problem the user has decided to live with would be worse than
            // the silence it replaced; the refusal is recorded again on the next launch, which is
            // the right cadence for something this consequential.
            PluginVersionGateRegistry.clear(gate.pluginId)
        },
        onApply = { remedy ->
            busy = true
            error = null
            scope.launch {
                try {
                    // runCatching, not a catch: a throw rather than a failed Result would leave
                    // the dialog open with no message and live buttons, looking like the click did
                    // nothing.
                    runCatching { remedyResolver.apply(gate, remedy, manager) }
                        .getOrElse { Result.failure(it) }
                        .onFailure { e ->
                            error = e.message ?: "Could not apply that fix."
                        }
                } finally {
                    // Always: `busy` disables every button and blocks dismissal, so leaving it set
                    // would produce a modal escapable only by closing the window.
                    busy = false
                }
            }
        },
    )
}

/**
 * Supplies and performs the remedies for a gate.
 *
 * An interface rather than a direct call into the desktop implementation, because
 * [PluginVersionGateHost] is `commonMain` and the pieces a remedy needs - the app updater, the
 * plugin store, the plugins directory - are all `desktopMain`. It also makes the host testable
 * without any of them.
 */
interface PluginVersionRemedyResolver {
    suspend fun resolve(gate: PluginVersionGate): List<PluginVersionRemedy>

    suspend fun apply(
        gate: PluginVersionGate,
        remedy: PluginVersionRemedy,
        manager: DynamicPluginManager,
    ): Result<String>
}

/**
 * Holds the desktop [PluginVersionRemedyResolver] for the `commonMain` host composable.
 *
 * The same shape as `BrokeredCredentialAccess`, and for the same reason: the implementation speaks
 * to the app updater, the plugin store and the filesystem, all of which live in `desktopMain`,
 * while the dialog that uses it is mounted from `commonMain`. Populated once at startup.
 *
 * Null until then, and the host renders nothing - which is correct rather than merely tolerable: a
 * refusal recorded before this is set has nothing that could act on it, and it stays in the
 * registry until something can.
 */
object PluginVersionRemedyAccess {
    @Volatile
    private var resolver: PluginVersionRemedyResolver? = null

    /** Called once from desktop startup. */
    fun initialize(implementation: PluginVersionRemedyResolver) {
        resolver = implementation
    }

    fun current(): PluginVersionRemedyResolver? = resolver
}
