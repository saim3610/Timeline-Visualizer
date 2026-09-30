package com.journeyvisualizer.app.history

import com.journeyvisualizer.app.map.BasemapStyle

/**
 * Pure label helpers shared by history registration and the UI.
 * Kept Android-free so they are unit-testable on the JVM.
 */
object HistoryLabels {

    /** Canonical aspect label from export dimensions. */
    fun aspectLabel(width: Int, height: Int): String = when {
        width <= 0 || height <= 0 -> "?"
        width == height -> "1:1"
        width > height -> "16:9"
        else -> "9:16"
    }

    fun mapStyleLabel(style: BasemapStyle): String = when (style) {
        BasemapStyle.STANDARD -> "Standard"
        BasemapStyle.SATELLITE -> "Satellite"
        BasemapStyle.HYBRID -> "Hybrid"
        BasemapStyle.TERRAIN -> "Terrain"
    }
}
