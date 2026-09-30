package com.journeyvisualizer.app.ui.phase1.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.ui.phase1.MapStyle
import com.journeyvisualizer.app.ui.phase1.Phase1ViewModel
import com.journeyvisualizer.app.ui.phase1.components.CardRadius
import com.journeyvisualizer.app.ui.phase1.components.JVTopBar
import com.journeyvisualizer.app.ui.phase1.components.MapStyleCard
import com.journeyvisualizer.app.ui.phase1.components.PrimaryButton
import com.journeyvisualizer.app.ui.phase1.components.RadioOptionRow
import com.journeyvisualizer.app.ui.phase1.components.SectionHeader

// ---------------------------------------------------------------------------
// Screen 8 — Map Style selection.
// ---------------------------------------------------------------------------

@Composable
fun MapStyleScreen(
    ux: Phase1ViewModel,
    onBack: () -> Unit,
    onApplied: () -> Unit,
) {
    var pending by remember { mutableStateOf(ux.mapStyle) }
    val context = LocalContext.current

    Scaffold(topBar = { JVTopBar(title = stringResource(R.string.mapstyle_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapStyleCard(
                    style = MapStyle.STANDARD,
                    name = stringResource(R.string.style_standard),
                    selected = pending == MapStyle.STANDARD,
                    onClick = { pending = MapStyle.STANDARD },
                    modifier = Modifier.weight(1f),
                )
                MapStyleCard(
                    style = MapStyle.SATELLITE,
                    name = stringResource(R.string.style_satellite),
                    selected = pending == MapStyle.SATELLITE,
                    onClick = { pending = MapStyle.SATELLITE },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapStyleCard(
                    style = MapStyle.HYBRID,
                    name = stringResource(R.string.style_hybrid),
                    selected = pending == MapStyle.HYBRID,
                    onClick = { pending = MapStyle.HYBRID },
                    modifier = Modifier.weight(1f),
                )
                MapStyleCard(
                    style = MapStyle.TERRAIN,
                    name = stringResource(R.string.style_terrain),
                    selected = pending == MapStyle.TERRAIN,
                    onClick = { pending = MapStyle.TERRAIN },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(R.string.mapstyle_apply),
                onClick = {
                    ux.selectMapStyle(pending)
                    ux.persistMapStyle(context)
                    onApplied()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
