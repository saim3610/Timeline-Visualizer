package com.journeyvisualizer.app.ui.phase1.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.FailureReason
import com.journeyvisualizer.app.ui.phase1.ImportStage
import com.journeyvisualizer.app.ui.phase1.ImportState
import com.journeyvisualizer.app.ui.phase1.ImportSummary
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.components.CardRadius
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.PrimaryButton
import com.journeyvisualizer.app.ui.phase1.components.ProgressRing
import com.journeyvisualizer.app.ui.phase1.components.SecondaryButton
import com.journeyvisualizer.app.ui.phase1.components.SectionHeader
import com.journeyvisualizer.app.ui.phase1.components.SmallRadius
import com.journeyvisualizer.app.ui.phase1.components.StepRow
import com.journeyvisualizer.app.ui.phase1.components.UploadDropZone
import com.journeyvisualizer.app.ui.theme.SuccessGreen
import java.util.Locale

// ---------------------------------------------------------------------------
// Screen 3 — Upload Timeline
// ---------------------------------------------------------------------------

@Composable
fun UploadScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onFileReady: () -> Unit,
) {
    val context = LocalContext.current
    var showHowTo by remember { mutableStateOf(false) }

    // Storage Access Framework: no storage permission needed, the picker
    // grants a one-shot read for exactly the file the user chose.
    // Cancellation returns null and simply stays on this screen.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            ux.startImport(context, uri)
            onFileReady()
        }
    }
    val pickFile = { picker.launch("application/json") }
    val useSample = {
        ux.startSampleImport(context)
        onFileReady()
    }

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.upload_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            UploadDropZone(
                onClick = pickFile,
                title = stringResource(R.string.upload_drop_title),
                subtitle = stringResource(R.string.upload_drop_text),
                icon = Icons.Filled.CloudUpload,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = stringResource(R.string.upload_choose),
                onClick = pickFile,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.upload_use_sample),
                onClick = useSample,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader(title = stringResource(R.string.upload_supported))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(CardRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(SmallRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.upload_supported_name),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.upload_supported_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showHowTo = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(text = stringResource(R.string.upload_howto))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHowTo) {
        HowToDialog(onDismiss = { showHowTo = false })
    }
}

@Composable
fun HowToDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upload_howto), fontWeight = FontWeight.SemiBold) },
        text = { Text(stringResource(R.string.create_howto)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Screen 4 — File selected / validation (real import results)
// ---------------------------------------------------------------------------

@Composable
fun FileSummaryScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    val state = ux.importState
    var showHowTo by remember { mutableStateOf(false) }

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.summary_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            when (state) {
                is ImportState.Ready -> SummaryReady(
                    summary = state.summary,
                    onContinue = onContinue,
                    onChooseAnother = onChooseAnother,
                )
                is ImportState.Failed -> SummaryFailed(
                    state = state,
                    onChooseAnother = onChooseAnother,
                    onHowTo = { showHowTo = true },
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHowTo) {
        HowToDialog(onDismiss = { showHowTo = false })
    }
}

@Composable
private fun SummaryReady(
    summary: ImportSummary,
    onContinue: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    // File card with real values from the parsed file.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(SmallRadius))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = summary.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.upload_supported_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            SummaryRow(
                label = stringResource(R.string.summary_size),
                value = summary.sizeLabel,
            )
            Spacer(Modifier.height(10.dp))
            SummaryRow(
                label = stringResource(
                    if (summary.isSegmentFormat) R.string.summary_segments
                    else R.string.summary_records,
                ),
                value = formatCount(summary.recordCount),
            )
            Spacer(Modifier.height(10.dp))
            SummaryRow(
                label = stringResource(R.string.summary_valid_locations),
                value = formatCount(summary.validPoints),
            )
            Spacer(Modifier.height(10.dp))
            SummaryRow(
                label = stringResource(R.string.summary_range),
                value = summary.dateRangeLabel,
            )
            // Phase 3: how many coordinates got a real place name.
            if (summary.resolvedPoints.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SummaryRow(
                    label = stringResource(R.string.summary_locations_named),
                    value = "${formatCount(summary.resolvedCount)} / ${formatCount(summary.validPoints)}",
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    val hasWarnings = summary.warnings.isNotEmpty() || summary.invalidPoints > 0
    if (!hasWarnings) {
        ValidationBanner(
            title = stringResource(R.string.summary_ok),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            badge = SuccessGreen,
        ) {
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_json), tint = SuccessGreen)
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_timeline), tint = SuccessGreen)
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_geo), tint = SuccessGreen)
        }
    } else {
        ValidationBanner(
            title = stringResource(R.string.summary_warning_title),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            badge = MaterialTheme.colorScheme.tertiary,
        ) {
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_json), tint = SuccessGreen)
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_timeline), tint = SuccessGreen)
            ValidationLine(icon = Icons.Filled.Check, text = stringResource(R.string.summary_check_geo), tint = SuccessGreen)
            if (summary.invalidPoints > 0) {
                ValidationLine(
                    icon = Icons.Filled.Warning,
                    text = stringResource(R.string.summary_invalid_records, summary.invalidPoints),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            summary.warnings.forEach { warning ->
                ValidationLine(
                    icon = Icons.Filled.Warning,
                    text = warning,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        text = stringResource(R.string.summary_continue),
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.summary_change),
        onClick = onChooseAnother,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SummaryFailed(
    state: ImportState.Failed,
    onChooseAnother: () -> Unit,
    onHowTo: () -> Unit,
) {
    val title = when (state.reason) {
        FailureReason.EMPTY_FILE -> stringResource(R.string.import_error_empty)
        FailureReason.READ_ERROR -> stringResource(R.string.import_error_read)
        FailureReason.NOT_JSON -> stringResource(R.string.import_error_json)
        FailureReason.NOT_TIMELINE -> stringResource(R.string.import_error_format)
        FailureReason.NO_POINTS -> stringResource(R.string.import_error_nopoints)
        FailureReason.TOO_LARGE -> stringResource(R.string.import_error_toolarge)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.summary_error_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        text = stringResource(R.string.summary_change),
        onClick = onChooseAnother,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.upload_howto),
        onClick = onHowTo,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ValidationBanner(
    title: String,
    container: Color,
    content: Color,
    badge: Color,
    lines: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(container)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
        }
        Spacer(Modifier.height(12.dp))
        lines()
    }
}

@Composable
private fun ValidationLine(icon: ImageVector, text: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatCount(n: Int): String = "%,d".format(Locale.US, n)

// ---------------------------------------------------------------------------
// Screen 5 — Processing (real import stages, no fake progress)
// ---------------------------------------------------------------------------

@Composable
fun ProcessingScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val state = ux.importState

    // Terminal states hand off to the summary screen, which renders them.
    if (state is ImportState.Ready || state is ImportState.Failed) {
        LaunchedEffect(state) { onFinished() }
    }
    if (state is ImportState.Idle) {
        LaunchedEffect(Unit) { onBack() }
    }

    val stageLabels = listOf(
        stringResource(R.string.processing_stage_reading),
        stringResource(R.string.processing_stage_detecting),
        stringResource(R.string.processing_stage_parsing),
        stringResource(R.string.processing_stage_resolving),
        stringResource(R.string.processing_stage_finalizing),
    )
    val currentStage = when (val s = state) {
        is ImportState.Reading -> 0
        is ImportState.Parsing -> when (s.stage) {
            ImportStage.READING -> 0
            ImportStage.DETECTING -> 1
            ImportStage.PARSING -> 2
            ImportStage.RESOLVING -> 3
            ImportStage.FINALIZING -> 4
        }
        else -> -1
    }
    val progress = when (val s = state) {
        is ImportState.Reading -> 0.05f
        is ImportState.Parsing -> s.progress
        else -> 0f
    }
    val fileName = when (val s = state) {
        is ImportState.Reading -> s.fileName
        is ImportState.Parsing -> s.fileName
        else -> null
    }

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.processing_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            ProgressRing(progress = progress)
            Spacer(Modifier.height(24.dp))
            if (fileName != null) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = stringResource(R.string.processing_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Column(Modifier.fillMaxWidth()) {
                stageLabels.forEachIndexed { i, label ->
                    StepRow(
                        label = label,
                        done = currentStage > i,
                        active = i == currentStage,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
