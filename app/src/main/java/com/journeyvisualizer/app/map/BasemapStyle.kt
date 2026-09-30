package com.journeyvisualizer.app.map

/**
 * Basemap styles the app can genuinely render (Phase 4).
 *
 * Every entry here was verified live on 2026-09-30 against a real tile
 * endpoint. The map-style UI must only offer these styles — never present a
 * style the provider does not actually serve.
 */
enum class BasemapStyle {
    STANDARD,
    SATELLITE,
    HYBRID,
    TERRAIN,
}

/**
 * One raster tile layer. [urlPattern] uses `{z}/{x}/{y}` placeholders
 * (Esri services expect `{z}/{y}/{x}` — written out literally).
 */
data class TileLayerSpec(
    val urlPattern: String,
    val attribution: String,
    val minZoom: Int = 0,
    val maxZoom: Int = 19,
) {
    /** Build a concrete tile URL. No network access here — pure string work. */
    fun tileUrl(z: Int, x: Int, y: Int): String =
        urlPattern
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
}

/**
 * A selectable basemap: a base layer plus an optional transparent overlay
 * (Hybrid = satellite imagery + Esri reference labels/boundaries).
 */
data class BasemapStyleSpec(
    val style: BasemapStyle,
    val base: TileLayerSpec,
    val overlay: TileLayerSpec? = null,
)

/**
 * The only tile providers the app uses. CARTO Voyager is the provider the
 * video renderer ([TileCache]) already uses, so the interactive map and the
 * exporter share one consistent "Standard" look.
 *
 * Attribution strings are shown on the map at all times (osmdroid draws the
 * tile source copyright); they must not be removed or hidden.
 */
object BasemapStyles {

    private const val CARTO_ATTRIBUTION = "© OpenStreetMap contributors © CARTO"
    private const val ESRI_ATTRIBUTION = "Imagery © Esri, Maxar, Earthstar Geographics"
    private const val OTM_ATTRIBUTION =
        "© OpenStreetMap contributors, SRTM | style: © OpenTopoMap (CC-BY-SA)"

    private val standard = BasemapStyleSpec(
        style = BasemapStyle.STANDARD,
        base = TileLayerSpec(
            urlPattern = "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
            attribution = CARTO_ATTRIBUTION,
        ),
    )

    private val satellite = BasemapStyleSpec(
        style = BasemapStyle.SATELLITE,
        base = TileLayerSpec(
            // NOTE: Esri orders its tile path as z/y/x.
            urlPattern = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attribution = ESRI_ATTRIBUTION,
        ),
    )

    private val hybrid = BasemapStyleSpec(
        style = BasemapStyle.HYBRID,
        base = satellite.base,
        overlay = TileLayerSpec(
            // Transparent reference overlay: boundaries + place labels.
            urlPattern = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
            attribution = ESRI_ATTRIBUTION,
        ),
    )

    private val terrain = BasemapStyleSpec(
        style = BasemapStyle.TERRAIN,
        base = TileLayerSpec(
            urlPattern = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            attribution = OTM_ATTRIBUTION,
            // OpenTopoMap only renders up to zoom 17.
            maxZoom = 17,
        ),
    )

    private val all = mapOf(
        BasemapStyle.STANDARD to standard,
        BasemapStyle.SATELLITE to satellite,
        BasemapStyle.HYBRID to hybrid,
        BasemapStyle.TERRAIN to terrain,
    )

    /** All genuinely supported styles, in UI order. */
    fun allStyles(): List<BasemapStyleSpec> = all.values.toList()

    fun specFor(style: BasemapStyle): BasemapStyleSpec =
        all.getValue(style)

    /** Combined attribution for every layer a style draws. */
    fun attributionFor(style: BasemapStyle): String {
        val spec = specFor(style)
        return listOfNotNull(spec.base.attribution, spec.overlay?.attribution)
            .distinct()
            .joinToString(" · ")
    }
}
