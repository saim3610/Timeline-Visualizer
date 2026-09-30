package com.journeyvisualizer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.CAMERA_LABELS
import com.journeyvisualizer.app.data.JourneyViewModel
import com.journeyvisualizer.app.data.TIME_WARP_LABELS
import com.journeyvisualizer.app.data.VIDEO_FORMATS
import com.journeyvisualizer.app.data.VIDEO_FORMAT_LABELS
import com.journeyvisualizer.app.ui.components.OptionDropdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: JourneyViewModel) {
    val scope = rememberCoroutineScope()
    val settings = viewModel.settings

    val unit by settings.distanceUnit.collectAsState(initial = "km")
    val defDuration by settings.defaultDurationSec.collectAsState(initial = 60)
    val format by settings.videoFormat.collectAsState(initial = "SQUARE_1080")
    val camera by settings.cameraMode.collectAsState(initial = "STEADY")
    val warp by settings.defaultTimeWarp.collectAsState(initial = "LINEAR")
    val template by settings.titleTemplate.collectAsState(initial = "My Journey {year}")
    var templateText by remember(template) { mutableStateOf(template) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_video_defaults),
                        style = MaterialTheme.typography.titleSmall
                    )

                    OptionDropdown(
                        label = stringResource(R.string.settings_distance_unit),
                        options = listOf("km", "mi"),
                        selected = unit,
                        labelOf = {
                            stringResource(if (it == "km") R.string.unit_km else R.string.unit_mi)
                        },
                        onSelect = { scope.launch { settings.setDistanceUnit(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        stringResource(R.string.settings_default_length, defDuration),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = defDuration.toFloat(),
                        onValueChange = {
                            scope.launch { settings.setDefaultDurationSec(it.toInt()) }
                        },
                        valueRange = 10f..300f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OptionDropdown(
                        label = stringResource(R.string.settings_default_format),
                        options = VIDEO_FORMATS.keys.toList(),
                        selected = format,
                        labelOf = { stringResource(VIDEO_FORMAT_LABELS[it] ?: R.string.fmt_square_1080) },
                        onSelect = { scope.launch { settings.setVideoFormat(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OptionDropdown(
                        label = stringResource(R.string.settings_default_camera),
                        options = CAMERA_LABELS.keys.toList(),
                        selected = camera,
                        labelOf = { stringResource(CAMERA_LABELS[it] ?: R.string.camera_steady) },
                        onSelect = { scope.launch { settings.setCameraMode(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OptionDropdown(
                        label = stringResource(R.string.settings_default_pace),
                        options = TIME_WARP_LABELS.keys.toList(),
                        selected = warp,
                        labelOf = { stringResource(TIME_WARP_LABELS[it] ?: R.string.pace_linear) },
                        onSelect = { scope.launch { settings.setDefaultTimeWarp(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = templateText,
                        onValueChange = {
                            templateText = it
                            scope.launch { settings.setTitleTemplate(it) }
                        },
                        label = { Text(stringResource(R.string.settings_title_template)) },
                        supportingText = { Text(stringResource(R.string.settings_template_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(onClick = {
                        scope.launch {
                            settings.setDistanceUnit("km")
                            settings.setDefaultDurationSec(60)
                            settings.setVideoFormat("SQUARE_1080")
                            settings.setCameraMode("STEADY")
                            settings.setDefaultTimeWarp("LINEAR")
                            settings.setTitleTemplate("My Journey {year}")
                        }
                    }) {
                        Text(stringResource(R.string.settings_restore))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_privacy),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_privacy_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(R.string.settings_about_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
