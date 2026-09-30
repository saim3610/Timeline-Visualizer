package com.journeyvisualizer.app.ui.phase1.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.journeyvisualizer.app.map.BasemapStyle
import com.journeyvisualizer.app.map.TileImageFetcher

/**
 * A real map tile rendered as a style-card thumbnail (Phase 4).
 *
 * Shows actual tile data from the style's provider — never a decorative
 * fake. While loading (or offline) a neutral placeholder is shown; the card
 * still names the style honestly.
 */
@Composable
fun MapStyleThumbnail(
    style: BasemapStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(style) { mutableStateOf<Bitmap?>(null) }
    var attempted by remember(style) { mutableStateOf(false) }

    LaunchedEffect(style) {
        bitmap = TileImageFetcher.fetchStyleThumbnail(context.applicationContext, style)
        attempted = true
    }

    Box(
        modifier = modifier.background(Color(0xFFE8E4D8)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!attempted) {
            CircularProgressIndicator(
                color = Color(0xFF16A34A),
                strokeWidth = 2.dp,
            )
        }
        // else: keep the neutral placeholder — no fake map implied.
    }
}
