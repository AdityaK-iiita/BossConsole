package ai.rever.boss.process

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which registered processes the global monitor attaches health supervision to.
 *
 * Plugin children are in the registry so the kernel's shutdown hook can reap them on exit,
 * but their health belongs to `PluginProcessMonitor` on the host side. If this monitor
 * supervised them too, a plugin the operator disables would exit on purpose, read as a crash
 * here, and come back through the kernel's respawn path behind the operator's back.
 */
class ProcessMonitorSupervisionTest {
    /** A [Process] whose liveness the test controls. */
    private class FakeProcess(
        private val pidValue: Long,
    ) : Process() {
        private var alive = true
        private var exit = 0

        fun die(exitCode: Int) {
            exit = exitCode
            alive = false
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = !alive

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else exit

        override fun destroy() = die(143)

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pidValue
    }

    private fun managed(
        id: String,
        type: ProcessType,
        process: Process,
    ) = ManagedProcess(
        config =
            ProcessConfig(
                processId = id,
                processType = type,
                displayName = id,
                mainClass = "Main",
                heartbeatIntervalMs = 100,
            ),
        process = process,
        ipcAddress = "unix:///tmp/$id",
    )

    /**
     * Run the global monitor over a single registered process of [type], kill it, and return
     * every failure the monitor reported.
     */
    private fun failuresAfterDeath(type: ProcessType): List<ProcessFailure> {
        val seen = mutableListOf<ProcessFailure>()
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            val proc = FakeProcess(4242)
            registry.register("p1", managed("p1", type, proc))

            monitor.startGlobalMonitor(checkIntervalMs = 10)
            advanceTimeBy(50)
            proc.die(exitCode = 9)
            advanceTimeBy(500)
        }
        return seen
    }

    @Test
    fun `a dead service is reported as a failure`() {
        val failures = failuresAfterDeath(ProcessType.SERVICE)

        // Reported repeatedly, not once: monitorProcess breaks out on death but leaves the
        // registry entry behind, so the global monitor re-attaches on its next tick and reports
        // again. In the real kernel the failure handler respawns or unregisters, which ends it.
        // This test pins that a service death is seen at all, not how many times.
        assertTrue(failures.isNotEmpty(), "a service death must reach the kernel's failure path")
        assertTrue(
            failures.all { it.processId == "p1" && it.exitCode == 9 && it.reason == FailureReason.PROCESS_EXIT },
            "unexpected failure contents: $failures",
        )
    }

    @Test
    fun `a dead plugin is not reported - PluginProcessMonitor owns plugin health`() {
        assertEquals(
            emptyList(),
            failuresAfterDeath(ProcessType.PLUGIN),
            "a plugin exit must not reach the kernel's respawn path",
        )
    }

    @Test
    fun `plugins stay in the registry so the shutdown hook can still reap them`() {
        val registry = ProcessRegistry()
        val proc = FakeProcess(6262)
        registry.register("plugin-y", managed("plugin-y", ProcessType.PLUGIN, proc))

        // Exactly what the kernel's JVM shutdown hook iterates.
        val reapable = registry.getAllProcesses()
        assertEquals(listOf("plugin-y"), reapable.map { it.config.processId })

        assertTrue(proc.isAlive)
        reapable.forEach { it.destroy() }
        assertFalse(proc.isAlive, "the hook's destroy must reach a registered plugin child")
    }
}
