package com.journeyvisualizer.app.ui.phase1.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The app's single shared video player surface (platform VideoView +
 * MediaController: play/pause/seek/duration/position).
 *
 * Used by both the Phase 7 export-result screen and the Phase 8 video
 * detail screen — no second player framework.
 */
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
) {
    val context = LocalContext.current
    val videoView = remember(uri) {
        VideoView(context).apply {
            setVideoURI(uri)
            val controller = MediaController(context)
            controller.setAnchorView(this)
            setMediaController(controller)
        }
    }
    DisposableEffect(uri) {
        if (autoPlay) videoView.start()
        onDispose {
            videoView.stopPlayback()
        }
    }
    AndroidView(
        factory = { videoView },
        modifier = modifier.fillMaxWidth(),
    )
}
