package com.journeyvisualizer.app.ui.phase1.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.BasemapStyles
import com.journeyvisualizer.app.map.InteractiveMapController
import com.journeyvisualizer.app.map.MapPoint
import org.osmdroid.views.MapView

/**
 * The real interactive timeline map (Phase 4): actual osmdroid MapView with
 * pan, zoom, double-tap zoom, rotation, clustered markers, chronological
 * route, and start/end badges — driven by real Timeline coordinates.
 *
 * State flow: the caller owns [controller], [points], [style], and
 * [selectedIndex]; the controller only renders. Marker taps report stable
 * timeline indexes via [onMarkerClick]. Phase 5 drives the animation
 * overlays through the same [controller] from outside this composable.
 */
@Composable
fun TimelineMap(
    controller: InteractiveMapController,
    points: List<MapPoint>,
    style: BasemapStyle,
    selectedIndex: Int?,
    onMarkerClick: (index: Int) -> Unit,
    onStyleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Keep the tap callback fresh without rebuilding the controller.
    DisposableEffect(controller, onMarkerClick) {
        controller.setOnMarkerClickListener(onMarkerClick)
        onDispose { }
    }

    var offline by remember { mutableStateOf(!isOnline(context)) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFE8E4D8)),
    ) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).also { controller.attach(it) }
            },
            update = { controller.applyUiState(points, style, selectedIndex) },
            onRelease = { view ->
                controller.detach()
                view.onDetach()
            },
            modifier = Modifier.fillMaxSize(),
        )

        // MapView lifecycle → osmdroid tile management.
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        controller.onResumeView()
                        offline = !isOnline(context)
                    }
                    Lifecycle.Event.ON_PAUSE -> controller.onPauseView()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        // Attribution: required by every tile provider, always visible.
        Text(
            text = BasemapStyles.attributionFor(style),
            fontSize = 9.sp,
            color = Color(0xFF6B7280),
            textAlign = TextAlign.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 6.dp)
                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // Controls: fit/recenter + style picker entry.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapControlButton(
                icon = Icons.Filled.CenterFocusStrong,
                description = stringResource(R.string.map_fit_timeline),
                onClick = { controller.fitTimelineBounds(animated = true) },
            )
            Spacer(Modifier.height(8.dp))
            MapControlButton(
                icon = Icons.Filled.Layers,
                description = stringResource(R.string.map_change_style),
                onClick = onStyleClick,
            )
        }

        if (offline) {
            Surface(
                color = Color(0xFF1F2937).copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.map_offline),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** Push the declarative UI state into the imperative controller, diffed. */
private fun InteractiveMapController.applyUiState(
    points: List<MapPoint>,
    style: BasemapStyle,
    selectedIndex: Int?,
) {
    // The controller diffs all three: repeated recompositions are no-ops,
    // and the first real route triggers a one-time fitTimelineBounds.
    setMapStyle(style)
    setRoute(points)
    setSelectedPoint(selectedIndex)
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = Color(0xFF16A34A),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
