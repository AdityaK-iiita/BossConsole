package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the paths that move files and unload a running plugin.
 *
 * These are the ones worth testing rather than reasoning about: each of them can leave the user with
 * no working plugin, and two of them were found by review rather than by running the app.
 */
class StoreVersionInstallerTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val VERSION = "1.0.4"
        const val EXPECTED_NAME = "ai_rever_boss_plugin_dynamic_probe_1.0.4.jar"
    }

    private val loaded = mutableListOf<String>()
    private val unloaded = mutableListOf<String>()
    private val discarded = mutableListOf<String>()
    private val persisted = mutableListOf<Triple<String, String, String?>>()

    /** Writes the "downloaded" bytes wherever the installer asks, like the real repository does. */
    private class FakeRepository(
        private val onDownload: (String) -> Unit = {},
    ) : PluginRepository {
        override val id = "store"
        override val name = "Store"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> = error("unused")

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = Result.success(null)

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = listPlugins()

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> {
            File(targetPath).writeText("store bytes")
            onDownload(targetPath)
            return Result.success(targetPath)
        }

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }

    private fun installer(
        readManifestId: String? = PLUGIN,
        promoteThrows: Boolean = false,
    ) = StoreVersionInstaller(
        pluginDir = { dir },
        hooks =
            StoreVersionHooks(
                readManifest = { path ->
                    readManifestId?.let { id ->
                        PluginManifest(
                            pluginId = id,
                            displayName = "Probe",
                            version = VERSION,
                            apiVersion = "1.0.0",
                            mainClass = "com.example.Main",
                        ).takeIf { File(path).exists() }
                    }
                },
                promoteFiles = { downloaded, target ->
                    if (promoteThrows) error("move failed")
                    target.writeText(File(downloaded).readText())
                    File(downloaded).delete()
                },
                discardFiles = { path ->
                    discarded += path
                    File(path).delete()
                },
                exists = { File(it).isFile },
                persist = { id, jarPath, version, sourceUrl -> persisted += Triple(id, version, sourceUrl) },
            ),
    )

    private suspend fun StoreVersionInstaller.run(
        repository: PluginRepository = FakeRepository(),
        runningJarPath: String? = null,
        loadSucceeds: Boolean = true,
    ) = install(
        store = repository,
        request =
            StoreVersionRequest(
                pluginId = PLUGIN,
                version = VERSION,
                sourceUrl = "https://store.example/probe.jar",
                runningJarPath = runningJarPath,
            ),
        unload = { id ->
            unloaded += id
            Result.success(Unit)
        },
        load = { path ->
            loaded += path
            // The restore of a previous build must be allowed to succeed even when the new one fails.
            if (!loadSucceeds && path != runningJarPath) Result.success(false) else Result.success(true)
        },
    )

    @Test
    fun `a clean swap downloads, unloads, loads and records the store source`() =
        runTest {
            val result = installer().run()

            assertTrue(result.isSuccess)
            assertEquals(listOf(PLUGIN), unloaded)
            assertEquals(1, loaded.size)
            assertTrue(File(dir, EXPECTED_NAME).isFile)
            // sourceUrl is what stops an unsigned store download later reading as a local build.
            assertEquals(listOf(Triple(PLUGIN, VERSION, "https://store.example/probe.jar")), persisted.toList())
        }

    @Test
    fun `the target is never the jar that is currently loaded`() =
        runTest {
            // Reachable in one sitting: install the store version, hot reload over it, click again.
            // The old code promoted onto this path BEFORE unloading, so it moved onto a jar held open
            // by a live classloader and then discarded the running plugin's own file on failure.
            val running = File(dir, EXPECTED_NAME).apply { writeText("the build that is running") }

            val result = installer().run(runningJarPath = running.absolutePath)

            assertTrue(result.isSuccess)
            assertEquals("the build that is running", running.readText(), "the running jar must be untouched")
            assertTrue(loaded.single() != running.absolutePath, "a distinct file must have been loaded")
            assertTrue(File(loaded.single()).isFile)
        }

    @Test
    fun `a failed load puts the previous build back`() =
        runTest {
            val running = File(dir, "probe-old.jar").apply { writeText("previous build") }

            val result = installer().run(runningJarPath = running.absolutePath, loadSucceeds = false)

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("Kept the build you were running") == true,
                "the user should be told their plugin survived: ${result.exceptionOrNull()?.message}",
            )
            // The downloaded jar is gone, so the next directory scan cannot load what just failed.
            assertFalse(File(dir, EXPECTED_NAME).isFile)
            assertTrue(discarded.any { it.endsWith(EXPECTED_NAME) })
            // And the previous build was loaded again.
            assertEquals(running.absolutePath, loaded.last())
        }

    @Test
    fun `a failed load with nothing to restore says so plainly`() =
        runTest {
            val result = installer().run(runningJarPath = null, loadSucceeds = false)

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("could not be restored") == true,
                "message was: ${result.exceptionOrNull()?.message}",
            )
        }

    @Test
    fun `a jar declaring a different plugin is refused before anything is unloaded`() =
        runTest {
            val result = installer(readManifestId = "some.other.plugin").run()

            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), unloaded, "nothing may be unloaded on the strength of a bad jar")
            assertEquals(emptyList<String>(), loaded)
            assertTrue(discarded.any { it.endsWith(EXPECTED_NAME) })
        }

    @Test
    fun `a half-promoted download leaves nothing behind`() =
        runTest {
            val result = installer(promoteThrows = true).run()

            assertTrue(result.isFailure)
            assertEquals(emptyList<String>(), unloaded)
            // Both the part file and the target are discarded: the move may already have succeeded
            // when the sidecar step threw, and a jar at a scannable name would be loaded next launch.
            assertTrue(discarded.any { it.endsWith(".part") })
            assertTrue(discarded.any { it.endsWith(EXPECTED_NAME) })
        }

    @Test
    fun `the download never streams onto the target name`() =
        runTest {
            // outputStream() truncates on open, so downloading straight to the final name would
            // destroy whatever is there the moment the connection opens.
            var streamedTo: String? = null
            val result = installer().run(repository = FakeRepository { streamedTo = it })

            assertTrue(result.isSuccess)
            assertTrue(streamedTo!!.endsWith(".part"), "streamed to $streamedTo")
        }
}
