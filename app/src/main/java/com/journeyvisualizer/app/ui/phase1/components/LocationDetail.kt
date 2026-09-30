package com.journeyvisualizer.app.ui.phase1.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.geo.MatchQuality
import com.journeyvisualizer.app.data.geo.ResolvedLocation
import java.util.Locale

/**
 * Location details, built for reuse by the future real map screen (Phase 4).
 *
 * Shows only fields that actually exist: the resolved place and country when
 * known, the exact coordinates, and how far the known place is from the
 * coordinate. Distant matches are labeled approximate — never presented as
 * exact. Technical GeoNames details stay out of the UI (logs/debug only).
 */
@Composable
fun LocationDetailCard(
    location: ResolvedLocation,
    modifier: Modifier = Modifier,
    /** Formatted event date/time (Phase 4 marker taps); null hides the row. */
    dateTime: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = location.name
                            ?: stringResource(R.string.location_unavailable_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    location.country?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                MatchQualityChip(quality = location.matchQuality)
            }

            Spacer(Modifier.height(12.dp))

            // Exact coordinates — always shown; the map engine needs these later.
            Text(
                text = formatCoordinate(location.latitude, location.longitude),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            if (dateTime != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dateTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (location.isResolved) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (location.matchQuality == MatchQuality.APPROXIMATE) {
                        stringResource(
                            R.string.location_approx_distance,
                            formatKm(location.distanceKm),
                        )
                    } else {
                        stringResource(
                            R.string.location_near_distance,
                            formatKm(location.distanceKm),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.location_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Small status chip: Resolved / Approximate / Unavailable. */
@Composable
fun MatchQualityChip(
    quality: MatchQuality,
    modifier: Modifier = Modifier,
) {
    val (label, container, content) = when (quality) {
        MatchQuality.CLOSE -> Triple(
            stringResource(R.string.location_status_resolved),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        MatchQuality.APPROXIMATE -> Triple(
            stringResource(R.string.location_status_approximate),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        MatchQuality.UNRESOLVED -> Triple(
            stringResource(R.string.location_status_unresolved),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Tap-a-pin dialog used by the Timeline Preview event list. */
@Composable
fun LocationDetailDialog(
    location: ResolvedLocation,
    onDismiss: () -> Unit,
    dateTime: String? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        LocationDetailCard(location = location, dateTime = dateTime)
    }
}

/** "31.5204° N, 74.3587° E". */
fun formatCoordinate(lat: Double, lng: Double): String {
    val latHemi = if (lat >= 0) "N" else "S"
    val lngHemi = if (lng >= 0) "E" else "W"
    return String.format(
        Locale.US, "%.4f° %s, %.4f° %s",
        kotlin.math.abs(lat), latHemi, kotlin.math.abs(lng), lngHemi,
    )
}

/** "1.2 km" / "850 m". */
fun formatKm(km: Double): String = when {
    km.isNaN() -> "—"
    km < 1.0 -> String.format(Locale.US, "%d m", (km * 1000).toInt().coerceAtLeast(0))
    km < 10.0 -> String.format(Locale.US, "%.1f km", km)
    else -> String.format(Locale.US, "%,.0f km", km)
}
