package ai.rever.boss.testsupport

import java.io.File

/**
 * Locates the checkout root by walking up from the test's working directory.
 *
 * Shared because the source-scanning convention tests all need it and had started copying it
 * verbatim - `NoRawDialogConventionTest` and `WindowIconConventionTest` held identical copies before
 * this existed. Gradle runs desktopTest from the module directory, and a git worktree puts the
 * checkout somewhere else again, so no test can assume a fixed relative path.
 */
fun repoRoot(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        if (File(dir, "composeApp").isDirectory && File(dir, "version.properties").isFile) return dir
        dir = dir.parentFile
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
}

/** Every `.kt` file under the given repo-relative roots that exist. */
fun kotlinSourcesUnder(
    root: File,
    vararg relativeRoots: String,
): List<File> {
    val roots = relativeRoots.map { File(root, it) }.filter { it.isDirectory }
    check(roots.isNotEmpty()) { "none of ${relativeRoots.toList()} found under $root" }
    return roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
}
