package com.journeyvisualizer.app.ui.phase1.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.animation.AnimationEngine
import com.journeyvisualizer.app.animation.CameraFollowMode
import com.journeyvisualizer.app.animation.PlaybackState
import com.journeyvisualizer.app.map.RouteDrawMode
import com.journeyvisualizer.app.ui.util.formatDateTime

/**
 * Phase 5 journey playback panel: current-location card, timeline scrubber,
 * transport controls, speed selector, route mode and camera mode.
 *
 * The [AnimationEngine] is the single source of truth — this panel only
 * reads its flows and calls its methods; it keeps no playback state itself.
 */
@Composable
fun AnimationPanel(
    engine: AnimationEngine,
    routeMode: RouteDrawMode,
    onRouteModeChange: (RouteDrawMode) -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by engine.state.collectAsState()
    val frame by engine.frame.collectAsState()
    val speed by engine.speed.collectAsState()
    val followMode by engine.followMode.collectAsState()
    val total = engine.getTotalDuration()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // -- Current location -----------------------------------------
            val point = frame.currentPoint
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2563EB)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = point.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val sub = point.country
                        ?: "%.4f, %.4f".format(Locale.US, point.lat, point.lng)
                    Text(
                        text = "$sub · ${formatDateTime(frame.displayedTimestampMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // "Traveling to …" while moving between two distinct events.
            val next = frame.nextPoint
            if (state == PlaybackState.PLAYING && next != null && next.id != point.id) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.anim_traveling_to, next.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF16A34A),
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(8.dp))

            // -- Scrubber ---------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatAnimDuration(frame.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = frame.overallFraction.toFloat(),
                    onValueChange = { frac ->
                        engine.seekTo((frac * total).toLong())
                    },
                    enabled = total > 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF16A34A),
                        activeTrackColor = Color(0xFF16A34A),
                    ),
                )
                Text(
                    text = formatAnimDuration(total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
            }

            // -- Transport controls ------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = stringResource(R.string.anim_previous),
                    enabled = total > 0,
                    onClick = { engine.previousEvent() },
                )
                Spacer(Modifier.width(4.dp))
                when {
                    state == PlaybackState.COMPLETED -> TransportButton(
                        icon = Icons.Filled.Replay,
                        description = stringResource(R.string.anim_replay),
                        enabled = true,
                        prominent = true,
                        onClick = { engine.restart() },
                    )
                    state == PlaybackState.PLAYING -> TransportButton(
                        icon = Icons.Filled.Pause,
                        description = stringResource(R.string.anim_pause),
                        enabled = true,
                        prominent = true,
                        onClick = { engine.pause() },
                    )
                    else -> TransportButton(
                        icon = Icons.Filled.PlayArrow,
                        description = stringResource(R.string.anim_play),
                        enabled = total > 0 && state != PlaybackState.ERROR,
                        prominent = true,
                        onClick = { engine.play() },
                    )
                }
                Spacer(Modifier.width(4.dp))
                TransportButton(
                    icon = Icons.Filled.SkipNext,
                    description = stringResource(R.string.anim_next),
                    enabled = total > 0,
                    onClick = { engine.nextEvent() },
                )
                Spacer(Modifier.width(12.dp))
                TransportButton(
                    icon = Icons.Filled.Stop,
                    description = stringResource(R.string.anim_stop),
                    enabled = state == PlaybackState.PLAYING || state == PlaybackState.PAUSED,
                    onClick = { engine.stop() },
                )
                TransportButton(
                    icon = Icons.Filled.Replay,
                    description = stringResource(R.string.anim_restart),
                    enabled = total > 0 && frame.positionMs > 0,
                    onClick = { engine.restart() },
                )
            }

            Spacer(Modifier.height(8.dp))

            // -- Speed ---------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.anim_speed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (s in engine.timeline.config.speeds) {
                        SpeedChip(
                            label = "${formatSpeed(s)}×",
                            selected = speed == s,
                            onClick = { engine.setSpeed(s) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // -- Route + camera modes (one compact, scrollable row) ------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.anim_route_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                ModeChip(
                    label = stringResource(R.string.anim_route_full),
                    selected = routeMode == RouteDrawMode.FULL,
                    onClick = { onRouteModeChange(RouteDrawMode.FULL) },
                )
                Spacer(Modifier.width(6.dp))
                ModeChip(
                    label = stringResource(R.string.anim_route_progressive),
                    selected = routeMode == RouteDrawMode.PROGRESSIVE,
                    onClick = { onRouteModeChange(RouteDrawMode.PROGRESSIVE) },
                )
                Spacer(Modifier.width(12.dp))
                ModeChip(
                    label = stringResource(R.string.anim_follow),
                    selected = followMode == CameraFollowMode.FOLLOW,
                    onClick = { engine.setFollowMode(CameraFollowMode.FOLLOW) },
                )
                Spacer(Modifier.width(6.dp))
                ModeChip(
                    label = stringResource(R.string.anim_free),
                    selected = followMode == CameraFollowMode.FREE,
                    onClick = { engine.setFollowMode(CameraFollowMode.FREE) },
                )
                if (followMode == CameraFollowMode.FREE) {
                    Spacer(Modifier.width(6.dp))
                    ModeChip(
                        label = stringResource(R.string.anim_recenter),
                        selected = false,
                        onClick = onRecenter,
                        leadingIcon = Icons.Filled.MyLocation,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    prominent: Boolean = false,
) {
    val bg = if (prominent) Color(0xFF16A34A) else Color.Transparent
    val fg = if (prominent) Color.White else Color(0xFF16A34A)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(if (prominent) 52.dp else 44.dp)
            .clip(CircleShape)
            .background(if (prominent && enabled) bg else Color.Transparent),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) fg else Color(0xFF9CA3AF),
            modifier = Modifier.size(if (prominent) 28.dp else 24.dp),
        )
    }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF16A34A),
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = leadingIcon?.let {
            { Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFDCFCE7),
            selectedLabelColor = Color(0xFF15803D),
        ),
    )
}

private fun formatSpeed(s: Double): String =
    if (s == s.toLong().toDouble()) s.toLong().toString() else s.toString()

/** m:ss animation-clock duration. */
private fun formatAnimDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, totalSec / 60, totalSec % 60)
}
