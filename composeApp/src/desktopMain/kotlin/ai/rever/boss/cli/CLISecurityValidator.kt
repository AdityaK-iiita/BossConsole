package ai.rever.boss.cli

/**
 * Security validation utilities for CLI operations.
 *
 * Prevents path traversal attacks, command injection, and other security issues.
 * Based on security validation from UpdateScriptGenerator.kt:72-104
 */
object CLISecurityValidator {
    /**
     * Validates URL format.
     * For backward compatibility - use normalizeAndValidateUrl() for new code.
     */
    fun isValidUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /**
     * Normalizes and validates a URL.
     * Adds https:// prefix if missing for domain-like strings.
     *
     * @param url The URL to normalize and validate
     * @return The normalized URL with proper protocol, or null if invalid
     *
     * Examples:
     * - "google.com" -> "https://google.com"
     * - "https://google.com" -> "https://google.com"
     * - "http://example.com" -> "http://example.com"
     * - "invalidurl" -> null (no domain detected)
     */
    fun normalizeAndValidateUrl(url: String): String? {
        val trimmed = url.trim()

        // Empty URL is invalid
        if (trimmed.isEmpty()) {
            return null
        }

        // Already has protocol - validate and return as-is
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        // Check if it looks like a domain (contains at least one dot)
        // This prevents random strings from being treated as URLs
        if (!trimmed.contains('.')) {
            return null
        }

        // Basic validation: shouldn't contain spaces or most special chars
        if (trimmed.contains(' ') || trimmed.contains('\n') || trimmed.contains('\r')) {
            return null
        }

        // Add https:// prefix (modern standard)
        return "https://$trimmed"
    }

    /**
     * Validates file path for security.
     * Prevents path traversal attacks and other malicious patterns.
     */
    fun isValidPath(path: String): Boolean {
        // Check for null bytes
        if (path.contains('\u0000')) {
            return false
        }

        // Check for path traversal
        if (path.contains("..")) {
            return false
        }

        // Check for shell metacharacters
        val dangerousChars = listOf(';', '&', '|', '`', '$', '\n', '\r')
        if (dangerousChars.any { path.contains(it) }) {
            return false
        }

        return true
    }

    /**
     * Longest terminal command BOSS will type into a shell — well past anything
     * a person writes by hand, and a bound on what a caller can make the app
     * hold. Commands that must be confirmed are held to the tighter
     * [TERMINAL_CONFIRM_MAX_COMMAND_LENGTH], which is what the prompt can show in
     * full.
     */
    private const val MAX_COMMAND_LENGTH = 4096

    /**
     * Checks that a terminal command is well formed: one non-empty line of
     * printable text, no longer than [MAX_COMMAND_LENGTH].
     *
     * This is a shape check, not a judgement about what the command does. An
     * allow-list of commands would rule out the legitimate use — `boss terminal
     * -c` exists precisely to run whatever the operator types — without ruling
     * out much else, so **who asked** is decided separately by
     * [ai.rever.boss.utils.DeepLinkOrigin]: only a request the operator made
     * themselves runs without a prompt.
     *
     * Control characters are rejected because the command is written into a
     * shell followed by a single Enter — an embedded line break would submit
     * further lines that nothing ever displayed, so keeping the command to one
     * line is what makes the text shown equal to the text that runs. The NUL
     * byte the previous check looked for is one of them.
     */
    fun isValidCommand(command: String): Boolean =
        command.isNotBlank() &&
            command.length <= MAX_COMMAND_LENGTH &&
            command.none { it.isISOControl() }
}
