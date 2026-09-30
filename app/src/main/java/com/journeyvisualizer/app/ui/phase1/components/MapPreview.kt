package com.journeyvisualizer.app.ui.phase1.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyvisualizer.app.ui.phase1.MapStyle
import com.journeyvisualizer.app.ui.phase1.mock.MockData
import com.journeyvisualizer.app.data.geo.ResolvedLocation
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.ui.phase1.map.MapStyleThumbnail
import kotlin.math.min
import kotlin.random.Random

/**
 * A labeled pin on the decorative map preview.
 *
 * This is a plain UI data holder, not mock data: Phase 2 builds these from
 * real parsed coordinates ([com.journeyvisualizer.app.ui.phase1.toPreviewPins]),
 * while [MockData] keeps sample instances for UI previews only.
 */
data class MapPin(
    val name: String,
    val dateTime: String,
    /** Position as a fraction of the map preview (0..1, 0..1). */
    val x: Float,
    val y: Float,
    /**
     * Resolved geographic metadata for this pin (Phase 3). Null only for
     * sample/mock pins — real pins always carry a location, possibly
     * UNRESOLVED, so the UI can show honest status instead of guessing.
     */
    val location: ResolvedLocation? = null,
    /**
     * Stable index into the timeline's resolved-point list (Phase 4). -1 for
     * sample/mock pins that have no timeline behind them.
     */
    val pointIndex: Int = -1,
)

// ---------------------------------------------------------------------------
// Decorative pseudo-map used across Phase 1 previews.
//
// This is clearly-identified sample map content only (see MockData): the real
// map implementation (TileCache / FrameRenderer) is untouched and will be
// wired in during a later phase. The drawing is deterministic so previews
// look stable between recompositions.
// ---------------------------------------------------------------------------

private data class MapPalette(
    val background: Color,
    val blobA: Color,
    val blobB: Color,
    val grid: Color,
    val route: Color,
    val contour: Color? = null,
)

private fun paletteFor(style: MapStyle): MapPalette = when (style) {
    MapStyle.STANDARD -> MapPalette(
        background = Color(0xFFEDE9DA),
        blobA = Color(0xFFE3DCC6),
        blobB = Color(0xFFD9E4C4),
        grid = Color(0xFFFFFFFF),
        route = Color(0xFF2E9BF0),
    )
    MapStyle.SATELLITE -> MapPalette(
        background = Color(0xFF232E1E),
        blobA = Color(0xFF2C3A25),
        blobB = Color(0xFF3A3122),
        grid = Color(0xFF3A4A33).copy(alpha = 0.6f),
        route = Color(0xFF38BDF8),
    )
    MapStyle.HYBRID -> MapPalette(
        background = Color(0xFF26311F),
        blobA = Color(0xFF303D26),
        blobB = Color(0xFF403824),
        grid = Color(0xFFFFFFFF).copy(alpha = 0.55f),
        route = Color(0xFF38BDF8),
    )
    MapStyle.TERRAIN -> MapPalette(
        background = Color(0xFFF1ECDF),
        blobA = Color(0xFFE7E0CB),
        blobB = Color(0xFFDFE6CC),
        grid = Color(0xFFFFFFFF),
        route = Color(0xFF2E9BF0),
        contour = Color(0xFFD5CCB2),
    )
}

/** Waypoint dot colors, cycling like the reference design. */
private val WaypointColors = listOf(
    Color(0xFF16A34A),
    Color(0xFF2E9BF0),
    Color(0xFF8B5CF6),
    Color(0xFFF97316),
)

@Composable
fun MapPreview(
    style: MapStyle,
    modifier: Modifier = Modifier,
    events: List<MapPin> = MockData.events,
    showRoute: Boolean = true,
    showLabels: Boolean = true,
    showPins: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val palette = paletteFor(style)
    // Deterministic decorative blobs.
    val blobs = remember(style) {
        val rnd = Random(style.ordinal * 1000 + 7)
        List(9) {
            Triple(
                Offset(rnd.nextFloat(), rnd.nextFloat()),
                0.18f + rnd.nextFloat() * 0.30f,
                rnd.nextBoolean(),
            )
        }
    }
    val gridLines = remember { List(6) { (it + 1) / 7f } }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(CardRadius))
            .background(palette.background),
    ) {
        val maxW = maxWidth
        val maxH = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Decorative landmass blobs.
            for ((pos, rFrac, useA) in blobs) {
                val r = min(w, h) * rFrac
                drawCircle(
                    color = if (useA) palette.blobA else palette.blobB,
                    radius = r,
                    center = Offset(pos.x * w, pos.y * h),
                )
            }
            // Faint road grid.
            for (f in gridLines) {
                drawLine(
                    color = palette.grid,
                    start = Offset(f * w, 0f),
                    end = Offset(f * w, h),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = palette.grid,
                    start = Offset(0f, f * h),
                    end = Offset(w, f * h),
                    strokeWidth = 2f,
                )
            }
            // Terrain contours.
            palette.contour?.let { contour ->
                for (i in 1..5) {
                    drawArc(
                        color = contour,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * 0.05f, h * (0.05f + i * 0.02f)),
                        size = Size(w * 0.9f, h * 0.55f),
                        style = Stroke(3f),
                    )
                }
            }

            val pts = events.map { Offset(it.x * w, it.y * h) }

            // Route: white casing + colored inner line.
            if (showRoute && pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(14f))
                drawPath(path, palette.route, style = Stroke(8f))
            }

            // Waypoint pins.
            if (showPins) {
                pts.forEachIndexed { i, p ->
                    val c = WaypointColors[i % WaypointColors.size]
                    drawCircle(Color.White, radius = 17f, center = p)
                    drawCircle(c, radius = 11f, center = p)
                    drawCircle(Color.White, radius = 4f, center = p)
                }
            }
        }

        // Location labels as real text (respects font scaling).
        if (showLabels) {
            events.forEach { e ->
                val labelX = (maxW * e.x - 44.dp).coerceIn(4.dp, maxW - 92.dp)
                val labelY = (maxH * e.y - 66.dp).coerceAtLeast(4.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = labelX, y = labelY)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.94f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Column {
                        Text(
                            text = e.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0F172A),
                        )
                        Text(
                            text = e.dateTime,
                            fontSize = 10.sp,
                            color = Color(0xFF475569),
                        )
                    }
                }
            }
        }

        overlay()
    }
}

// ---------------------------------------------------------------------------
// Map style selection card with a live mini preview.
// ---------------------------------------------------------------------------

@Composable
fun MapStyleCard(
    style: MapStyle,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(CardRadius))
            .border(2.dp, borderColor, RoundedCornerShape(CardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            // Real tile thumbnail from the style's provider (Phase 4) —
            // never a decorative fake.
            MapStyleThumbnail(
                style = BasemapStyle.valueOf(style.name),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(SmallRadius)),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// App logo: green rounded square with a white map-pin + play mark.
// ---------------------------------------------------------------------------

@Composable
fun AppLogo(
    size: androidx.compose.ui.unit.Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    val green = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(green),
    ) {
        val w = size.toPx()
        val h = size.toPx()
        // Map pin.
        val pinPath = Path().apply {
            val cx = w * 0.5f
            val cy = h * 0.40f
            val r = w * 0.26f
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = cx - r, top = cy - r, right = cx + r, bottom = cy + r,
                )
            )
            moveTo(cx - r * 0.86f, cy + r * 0.5f)
            lineTo(cx, cy + r * 1.55f)
            lineTo(cx + r * 0.86f, cy + r * 0.5f)
            close()
        }
        drawPath(pinPath, Color.White)
        // Play triangle inside the pin.
        val tri = Path().apply {
            val cx = w * 0.5f
            val cy = h * 0.40f
            val s = w * 0.11f
            moveTo(cx - s * 0.6f, cy - s)
            lineTo(cx + s, cy)
            lineTo(cx - s * 0.6f, cy + s)
            close()
        }
        drawPath(tri, green)
    }
}
