package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

private val filePickerLogger = BossLogger.forComponent("FilePicker")

/**
 * Ceiling on a picked file's size.
 *
 * A 100k-entry export is roughly 10 MB, so this is comfortably above anything
 * real while keeping the read — and the plaintext String it decodes into —
 * small enough not to stall the UI or balloon the heap.
 */
private const val MAX_PICKED_FILE_BYTES = 16L * 1024 * 1024

@Composable
actual fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?, tooLarge: Boolean) -> Unit,
    fileExtensions: List<String>,
    title: String,
): FilePicker =
    remember {
        DesktopFilePicker(onFileSelected, fileExtensions, title)
    }

class DesktopFilePicker(
    private val onFileSelected: (path: String?, content: String?, tooLarge: Boolean) -> Unit,
    private val fileExtensions: List<String>,
    private val title: String = "Select File",
) : FilePicker {
    override fun pickFile() {
        try {
            val fileDialog =
                FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                    // Set file filter for JSON files
                    if (fileExtensions.isNotEmpty()) {
                        setFilenameFilter { _, name ->
                            fileExtensions.any { name.endsWith(".$it", ignoreCase = true) }
                        }
                    }
                    isVisible = true
                }

            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory

            if (selectedFile != null && selectedDir != null) {
                val file = File(selectedDir, selectedFile)

                // Bounded read: this runs on the caller's thread (the EDT for a
                // dialog), and an accidentally-picked multi-gigabyte file would
                // otherwise freeze the UI on its way to an OutOfMemoryError.
                if (file.length() > MAX_PICKED_FILE_BYTES) {
                    filePickerLogger.warn(
                        LogCategory.FILE,
                        "Picked file is too large to read - reporting no selection",
                        mapOf("bytes" to file.length()),
                    )
                    onFileSelected(null, null, true)
                    return
                }

                onFileSelected(file.absolutePath, file.readText(), false)
            } else {
                onFileSelected(null, null, false)
            }
        } catch (e: Exception) {
            filePickerLogger.warn(LogCategory.FILE, "Failed to read picked file - reporting no selection", error = e)
            onFileSelected(null, null, false)
        }
    }
}

/**
 * Desktop implementation of pickSaveFile using AWT FileDialog.
 * Runs synchronously on the EDT (Event Dispatch Thread) as required by JxBrowser callbacks.
 */
actual fun pickSaveFile(
    suggestedFileName: String,
    initialDirectory: String?,
    allowedExtensions: List<String>,
): String? {
    // Sanitize the suggested file name for security
    val sanitizedFileName = FileNameSanitizer.sanitize(suggestedFileName)

    var result: String? = null

    try {
        // Must run on EDT to avoid AWT threading issues
        SwingUtilities.invokeAndWait {
            val fileDialog =
                FileDialog(null as Frame?, "Save File", FileDialog.SAVE).apply {
                    // Set suggested file name
                    file = sanitizedFileName

                    // Set initial directory if provided
                    initialDirectory?.let { directory = it }

                    // Set file filter if extensions specified
                    if (allowedExtensions.isNotEmpty()) {
                        setFilenameFilter { _, name ->
                            allowedExtensions.any { ext ->
                                name.endsWith(".$ext", ignoreCase = true)
                            } || allowedExtensions.contains("*")
                        }
                    }

                    isVisible = true
                }

            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory

            if (selectedFile != null && selectedDir != null) {
                result = File(selectedDir, selectedFile).absolutePath
            }
        }
    } catch (e: Exception) {
        filePickerLogger.warn(LogCategory.FILE, "Error showing save file dialog", error = e)
        result = null
    }

    return result
}
