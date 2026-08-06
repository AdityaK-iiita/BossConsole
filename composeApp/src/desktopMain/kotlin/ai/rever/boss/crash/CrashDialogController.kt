package ai.rever.boss.crash

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** What actually happened when the crash dialog closed. */
sealed interface CrashOutcome {
    /** The offending plugin was taken out; the app kept running. */
    data class Recovered(
        val pluginId: String,
    ) : CrashOutcome

    /** The process was ended (fatal host crash, or recovery could not be done). */
    data object Terminated : CrashOutcome
}

/**
 * The crash dialog's exits, in one place.
 *
 * There are three ways out of that window — the button, Escape, and the title
 * bar's close box — and they used to disagree. Escape and the button both
 * terminated the process; the close box merely disposed the frame, leaving the
 * app running with a crash that had been reported to nobody. That divergence is
 * only possible while each route carries its own copy of the logic, so all three
 * are wired to [dismiss] here and the divergence has nowhere to live.
 *
 * A successful submission ends the same way: submitting a report is not a reason
 * to lose your session over a plugin's bug.
 *
 * @param disposition decided once, at the point the dialog is built, so every
 *   exit agrees on whether this crash is survivable.
 * @param disposeWindow tears the crash window down. Always runs before the
 *   outcome is resolved: terminating leaves no chance to, and recovering must not
 *   leave a dead dialog floating over a working app.
 * @param resolve injected for tests, which need to observe termination without
 *   ending the test JVM.
 */
internal class CrashDialogController(
    private val disposition: CrashDisposition,
    private val error: Throwable,
    private val disposeWindow: () -> Unit,
    private val resolve: (CrashDisposition, Throwable) -> CrashOutcome = CrashHandler::resolveCrash,
) {
    private val logger = BossLogger.forComponent("CrashHandler")

    /** Shared by "Don't Send" / "Continue Without Plugin", Escape, and the close box. */
    fun dismiss(): CrashOutcome {
        logger.info(
            LogCategory.SYSTEM,
            "User dismissed crash report without submitting",
            mapOf("disposition" to dispositionLabel()),
        )
        return finish()
    }

    /** Called after the report was accepted by GitHub. */
    fun submit(
        userNotes: String?,
        includeLogs: Boolean,
    ): CrashOutcome {
        logger.info(
            LogCategory.SYSTEM,
            "Crash report submitted",
            mapOf(
                "hasNotes" to (userNotes != null),
                "includedLogs" to includeLogs,
                "disposition" to dispositionLabel(),
            ),
        )
        return finish()
    }

    /**
     * The window's close box, wired to the same [dismiss] the visible button runs.
     *
     * Returned as an adapter rather than wired inline so a test can invoke the
     * production listener directly — constructing a `WindowAdapter` needs no
     * display, and the event argument is unused.
     */
    fun windowClosingAdapter(): WindowAdapter =
        object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                dismiss()
            }
        }

    private fun finish(): CrashOutcome {
        disposeWindow()
        return resolve(disposition, error)
    }

    private fun dispositionLabel(): String =
        when (disposition) {
            is CrashDisposition.RecoverablePlugin -> "recoverable:${disposition.pluginId}"
            CrashDisposition.FatalHost -> "fatal"
        }
}
