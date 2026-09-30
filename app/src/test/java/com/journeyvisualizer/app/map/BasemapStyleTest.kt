package com.journeyvisualizer.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 4 basemap style registry (§24 "Map Style").
 *
 * Verifies every offered style resolves to a real tile URL pattern and
 * carries attribution. The actual tile endpoints were verified live with
 * HTTP 200 on 2026-09-30; these tests guard the URL-building logic.
 */
class BasemapStyleTest {

    @Test fun `all four styles are registered`() {
        val styles = BasemapStyles.allStyles().map { it.style }.toSet()
        assertEquals(
            setOf(
                BasemapStyle.STANDARD,
                BasemapStyle.SATELLITE,
                BasemapStyle.HYBRID,
                BasemapStyle.TERRAIN,
            ),
            styles,
        )
    }

    @Test fun `every style has non-blank attribution`() {
        for (spec in BasemapStyles.allStyles()) {
            assertFalse(
                "missing attribution for ${spec.style}",
                spec.base.attribution.isBlank(),
            )
            spec.overlay?.let {
                assertFalse(
                    "missing overlay attribution for ${spec.style}",
                    it.attribution.isBlank(),
                )
            }
        }
    }

    @Test fun `standard uses the project's CARTO voyager tiles`() {
        val spec = BasemapStyles.specFor(BasemapStyle.STANDARD)
        assertTrue(spec.urlPattern().startsWith("https://basemaps.cartocdn.com/rastertiles/voyager/"))
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/voyager/3/4/2.png",
            spec.base.tileUrl(3, 4, 2),
        )
    }

    @Test fun `satellite uses esri tile ordering`() {
        val spec = BasemapStyles.specFor(BasemapStyle.SATELLITE)
        // z=3, x=4, y=2 must render as .../tile/3/2/4 (Esri order).
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/3/2/4",
            spec.base.tileUrl(3, 4, 2),
        )
    }

    @Test fun `hybrid composites satellite with a label overlay`() {
        val spec = BasemapStyles.specFor(BasemapStyle.HYBRID)
        assertNotNull(spec.overlay)
        assertTrue(spec.overlay!!.urlPattern.contains("World_Boundaries_and_Places"))
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/3/2/4",
            spec.overlay!!.tileUrl(3, 4, 2),
        )
    }

    @Test fun `terrain uses opentopomap with capped zoom`() {
        val spec = BasemapStyles.specFor(BasemapStyle.TERRAIN)
        assertTrue(spec.urlPattern().startsWith("https://tile.opentopomap.org/"))
        assertEquals(17, spec.base.maxZoom)
    }

    @Test fun `attribution combines base and overlay without duplication`() {
        val hybrid = BasemapStyles.attributionFor(BasemapStyle.HYBRID)
        // Same Esri attribution on both layers: must appear exactly once.
        assertEquals(1, hybrid.split("Esri").size - 1)
        assertTrue(BasemapStyles.attributionFor(BasemapStyle.STANDARD).contains("CARTO"))
    }

    private fun BasemapStyleSpec.urlPattern(): String = base.urlPattern
}
