package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException

private val logger = BossLogger.forComponent("WindowsProtocolHandler")
private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

/** Root of the per-user protocol registration this file owns. */
private const val PROTOCOL_KEY = """HKEY_CURRENT_USER\Software\Classes\boss"""

/**
 * Windows-specific protocol handler for registering URL schemes.
 *
 * Registration happens at runtime rather than in the MSI (see
 * [docs/WINDOWS_DEEP_LINK_SETUP.md]), which means an uninstall leaves the `boss:`
 * handler in the registry pointing at a deleted executable. [unregisterProtocol] is the
 * cleanup hook for that — reachable as `BOSS.exe --unregister-protocol` (see
 * [unregisterProtocolExitCode]) so an uninstall action, or support instructions, can
 * call it.
 */
object WindowsProtocolHandler {
    /**
     * Outcome of [unregisterProtocol]. Distinguishes "there is nothing left to clean"
     * from "I deliberately did not touch it" — deleting a registration we merely could
     * not parse would break a working install.
     */
    enum class UnregisterOutcome {
        /** The registration was ours (or pointed at a deleted exe) and was removed. */
        REMOVED,

        /** Nothing was registered. */
        ABSENT,

        /** Not a Windows host — there is no registry registration to remove. */
        NOT_APPLICABLE,

        /** Registered to a different, still present BOSS installation. Left alone. */
        OTHER_INSTALL,

        /** Registered to a command this code cannot parse (e.g. installer-authored, unquoted). Left alone. */
        UNREADABLE,

        /** `reg delete` itself failed. */
        FAILED,
    }

    /**
     * Register the boss:// protocol in Windows Registry
     * This should be called on first launch or during installation
     *
     * Production-safe: Only registers if needed, validates existing registrations,
     * and prevents conflicts with other BOSS installations
     */
    fun registerProtocol() {
        if (!isWindows) return

        try {
            // 1. Get application path
            val appPath = getApplicationPath()
            if (appPath.isNullOrEmpty()) {
                // Development mode or unable to determine path
                return
            }

            // 2. Check current registry state
            val currentCommand = getCurrentRegistryCommand()

            // 3. Determine if registration is needed
            val needsRegistration =
                when {
                    currentCommand == null -> {
                        logger.info(LogCategory.SYSTEM, "Protocol not registered. Registering...")
                        true
                    }

                    !commandPointsToValidExecutable(currentCommand) -> {
                        logger.info(
                            LogCategory.SYSTEM,
                            "Protocol points to invalid path, re-registering",
                            mapOf("command" to currentCommand),
                        )
                        true
                    }

                    !currentCommand.contains(appPath, ignoreCase = true) -> {
                        // SAFETY CHECK: Only re-register if current path doesn't exist
                        val currentExePath = extractExecutablePath(currentCommand)
                        if (currentExePath != null && File(currentExePath).exists()) {
                            logger.info(
                                LogCategory.SYSTEM,
                                "Protocol already registered to different valid BOSS installation, skipping",
                                mapOf("path" to currentExePath),
                            )
                            false
                        } else {
                            logger.info(
                                LogCategory.SYSTEM,
                                "Protocol points to non-existent path, re-registering",
                                mapOf("command" to currentCommand),
                            )
                            true
                        }
                    }

                    else -> {
                        logger.debug(LogCategory.SYSTEM, "Protocol already correctly registered")
                        false
                    }
                }

            // 4. Perform registration if needed
            if (needsRegistration) {
                performRegistration(appPath)
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to register Windows protocol", error = e)
        }
    }

    /**
     * Perform the actual registry writes
     */
    private fun performRegistration(appPath: String) {
        logger.info(LogCategory.SYSTEM, "Starting BOSS protocol registration", mapOf("appPath" to appPath))

        val commands =
            listOf(
                // Create protocol key
                """reg add "$PROTOCOL_KEY" /ve /d "URL:BOSS Protocol" /f""",
                """reg add "$PROTOCOL_KEY" /v "URL Protocol" /d "" /f""",
                // Set icon
                """reg add "$PROTOCOL_KEY\DefaultIcon" /ve /d "$appPath,0" /f""",
                // Set command to open the app with URL
                """reg add "$PROTOCOL_KEY\shell\open\command" /ve /d "\"$appPath\" \"%1\"" /f""",
            )

        var successCount = 0
        commands.forEach { command ->
            try {
                val process = Runtime.getRuntime().exec(command)
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    successCount++
                } else {
                    logger.warn(LogCategory.SYSTEM, "Registry command failed", mapOf("exitCode" to exitCode))
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to execute registry command", error = e)
            }
        }

        if (successCount == commands.size) {
            logger.info(LogCategory.SYSTEM, "Protocol registration successful")
        } else {
            logger.warn(
                LogCategory.SYSTEM,
                "Protocol registration partial",
                mapOf(
                    "successCount" to successCount,
                    "totalCommands" to commands.size,
                ),
            )
        }
    }

    /**
     * Remove the `boss:` protocol registration.
     *
     * Because registration happens at runtime, an uninstall would otherwise leave a
     * handler pointing at a deleted `BOSS.exe`, and later `boss://` links fail with
     * Windows' generic "no app associated" error. Invoke via
     * `BOSS.exe --unregister-protocol` from an uninstall action or by hand.
     *
     * Safety: the key is only deleted when it points at *this* installation or at an
     * executable that no longer exists. A registration owned by a different, still
     * present install is left alone — and so is one whose command this code cannot
     * parse. "Unparseable" is deliberately NOT folded into "stale": an unquoted
     * `shell\open\command` is what a WiX/MSI-authored registration typically looks
     * like, so deleting it would break a working install.
     */
    fun unregisterProtocol(): UnregisterOutcome {
        if (!isWindows) return UnregisterOutcome.NOT_APPLICABLE

        val currentCommand = getCurrentRegistryCommand()
        val registeredExe = currentCommand?.let { extractExecutablePath(it) }
        val appPath = getApplicationPath()

        return when {
            !isProtocolRegistered() -> {
                logger.debug(LogCategory.SYSTEM, "boss:// protocol is not registered - nothing to clean up")
                UnregisterOutcome.ABSENT
            }

            registeredExe == null -> {
                logger.info(
                    LogCategory.SYSTEM,
                    "boss:// protocol command could not be parsed - leaving it registered",
                    mapOf("command" to currentCommand.orEmpty()),
                )
                UnregisterOutcome.UNREADABLE
            }

            // Ours, or pointing at an executable that is gone: safe to remove.
            registeredExe.equals(appPath, ignoreCase = true) || !File(registeredExe).exists() -> {
                deleteProtocolKey()
            }

            else -> {
                logger.info(
                    LogCategory.SYSTEM,
                    "boss:// protocol points at another live BOSS installation - leaving it registered",
                    mapOf("path" to registeredExe),
                )
                UnregisterOutcome.OTHER_INSTALL
            }
        }
    }

    /**
     * CLI mapping for `--unregister-protocol`: 0 when nothing is left to clean up
     * (removed / nothing registered / not a Windows host), 1 when the registration was
     * deliberately left in place, 2 when the delete itself failed.
     */
    fun unregisterProtocolExitCode(): Int {
        val outcome = unregisterProtocol()
        logger.info(LogCategory.SYSTEM, "boss:// protocol cleanup finished", mapOf("outcome" to outcome.name))
        return when (outcome) {
            UnregisterOutcome.REMOVED, UnregisterOutcome.ABSENT, UnregisterOutcome.NOT_APPLICABLE -> 0
            UnregisterOutcome.OTHER_INSTALL, UnregisterOutcome.UNREADABLE -> 1
            UnregisterOutcome.FAILED -> 2
        }
    }

    /**
     * Check if the protocol is already registered
     */
    fun isProtocolRegistered(): Boolean {
        if (!isWindows) return false

        return try {
            val process = Runtime.getRuntime().exec("""reg query "$PROTOCOL_KEY" """)
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "reg query failed - treating boss:// protocol as unregistered",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * Get the path to the running application
     */
    private fun getApplicationPath(): String? {
        return try {
            // Priority 1: Check for jpackage installation (MSI/EXE)
            // This is the most reliable method for production deployments
            val jpackagePath = System.getProperty("jpackage.app-path")
            if (!jpackagePath.isNullOrEmpty()) {
                val file = File(jpackagePath)
                if (file.exists()) {
                    logger.debug(LogCategory.SYSTEM, "Detected jpackage installation", mapOf("path" to jpackagePath))
                    return jpackagePath
                } else {
                    logger.warn(LogCategory.SYSTEM, "jpackage.app-path set but file doesn't exist", mapOf("path" to jpackagePath))
                }
            }

            // Priority 2: Try to get the path from the running JAR/EXE
            val jarPath =
                WindowsProtocolHandler::class.java.protectionDomain.codeSource.location
                    .toURI()
                    .path

            // Convert to Windows path format and handle different packaging scenarios
            when {
                jarPath.endsWith(".jar") -> {
                    // Running from JAR - look for launcher executable
                    val jarFile = File(jarPath)
                    val launcherPath = jarFile.parentFile.resolve("BOSS.exe")
                    if (launcherPath.exists()) {
                        launcherPath.absolutePath
                    } else {
                        // Cannot use "javaw.exe -jar" as registry needs executable path
                        logger.warn(LogCategory.SYSTEM, "Running from JAR without launcher executable")
                        null
                    }
                }

                jarPath.contains("BOSS.exe") -> {
                    // Already an executable
                    File(jarPath).absolutePath
                }

                else -> {
                    // Development environment - return null to skip registration
                    logger.debug(LogCategory.SYSTEM, "Running in development mode - deep links require MSI installation")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error determining application path", error = e)
            null
        }
    }

    /**
     * Parse command line arguments to extract deep link URL
     */
    fun extractDeepLinkFromArgs(args: Array<String>): String? {
        // Windows passes the URL as the first argument when launched via protocol
        return args.firstOrNull { it.startsWith("boss://") }
    }

    /**
     * Get the current command registered in the Windows registry for boss:// protocol
     */
    private fun getCurrentRegistryCommand(): String? =
        try {
            val process =
                ProcessBuilder(
                    "reg",
                    "query",
                    """$PROTOCOL_KEY\shell\open\command""",
                    "/ve",
                ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse: "    (Default)    REG_SZ    C:\Path\To\BOSS.exe "%1""
            val match = Regex("""REG_SZ\s+(.+)$""", RegexOption.MULTILINE).find(output)
            match?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not read protocol handler command from registry",
                mapOf("error" to e.toString()),
            )
            null
        }

    /**
     * Extract executable path from registry command string
     * Example: "C:\Path\To\BOSS.exe" "%1" -> C:\Path\To\BOSS.exe
     */
    private fun extractExecutablePath(command: String): String? {
        val match = Regex("""^"([^"]+)"""").find(command)
        return match?.groupValues?.get(1)
    }

    /**
     * Check if the command points to a valid executable file
     */
    private fun commandPointsToValidExecutable(command: String): Boolean {
        val exePath = extractExecutablePath(command) ?: return false
        return File(exePath).exists()
    }
}

/**
 * `reg delete` the whole `boss` key tree. File-private rather than an object member so
 * [WindowsProtocolHandler] stays within its function budget; it is only ever reached
 * from [WindowsProtocolHandler.unregisterProtocol], which decides whether deleting is
 * safe.
 */
private fun deleteProtocolKey(): WindowsProtocolHandler.UnregisterOutcome =
    try {
        val process =
            ProcessBuilder("reg", "delete", PROTOCOL_KEY, "/f")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) {
            logger.info(LogCategory.SYSTEM, "Removed boss:// protocol registration", mapOf("key" to PROTOCOL_KEY))
            WindowsProtocolHandler.UnregisterOutcome.REMOVED
        } else {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to remove boss:// protocol registration",
                mapOf("key" to PROTOCOL_KEY, "output" to output.trim()),
            )
            WindowsProtocolHandler.UnregisterOutcome.FAILED
        }
    } catch (e: IOException) {
        logger.error(LogCategory.SYSTEM, "Failed to remove boss:// protocol registration", error = e)
        WindowsProtocolHandler.UnregisterOutcome.FAILED
    } catch (e: InterruptedException) {
        // Restore the flag rather than swallowing the interrupt: this can run from the
        // `--unregister-protocol` path, where the JVM is about to exit anyway.
        Thread.currentThread().interrupt()
        logger.error(LogCategory.SYSTEM, "Interrupted while removing boss:// protocol registration", error = e)
        WindowsProtocolHandler.UnregisterOutcome.FAILED
    }
