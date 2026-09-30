package com.journeyvisualizer.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.R
import com.journeyvisualizer.app.data.JourneyViewModel
import com.journeyvisualizer.app.map.CameraMode
import com.journeyvisualizer.app.map.FrameRenderer
import com.journeyvisualizer.app.map.JourneyEngine
import com.journeyvisualizer.app.map.TileCache
import com.journeyvisualizer.app.map.TileKey
import com.journeyvisualizer.app.map.TimeWarp
import com.journeyvisualizer.app.ui.util.formatVideoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ACCENT = 0xFF38BDF8

@Composable
fun PreviewScreen(viewModel: JourneyViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val importState by viewModel.importState.collectAsState()
    val loaded = importState as? JourneyViewModel.ImportState.Loaded

    if (loaded == null) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
                Text(stringResource(R.string.preview_import_first))
            }
        }
        return
    }

    val draft = viewModel.draft
    val warp = remember(draft.timeWarpKey) { TimeWarp.valueOf(draft.timeWarpKey) }
    val journey = remember(loaded, viewModel.draftRevision) {
        JourneyEngine(loaded.journey).filterRange(
            draft.startMs ?: loaded.journey.startMs,
            draft.endMs ?: loaded.journey.endMs,
        ).copy(title = draft.title.ifBlank { "My Journey" })
    }
    val engine = remember(journey, warp) { JourneyEngine(journey, warp) }
    val durationSec = draft.durationSec
    val cameraMode = remember(draft.cameraKey) { CameraMode.valueOf(draft.cameraKey) }
    val stops by viewModel.visitedCities.collectAsState()

    var videoTime by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(true) }
    val tileCache = remember { TileCache(context) }
    val tileStates = remember { mutableStateMapOf<TileKey, Bitmap>() }

    DisposableEffect(Unit) {
        onDispose { tileCache.clearMemory() }
    }

    // 60fps playback clock.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            val nt = (videoTime + dt).coerceAtMost(durationSec.toFloat())
            videoTime = nt
            if (nt >= durationSec) {
                playing = false
                break
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.coerceAtLeast(1)
        val h = constraints.maxHeight.coerceAtLeast(1)
        val cam = engine.cameraAt(
            videoTime.toDouble(), durationSec.toDouble(), cameraMode, w, h
        )

        // Load the tiles the current camera needs, in the background.
        val camKey = "${(cam.lat * 200).toInt()}_${(cam.lng * 200).toInt()}_${(cam.zoom * 4).toInt()}"
        LaunchedEffect(camKey) {
            val keys = engine.visibleTiles(cam, w, h)
            withContext(Dispatchers.IO) {
                for (k in keys) {
                    if (!tileStates.containsKey(k)) {
                        tileCache.getTile(k.z, k.x, k.y)?.let { tileStates[k] = it }
                    }
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            FrameRenderer.drawFrame(
                drawContext.canvas.nativeCanvas,
                w, h,
                engine,
                videoTime.toDouble(), durationSec.toDouble(),
                cameraMode,
                tileProvider = { tileStates[it] },
                title = journey.title,
                accentColor = ACCENT.toInt(),
                cities = stops,
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = Color.White
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Slider(
                    value = videoTime,
                    onValueChange = {
                        videoTime = it
                        playing = false
                    },
                    valueRange = 0f..durationSec.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (videoTime >= durationSec) videoTime = 0f
                        playing = !playing
                    }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (playing) R.string.cd_pause else R.string.cd_play
                            ),
                            tint = Color.White,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${formatVideoTime(videoTime)} / ${formatVideoTime(durationSec.toFloat())}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
