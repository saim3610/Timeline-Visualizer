package com.journeyvisualizer.app.ui.phase1.mock

import com.journeyvisualizer.app.ui.phase1.components.MapPin

/**
 * Centralized mock/sample data for Phase 1 UI development.
 *
 * Everything here is clearly separated from production data: the real
 * Timeline import ([com.journeyvisualizer.app.data.TimelineParser] output)
 * drives the primary workflow, and these samples remain only for UI
 * previews, empty-state testing, and development fallback. Nothing in this
 * file is persisted.
 */
object MockData {

    /** Sample journey used across Phase 1 previews: Lahore → Skardu. */
    val events = listOf(
        MapPin("Lahore", "12 Mar 2024, 10:00 AM", 0.74f, 0.80f),
        MapPin("Islamabad", "14 Mar 2024, 02:30 PM", 0.63f, 0.56f),
        MapPin("Murree", "16 Mar 2024, 11:15 AM", 0.57f, 0.42f),
        MapPin("Skardu", "20 Mar 2024, 09:40 AM", 0.40f, 0.20f),
    )
}
