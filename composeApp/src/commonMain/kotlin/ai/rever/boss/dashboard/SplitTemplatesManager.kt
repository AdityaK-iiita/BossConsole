package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Configuration for a panel in a split template.
 */
@Serializable
data class TemplatePanelContent(
    val command: String? = null, // For terminal: command to run
    val url: String? = null, // For browser: URL to open
    val filePath: String? = null, // For editor: file to open
)

/**
 * Panel configuration within a split template.
 */
@Serializable
data class TemplatePanelConfig(
    val type: String, // "terminal", "browser", "editor"
    val position: String, // "left", "right", "top", "bottom"
    val content: TemplatePanelContent,
)

/**
 * A split template definition.
 */
@Serializable
data class SplitTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String, // Icon identifier
    val isBuiltIn: Boolean = true,
    val panels: List<TemplatePanelConfig>,
)

/**
 * Container for custom split templates data.
 */
@Serializable
data class CustomTemplatesData(
    val templates: List<SplitTemplate> = emptyList(),
)

/**
 * Manages split templates for quick workspace setup.
 * Built-in templates are always available.
 * Custom templates persist to ~/.boss/split-templates.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
object SplitTemplatesManager {
    private val logger = BossLogger.forComponent("SplitTemplatesManager")

    // Also the guards in [processPlaceholders], so a guard and its substitution cannot drift.
    // Every placeholder whose value costs something to compute needs one: a mkdir, a `git`
    // subprocess and a directory listing respectively.
    private const val PROJECT_PATH_PLACEHOLDER = "{projectPath}"
    private const val GIT_REMOTE_URL_PLACEHOLDER = "{gitRemoteUrl}"
    private const val CLAUDE_CONTINUE_FLAG_PLACEHOLDER = "{claudeContinueFlag}"
    private val settingsFile = BossDirectories.resolve("split-templates.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Built-in templates that are always available
    private val builtInTemplates =
        listOf(
            SplitTemplate(
                id = "claude-code",
                name = "Claude Code",
                description = "Terminal with Claude CLI + Browser with GitHub",
                icon = "terminal-browser",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath} && claude --dangerously-skip-permissions",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "{gitRemoteUrl}",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "code-review",
                name = "Code Review",
                description = "README + GitHub + Claude Code",
                icon = "git-pull-request",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "editor",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    filePath = "{projectPath}/README.md",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "{gitRemoteUrl}",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "bottom",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath} && claude --dangerously-skip-permissions",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "gemini",
                name = "Gemini",
                description = "Gemini CLI + GitHub",
                icon = "terminal-browser",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath} && gemini",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "{gitRemoteUrl}",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "codex",
                name = "Codex",
                description = "OpenAI Codex CLI + GitHub",
                icon = "terminal-browser",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath} && codex",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "{gitRemoteUrl}",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "opencode",
                name = "OpenCode",
                description = "OpenCode AI CLI + GitHub",
                icon = "terminal-browser",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath} && opencode",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "{gitRemoteUrl}",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "terminal-browser",
                name = "Terminal + Browser",
                description = "Terminal on left, Browser on right",
                icon = "layout-split",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath}",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "browser",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    url = "https://google.com",
                                ),
                        ),
                    ),
            ),
            SplitTemplate(
                id = "dual-terminal",
                name = "Dual Terminal",
                description = "Two terminals side by side",
                icon = "terminal-dual",
                isBuiltIn = true,
                panels =
                    listOf(
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "left",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath}",
                                ),
                        ),
                        TemplatePanelConfig(
                            type = "terminal",
                            position = "right",
                            content =
                                TemplatePanelContent(
                                    command = "cd {projectPath}",
                                ),
                        ),
                    ),
            ),
        )

    private val _customTemplates = MutableStateFlow<List<SplitTemplate>>(emptyList())
    val customTemplates: StateFlow<List<SplitTemplate>> = _customTemplates.asStateFlow()

    // Combined templates: built-in + custom
    private val _allTemplates = MutableStateFlow<List<SplitTemplate>>(builtInTemplates)
    val allTemplates: StateFlow<List<SplitTemplate>> = _allTemplates.asStateFlow()

    init {
        scope.launch {
            loadAsync()
        }
    }

    /**
     * Load custom templates from disk asynchronously.
     */
    private suspend fun loadAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val data = json.decodeFromString<CustomTemplatesData>(content)
                    _customTemplates.value = data.templates
                    updateAllTemplates()
                    logger.debug(LogCategory.SYSTEM, "Loaded custom templates", mapOf("count" to data.templates.size))
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error loading templates", error = e)
            }
        }

    /**
     * Save custom templates to disk.
     */
    private suspend fun saveAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                val data = CustomTemplatesData(templates = _customTemplates.value)
                val content = json.encodeToString(CustomTemplatesData.serializer(), data)
                settingsFile.writeText(content)
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error saving templates", error = e)
            }
        }

    private fun updateAllTemplates() {
        _allTemplates.value = builtInTemplates + _customTemplates.value
    }

    /**
     * Get a template by ID.
     */
    fun getTemplate(id: String): SplitTemplate? = _allTemplates.value.find { it.id == id }

    /**
     * Add a custom template.
     */
    fun addCustomTemplate(template: SplitTemplate) {
        val customTemplate = template.copy(isBuiltIn = false)
        _customTemplates.value = _customTemplates.value + customTemplate
        updateAllTemplates()
        scope.launch { saveAsync() }
    }

    /**
     * Remove a custom template.
     */
    fun removeCustomTemplate(id: String) {
        _customTemplates.value = _customTemplates.value.filter { it.id != id }
        updateAllTemplates()
        scope.launch { saveAsync() }
    }

    /**
     * Substitute `{projectPath}` with [pathValue].
     *
     * When [quote] is false (raw paths: workingDirectory/filePath/url) every
     * occurrence is replaced verbatim. When true (shell command context),
     * *bare* occurrences are shell-quoted so spaces/quotes survive as one
     * argument — but occurrences a template already wraps in a quote (e.g. a
     * user who worked around the bug with `cd "{projectPath}"`) are left raw,
     * to avoid double-quoting like `cd "'…'"`.
     */
    internal fun substituteProjectPath(
        content: String,
        pathValue: String,
        quote: Boolean,
    ): String {
        if (!quote) return content.replace(PROJECT_PATH_PLACEHOLDER, pathValue)
        val quoted = CommandProcessor.quotePath(pathValue)
        // Quote only occurrences NOT already adjacent to a quote char. The
        // lambda form does literal replacement (no $-group interpretation),
        // so `quoted` containing quotes/backslashes is inserted as-is.
        val bare = Regex("(?<![\"'])\\{projectPath\\}(?![\"'])")
        var result = bare.replace(content) { quoted }
        // Any leftover {projectPath} was already quote-wrapped by the template → raw.
        result = result.replace("{projectPath}", pathValue)
        return result
    }

    /**
     * Process placeholders in template content.
     *
     * Available placeholders:
     * - {projectPath}: Current project directory path
     * - {gitRemoteUrl}: Git remote origin URL converted to web URL
     * - {currentFile}: Currently open file path
     *
     * @param content The content string with placeholders
     * @param projectPath The current project path, or null/blank for no project. This function
     *   handles the no-project case for all three project placeholders consistently, so a
     *   caller may pass a raw path straight from window state - but note that every production
     *   caller resolves first (it needs the same directory for a tab's `workingDirectory`), so
     *   the no-project branch below is reached only by a direct caller. Passing an
     *   already-resolved path is not a second answer, just a no-op.
     * @param currentFile The currently open file (optional)
     * @param quoteProjectPath When true, {projectPath} is substituted as a
     *   shell-quoted argument. Pass true ONLY for shell command content
     *   (e.g. `cd {projectPath} && claude`) so a path with spaces/quotes —
     *   like `AI Workflow Tools' Exports` — survives as one argument. Leave
     *   false for raw paths (workingDirectory, filePath, url), which are NOT
     *   shell-parsed and must not be quoted. When true, {projectPath} should
     *   stand alone as a whole argument (`{projectPath}/sub` becomes `'…'/sub`,
     *   which POSIX concatenates but PowerShell does not).
     * @return The content with placeholders replaced
     */
    fun processPlaceholders(
        content: String,
        projectPath: String?,
        currentFile: String? = null,
        quoteProjectPath: Boolean = false,
    ): String {
        var result = content

        // One reading of "is there a project" for all three project placeholders. They used to
        // disagree about a blank path: {projectPath} treated it as absent, while the two below
        // took it as a real path - getClaudeContinueFlag("") looks in ~/.claude/projects/
        // itself.
        //
        // Reachable only by a direct caller. Every production caller resolves first, because
        // it needs the same directory for a tab's workingDirectory, so with no project
        // selected all three see ~/BossProjects and take the has-a-project branch: the git
        // lookup runs in the projects folder and finds no remote, and the session lookup
        // misses. That is what the old code did with the home directory too. What this buys is
        // that the branches agree with each other, whichever one a caller lands on.
        val selectedProject = DefaultWorkingDirectory.selectedOrNull(projectPath)

        // Replace project path (shell-quoted when used inside a command). With no project the
        // fallback is ~/BossProjects, not the home directory - see DefaultWorkingDirectory.
        //
        // Guarded on the placeholder being present, which substituteProjectPath would handle
        // by itself: the point is that DefaultWorkingDirectory.ensureDefaultDirectory() *creates a directory*,
        // and evaluating it for content with no {projectPath} in it - "{gitRemoteUrl}", a
        // plain string - makes a mkdir a side effect of a function called processPlaceholders.
        if (result.contains(PROJECT_PATH_PLACEHOLDER)) {
            val pathValue = selectedProject ?: DefaultWorkingDirectory.ensureDefaultDirectory()
            result = substituteProjectPath(result, pathValue, quoteProjectPath)
        }

        // Replace git remote URL. Deliberately not resolved to the default: the projects
        // folder is not a repository, so "no project" means there is no remote to link to.
        //
        // Guarded for the same reason as {projectPath}, and this is the expensive one:
        // getGitRemoteUrl forks `git remote get-url origin` and waits for it. Restoring a
        // workspace calls this once per placeholder-carrying field on the composition thread,
        // so an unguarded lookup was a subprocess spawn per tab for content that never asked
        // for a remote.
        if (result.contains(GIT_REMOTE_URL_PLACEHOLDER)) {
            val gitUrl = selectedProject?.let { getGitRemoteUrl(it) } ?: "https://google.com"
            result = result.replace(GIT_REMOTE_URL_PLACEHOLDER, gitUrl)
        }

        // Replace current file
        if (currentFile != null) {
            result = result.replace("{currentFile}", currentFile)
        }

        // Replace Claude continue flag based on session existence. Guarded too:
        // checkClaudeSessionExists lists ~/.claude/projects/<encoded>.
        if (result.contains(CLAUDE_CONTINUE_FLAG_PLACEHOLDER)) {
            result = result.replace(CLAUDE_CONTINUE_FLAG_PLACEHOLDER, getClaudeContinueFlag(selectedProject))
        }

        // Normalize command separators for current platform (MUST be last step)
        result = CommandProcessor.normalizeCommand(result)

        return result
    }

    /**
     * Get the Git remote origin URL for a project and convert it to a web URL.
     */
    private fun getGitRemoteUrl(projectPath: String): String {
        return try {
            val process =
                ProcessBuilder("git", "remote", "get-url", "origin")
                    .directory(File(projectPath))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val url = reader.readLine()?.trim() ?: return "https://google.com"
            val exitCode = process.waitFor()

            // Validate: git must succeed and output must look like a URL/remote
            if (exitCode != 0 || (
                    !url.startsWith("git@") && !url.startsWith("https://") &&
                        !url.startsWith("http://") && !url.startsWith("ssh://")
                )
            ) {
                return "https://google.com"
            }

            // Convert SSH URL to HTTPS if needed
            convertGitUrlToWebUrl(url)
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Error getting git remote", mapOf("error" to e.toString()))
            "https://google.com"
        }
    }

    /**
     * Convert a Git URL (SSH or HTTPS) to a web URL.
     * Examples:
     * - git@github.com:user/repo.git -> https://github.com/user/repo
     * - https://github.com/user/repo.git -> https://github.com/user/repo
     */
    private fun convertGitUrlToWebUrl(gitUrl: String): String {
        var url = gitUrl.trim()

        // Handle SSH format: git@github.com:user/repo.git
        if (url.startsWith("git@")) {
            url = url.removePrefix("git@")
            url = url.replace(":", "/")
            url = "https://$url"
        }

        // Remove .git suffix
        if (url.endsWith(".git")) {
            url = url.removeSuffix(".git")
        }

        return url
    }

    /**
     * Check if a valid Claude session exists for the given project.
     * Valid sessions are non-empty .jsonl files that are not agent sub-sessions.
     */
    private fun checkClaudeSessionExists(projectPath: String): Boolean {
        return try {
            val userHome = System.getProperty("user.home")
            val encodedPath = projectPath.replace("/", "-").replace("\\", "-")
            val claudeProjectDir = File("$userHome/.claude/projects/$encodedPath")

            if (!claudeProjectDir.exists() || !claudeProjectDir.isDirectory) {
                return false
            }

            // Look for non-empty .jsonl files that are not agent sessions
            claudeProjectDir.listFiles()?.any { file ->
                file.isFile &&
                    file.name.endsWith(".jsonl") &&
                    !file.name.startsWith("agent-") &&
                    file.length() > 0
            } ?: false
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Error checking Claude session", mapOf("error" to e.toString()))
            false
        }
    }

    /**
     * Get the appropriate Claude CLI flags based on session existence.
     * Returns "--continue" if a valid session exists, empty string otherwise.
     */
    private fun getClaudeContinueFlag(projectPath: String?): String {
        if (projectPath == null) return ""
        return if (checkClaudeSessionExists(projectPath)) "--continue" else ""
    }

    /**
     * Get built-in templates only.
     */
    fun getBuiltInTemplates(): List<SplitTemplate> = builtInTemplates
}
