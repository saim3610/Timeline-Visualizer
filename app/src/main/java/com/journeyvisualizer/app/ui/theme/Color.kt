package com.journeyvisualizer.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Phase 1 design system: professional green accent + neutral surfaces.
// All screens must reference these tokens (via MaterialTheme.colorScheme)
// instead of hardcoding colors, so dark mode stays a one-file change.
// ---------------------------------------------------------------------------

/** Primary brand green (light theme). */
val BrandGreen = Color(0xFF15803D)

/** Darker green for pressed/emphasis states on light theme. */
val BrandGreenDark = Color(0xFF166534)

/** Brighter green used as primary on dark theme. */
val BrandGreenLight = Color(0xFF4ADE80)

/** Soft green tint for hero cards / selected containers (light theme). */
val GreenTint = Color(0xFFEAF6EE)

/** Soft green tint for hero cards / selected containers (dark theme). */
val GreenTintDark = Color(0xFF12291A)

/** Success green for validation states and checkmarks. */
val SuccessGreen = Color(0xFF16A34A)

/** Neutral border color for cards (light theme). Very subtle. */
val CardBorder = Color(0xFFE2E8F0)

/** Neutral border color for cards (dark theme). */
val CardBorderDark = Color(0xFF26314B)

// ---------------------------------------------------------------------------
// Legacy tokens below. They are used by the map renderer, the export
// pipeline and the pre-Phase-1 screens — do not remove or repurpose them.
// ---------------------------------------------------------------------------

val RouteAccent = Color(0xFF38BDF8)
val RouteAccentDark = Color(0xFF0284C7)
val DestinationPink = Color(0xFFF472B6)

val DarkBackground = Color(0xFF0B1220)
val DarkSurface = Color(0xFF111A2E)
val DarkSurfaceVariant = Color(0xFF1A2540)
