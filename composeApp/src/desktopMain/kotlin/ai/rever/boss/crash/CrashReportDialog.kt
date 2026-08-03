package ai.rever.boss.crash

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Crash report dialog content shown when BOSS encounters an unhandled exception.
 *
 * This component renders the crash report UI directly without a Dialog wrapper,
 * as it's designed to be displayed in a standalone window (JFrame with ComposePanel).
 * This ensures the crash dialog appears even when the main Compose UI is broken.
 *
 * Features:
 * - Error summary with exception type and message
 * - Expandable technical details (stack trace)
 * - Copy to clipboard button
 * - User notes text field
 * - Optional inclusion of recent activity logs
 * - Submit to GitHub and dismiss buttons
 *
 * Layout: a fixed header, a scrollable body, and a pinned footer. The footer keeps the
 * action buttons and the submit result reachable no matter how tall the body grows —
 * without it, expanding the technical details section pushes the buttons out of the
 * (deliberately small) crash window.
 *
 * @param crashReport The crash report to display
 * @param onDismiss Called when user dismisses without submitting
 * @param onSubmit Called when user wants to submit the report
 * @param initialSubmitResult Seeds the submit-result card. Production leaves this null and lets the
 *   submit path fill it in; it exists because that path runs through the [CrashReportService]
 *   object, so the result state is otherwise unreachable — and the footer's behaviour under a long
 *   failure message is exactly what the `maxLines` cap below exists to bound. Also makes the
 *   populated footer previewable. The dialog is `internal`, so this widens nothing externally.
 */
@Composable
internal fun CrashReportDialog(
    crashReport: CrashReport,
    onDismiss: () -> Unit,
    onSubmit: (userNotes: String?, includeLogs: Boolean) -> Unit,
    onCleanAndRestart: (() -> Unit)? = null,
    initialSubmitResult: CrashReportService.SubmitResult? = null,
) {
    var userNotes by remember { mutableStateOf("") }
    var includeLogs by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf(initialSubmitResult) }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val bodyScrollState = rememberScrollState()

    // Hoisted out of the collapsible content below, so the trace keeps its scroll position
    // across a collapse/expand cycle instead of snapping back to the top. Consequence: while
    // collapsed its maxValue holds a stale value from when the pane was last measured, so
    // `traceOverflows` is only meaningful — and is only read — inside that content.
    val stackTraceScrollState = rememberScrollState()

    // Whether each region is clipping content, which gates its scrollbar — and, for the body, the
    // rule above the footer.
    //
    // `maxValue` starts at Int.MAX_VALUE and is only assigned during measure, so a bare `> 0`
    // reports "overflowing" on the first composition, before anything has been measured. Excluding
    // the sentinel keeps that first frame honest; derivedStateOf keeps a settling `maxValue` from
    // recomposing the whole dialog when the answer hasn't actually flipped.
    //
    // Note the one remaining feedback path: `bodyOverflows` adds the rule and its spacer to the
    // footer, which takes ~17dp from the body, which can only make it *more* likely to overflow.
    // Monotone, so it cannot oscillate — but it can latch, so content within ~17dp of fitting may
    // keep its scrollbar after the details collapse again. That is why nothing gated on this may
    // ever change the body's *width*: narrower is taller, and a second monotone path compounds it.
    val bodyOverflows by remember { derivedStateOf { bodyScrollState.isClipping() } }
    val traceOverflows by remember { derivedStateOf { stackTraceScrollState.isClipping() } }

    // Compose's default scrollbar is black at 12% alpha — invisible against these dark
    // panels, which would make the thumb useless as a "there is more below" cue.
    val defaultScrollbarStyle = LocalScrollbarStyle.current
    val thumbColor = BossTheme.colors.textMuted
    val thumbHoverColor = BossTheme.colors.textSecondary
    val scrollbarStyle =
        remember(defaultScrollbarStyle, thumbColor, thumbHoverColor) {
            defaultScrollbarStyle.copy(
                unhoverColor = thumbColor.copy(alpha = 0.45f),
                hoverColor = thumbHoverColor,
            )
        }

    // Derived from the thumb it makes room for, so restyling the scrollbar can't leave it
    // overlapping text.
    val scrollbarGutter = scrollbarStyle.thickness + 4.dp

    // Render directly in the window (no Dialog wrapper needed since this is shown in its own JFrame)
    Card(
        modifier =
            Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !isSubmitting) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        shape = RoundedCornerShape(0.dp),
        backgroundColor = BossTheme.colors.panel,
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            // Header with error icon — fixed, never scrolls away
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Error",
                    tint = BossTheme.colors.alert,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "BOSS Has Crashed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The body scrollbar overlays this content rather than reserving a gutter beside it.
            // A gutter would keep the body's right edge from lining up with the footer's, and if
            // it were gated on overflow it would also feed back into the measurement deciding it:
            // narrower content is taller content, so overflow would latch on once entered. Nothing
            // in the body renders hard against its right edge (every card and field has its own
            // padding), so an overlaid thumb costs nothing.
            //
            // Scrollable body — capped at the space left over by the header and footer, so
            // anything that grows (expanded stack trace, long exception message) scrolls here
            // instead of pushing the action buttons past the bottom of the window.
            // `fill = false` keeps the cap from becoming a floor: when the content is short the
            // body stays short and the footer sits right below it, as it did before the cap.
            Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(bodyScrollState),
                ) {
                    // Error summary
                    Card(
                        backgroundColor = BossTheme.colors.raised,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = crashReport.exceptionType,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BossTheme.colors.alert,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = crashReport.exceptionMessage,
                                fontSize = 13.sp,
                                color = BossTheme.colors.textPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Expandable technical details
                    Card(
                        backgroundColor = BossTheme.colors.raised,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            // Header row (clickable)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { showDetails = !showDetails }
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = "Technical Details",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BossTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (showDetails) "Collapse" else "Expand",
                                    tint = BossTheme.colors.textSecondary,
                                )
                            }

                            // Expandable content
                            AnimatedVisibility(
                                visible = showDetails,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                            .padding(bottom = 12.dp),
                                ) {
                                    // Copy button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(crashReport.stackTrace))
                                            },
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = BossTheme.colors.signal,
                                                ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ContentCopy,
                                                contentDescription = "Copy",
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy to Clipboard", fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Stack trace — bounded and independently scrollable so a deep
                                    // trace doesn't turn the body into an endless scroll
                                    SelectionContainer {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 200.dp)
                                                    .background(
                                                        BossTheme.colors.panel,
                                                        RoundedCornerShape(4.dp),
                                                    ).padding(8.dp),
                                        ) {
                                            Text(
                                                text = crashReport.stackTrace,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = BossTheme.colors.textPrimary,
                                                lineHeight = 14.sp,
                                                modifier =
                                                    Modifier
                                                        .verticalScroll(stackTraceScrollState)
                                                        // Unconditional here, unlike the body:
                                                        // trace lines *do* run to the edge, so the
                                                        // thumb may not overlay them — and inside
                                                        // this panel a permanent inset has no
                                                        // alignment reference to break.
                                                        .padding(end = scrollbarGutter),
                                            )
                                            if (traceOverflows) {
                                                VerticalScrollbar(
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.CenterEnd)
                                                            .fillMaxHeight()
                                                            .testTag(TRACE_SCROLLBAR_TAG),
                                                    adapter = rememberScrollbarAdapter(stackTraceScrollState),
                                                    style = scrollbarStyle,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // User notes input
                    Text(
                        text = "What were you doing when this happened? (optional)",
                        fontSize = 13.sp,
                        color = BossTheme.colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userNotes,
                        onValueChange = { userNotes = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                "Describe what you were doing...",
                                color = BossTheme.colors.textMuted,
                            )
                        },
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                backgroundColor = BossTheme.colors.raised,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                cursorColor = BossTheme.colors.signal,
                            ),
                        enabled = !isSubmitting,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Include logs checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isSubmitting) { includeLogs = !includeLogs }
                                .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = includeLogs,
                            onCheckedChange = null,
                            colors =
                                CheckboxDefaults.colors(
                                    checkedColor = BossTheme.colors.signal,
                                    uncheckedColor = BossTheme.colors.textMuted,
                                    checkmarkColor = BossTheme.colors.onSignal,
                                ),
                            enabled = !isSubmitting,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Include recent activity logs",
                                fontSize = 14.sp,
                                color = BossTheme.colors.textPrimary,
                            )
                            Text(
                                text = "Helps with debugging (logs are sanitized)",
                                fontSize = 11.sp,
                                color = BossTheme.colors.textMuted,
                            )
                        }
                    }
                }

                if (bodyOverflows) {
                    VerticalScrollbar(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .testTag(BODY_SCROLLBAR_TAG),
                        adapter = rememberScrollbarAdapter(bodyScrollState),
                        style = scrollbarStyle,
                    )
                }
            }

            // Pinned footer — the submit result and the action buttons stay visible regardless of
            // how much the body above has grown or scrolled. The rule is only drawn when the body
            // is actually clipping something, where it marks the scroll boundary; without it the
            // footer would look like a stray divider floating in empty space.
            if (bodyOverflows) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = BossTheme.colors.line)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Submit result message
            submitResult?.let { result ->
                Card(
                    backgroundColor =
                        when (result) {
                            is CrashReportService.SubmitResult.Success -> BossTheme.colors.ok
                            is CrashReportService.SubmitResult.Error -> BossTheme.colors.alert
                        },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Selectable so the visible reason can be copied — pasting it somewhere is
                    // usually the user's next move. Selection only reaches painted text, so the
                    // tail past the ellipsis is not recoverable here; both error paths log the
                    // full exception (CrashReportService), which is where it survives.
                    SelectionContainer {
                        Text(
                            text =
                                when (result) {
                                    is CrashReportService.SubmitResult.Success -> {
                                        if (result.isNewIssue) {
                                            "Issue created successfully!"
                                        } else {
                                            "Added to existing issue."
                                        }
                                    }

                                    is CrashReportService.SubmitResult.Error -> {
                                        // The text most likely to end up pasted into a public
                                        // issue, and it interpolates a raw exception message —
                                        // ktor's routinely carry the request URL. The unmasked
                                        // form still reaches the log, which is where it belongs.
                                        LogSanitizer.maskUriParams(result.message)
                                    }
                                },
                            fontSize = 13.sp,
                            color = BossTheme.colors.onSignal,
                            // This text is the one part of the footer whose length isn't ours: the
                            // network failures interpolate `e.message` (CrashReportService), which
                            // a TLS or proxy error can make arbitrarily long. Unbounded, it would
                            // grow the footer and squeeze the body — re-creating, one level up, the
                            // exact overflow this layout exists to prevent.
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Clean & Restart button
                if (onCleanAndRestart != null) {
                    Button(
                        onClick = onCleanAndRestart,
                        enabled = !isSubmitting,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.alert,
                                contentColor = BossTheme.colors.onSignal,
                                disabledBackgroundColor = BossTheme.colors.raised,
                                disabledContentColor = BossTheme.colors.textMuted,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Clean Data & Restart")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Don't Send button
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = BossTheme.colors.textSecondary,
                        ),
                ) {
                    Text("Don't Send")
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Report Issue button
                Button(
                    onClick = {
                        isSubmitting = true
                        coroutineScope.launch {
                            // Update report with user input
                            CrashHandler
                                .updateReportWithUserInput(
                                    userNotes = userNotes.takeIf { it.isNotBlank() },
                                    includeLogs = includeLogs,
                                )?.let { updatedReport ->
                                    val result = CrashReportService.submitCrashReport(updatedReport)
                                    submitResult = result
                                    isSubmitting = false

                                    // If successful, call onSubmit after a brief delay
                                    if (result is CrashReportService.SubmitResult.Success) {
                                        kotlinx.coroutines.delay(2000)
                                        onSubmit(userNotes.takeIf { it.isNotBlank() }, includeLogs)
                                    }
                                } ?: run {
                                submitResult = CrashReportService.SubmitResult.Error("Failed to prepare report")
                                isSubmitting = false
                            }
                        }
                    },
                    enabled = !isSubmitting && submitResult !is CrashReportService.SubmitResult.Success,
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                            disabledBackgroundColor = BossTheme.colors.raised,
                            disabledContentColor = BossTheme.colors.textMuted,
                        ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BossTheme.colors.textPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Submitting...")
                    } else {
                        Text("Report Issue")
                    }
                }
            }

            // Close button after successful submission
            if (submitResult is CrashReportService.SubmitResult.Success) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.raised,
                                contentColor = BossTheme.colors.textPrimary,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/** Present only while the body is clipping content; see `isClipping`. */
internal const val BODY_SCROLLBAR_TAG = "crash-dialog-body-scrollbar"

/** Present only while the stack trace pane is clipping content; see `isClipping`. */
internal const val TRACE_SCROLLBAR_TAG = "crash-dialog-trace-scrollbar"

/**
 * True when this region has measured content taller than its viewport.
 *
 * Deliberately not `maxValue > 0`: [ScrollState.maxValue] is initialised to [Int.MAX_VALUE] and
 * only assigned during the measure pass, so the bare comparison reports overflow on the first
 * composition of any content, however short.
 */
private fun ScrollState.isClipping(): Boolean = maxValue in 1 until Int.MAX_VALUE
