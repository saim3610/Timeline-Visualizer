package com.journeyvisualizer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TimelineParser] and its normalization layer.
 *
 * `org.json:json` is on the test classpath so these run without Robolectric.
 */
class TimelineParserTest {

    // -- Valid inputs -------------------------------------------------------

    @Test
    fun directArrayE7_parsesToDecimalDegrees() {
        val json = """
            [
              {"latitudeE7": 315204000, "longitudeE7": 743587000, "timestampMs": "1710237600000"},
              {"latitudeE7": 315208500, "longitudeE7": 743592000, "timestampMs": "1710239400000"}
            ]
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertNotNull(r.journey)
        assertEquals("direct", r.formatName)
        assertEquals(2, r.recordCount)
        assertEquals(2, r.pointCount)
        val p0 = r.journey!!.points[0]
        assertEquals(31.5204, p0.lat, 1e-6)
        assertEquals(74.3587, p0.lng, 1e-6)
        assertEquals(1710237600000L, p0.timeMs)
    }

    @Test
    fun semanticSegments_parsesTimelinePath() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2024-03-12T10:00:00Z",
                  "endTime": "2024-03-12T11:00:00Z",
                  "timelinePath": [
                    {"point": "31.5204,74.3587", "time": "2024-03-12T10:00:00Z"},
                    {"point": "31.5300,74.3600", "time": "2024-03-12T10:30:00Z"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals("semantic", r.formatName)
        assertEquals(1, r.recordCount)
        assertEquals(2, r.pointCount)
        val pts = r.journey!!.points
        assertEquals(1710237600000L, pts[0].timeMs)
        assertEquals(1710239400000L, pts[1].timeMs)
    }

    @Test
    fun decimalCoordinates_accepted() {
        val json = """[{"latitude": 31.5, "longitude": 74.3, "timestamp": "1710237600000"},
                       {"latitude": 31.6, "longitude": 74.4, "timestamp": "1710238200000"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals(2, r.pointCount)
        assertEquals(31.5, r.journey!!.points[0].lat, 1e-9)
    }

    @Test
    fun geoUriCoordinate_accepted() {
        val json = """[{"point": "geo:31.5204,74.3587", "timestamp": "1710237600000"},
                       {"point": "geo:31.5300,74.3600", "timestamp": "1710238200000"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals(2, r.pointCount)
        assertEquals(31.5204, r.journey!!.points[0].lat, 1e-6)
    }

    @Test
    fun missingTimestamps_interpolatedFromSegmentRange() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2024-03-12T10:00:00Z",
                  "endTime": "2024-03-12T12:00:00Z",
                  "timelinePath": [
                    {"latitudeE7": 315204000, "longitudeE7": 743587000},
                    {"latitudeE7": 315208500, "longitudeE7": 743592000}
                  ]
                }
              ]
            }
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        val pts = r.journey!!.points
        assertEquals(2, pts.size)
        assertEquals(1710237600000L, pts[0].timeMs)
        assertEquals(1710244800000L, pts[1].timeMs)
    }

    @Test
    fun visits_becomeDwellPoints() {
        val json = """
            {
              "semanticSegments": [
                {
                  "visit": {
                    "startTime": "2024-03-12T10:00:00Z",
                    "endTime": "2024-03-12T11:00:00Z",
                    "topCandidate": {"placeLocation": {"latLng": "31.5204,74.3587"}}
                  }
                }
              ]
            }
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals(2, r.pointCount) // start + end dwell points
    }

    // -- Coordinate validation ----------------------------------------------

    @Test
    fun invalidLatitude_skipped() {
        val json = """[{"latitudeE7": 910000000, "longitudeE7": 743587000, "timestampMs": "1710237600000"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
    }

    @Test
    fun invalidLongitude_skipped() {
        val json = """[{"latitude": 31.5, "longitude": 200.0, "timestamp": "1710237600000"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
    }

    @Test
    fun outlierFiltering_countsImplausibleJumps() {
        // ~140 km in one second: impossible, must be filtered.
        val json = """[{"latitude": 31.5, "longitude": 74.3, "timestamp": "1710237600000"},
                       {"latitude": 32.5, "longitude": 75.3, "timestamp": "1710237601000"},
                       {"latitude": 31.5001, "longitude": 74.3001, "timestamp": "1710238200000"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNotNull(r.journey)
        assertEquals(1, r.outlierCount)
        assertEquals(2, r.pointCount)
        assertTrue(r.warnings.any { it.contains("1 implausible GPS point") })
        assertEquals(1, r.rawPointCount - r.pointCount)
    }

    // -- Timestamp handling --------------------------------------------------

    @Test
    fun isoTimestamp_parsed() {
        val json = """[{"latitudeE7": 315204000, "longitudeE7": 743587000, "time": "2024-03-12T10:00:00+05:00"},
                       {"latitudeE7": 315208500, "longitudeE7": 743592000, "time": "2024-03-12T10:10:00+05:00"}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        // 10:00+05:00 == 05:00Z == 1710219600000
        assertEquals(1710219600000L, r.journey!!.points[0].timeMs)
    }

    // -- Structure / edge cases ----------------------------------------------

    @Test
    fun emptyArray_noPoints() {
        val r = TimelineParser.parseText("[]", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
    }

    @Test
    fun emptyObject_notTimeline() {
        val r = TimelineParser.parseText("{}", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NOT_TIMELINE, r.failureReason)
    }

    @Test
    fun invalidJson_notJson() {
        val r = TimelineParser.parseText("{not json", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NOT_JSON, r.failureReason)
    }

    @Test
    fun plainText_notJson() {
        val r = TimelineParser.parseText("hello world", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NOT_JSON, r.failureReason)
    }

    @Test
    fun unsupportedStructure_notTimeline() {
        val r = TimelineParser.parseText("""{"foo": 1}""", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NOT_TIMELINE, r.failureReason)
    }

    @Test
    fun noGeoPoints_noPoints() {
        val r = TimelineParser.parseText("""[{"a": 1}, {"b": 2}]""", "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
    }

    @Test
    fun recordsWithoutTime_areSkipped() {
        // Direct-array entries with no time and no segment range carry no
        // timestamp, so they are ignored rather than crashing.
        val json = """[{"latitudeE7": 315204000, "longitudeE7": 743587000}]"""
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
    }

    @Test
    fun largeDataset_parses() {
        val sb = StringBuilder("[")
        val base = 1710237600000L
        val count = 20_000
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append("""{"latitudeE7":${315204000 + i},"longitudeE7":743587000,"timestampMs":"${base + i * 1000L}"}""")
        }
        sb.append(']')
        val r = TimelineParser.parseText(sb.toString(), "big.json")

        assertNull(r.failureReason)
        assertEquals(count, r.recordCount)
        assertEquals(count, r.pointCount)
    }

    @Test
    fun progressCallback_reportsMonotonicProgress() {
        val json = """[{"latitude": 31.5, "longitude": 74.3, "timestamp": "1710237600000"},
                       {"latitude": 31.6, "longitude": 74.4, "timestamp": "1710238200000"}]"""
        val seen = mutableListOf<Float>()
        TimelineParser.parseText(json, "test.json") { seen.add(it) }

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it in 0f..1f })
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    // -- Classic Takeout "Location History" shape ---------------------------

    @Test
    fun locationsWrapper_parsesE7Points() {
        val json = """
            {
              "locations": [
                {"timestampMs": "1710237600000", "latitudeE7": 315204000, "longitudeE7": 743587000, "accuracy": 20},
                {"timestampMs": "1710238200000", "latitudeE7": 315300000, "longitudeE7": 743700000, "accuracy": 20}
              ]
            }
        """.trimIndent()
        val r = TimelineParser.parseText(json, "Location History.json")

        assertNull(r.failureReason)
        assertNotNull(r.journey)
        assertEquals("locations", r.formatName)
        assertEquals(2, r.recordCount)
        assertEquals(2, r.pointCount)
        val p0 = r.journey!!.points[0]
        assertEquals(31.5204, p0.lat, 1e-6)
        assertEquals(74.3587, p0.lng, 1e-6)
        assertEquals(1710237600000L, p0.timeMs)
    }

    @Test
    fun locationsWrapper_emptyArray_reportsNoPoints() {
        val r = TimelineParser.parseText("""{"locations": []}""", "Location History.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NO_POINTS, r.failureReason)
        assertEquals("locations", r.formatName)
        assertEquals(0, r.recordCount)
    }

    @Test
    fun objectWithoutKnownArrays_stillNotTimeline() {
        val r = TimelineParser.parseText("""{"foo": "bar"}""", "weird.json")

        assertNull(r.journey)
        assertEquals(FailureReason.NOT_TIMELINE, r.failureReason)
    }

    @Test
    fun semanticSegments_stillPreferredOverLocations() {
        val json = """
            {
              "semanticSegments": [
                {"point": "31.5204,74.3587", "time": "2024-03-12T10:00:00Z"},
                {"point": "31.5300,74.3600", "time": "2024-03-12T10:30:00Z"}
              ],
              "locations": [
                {"timestampMs": "1710237600000", "latitudeE7": 315204000, "longitudeE7": 743587000}
              ]
            }
        """.trimIndent()
        val r = TimelineParser.parseText(json, "both.json")

        assertEquals("semantic", r.formatName)
        assertEquals(2, r.recordCount)
    }

    // -- Shorthand coordinate spellings -------------------------------------

    @Test
    fun directArray_latLng_parses() {
        val json = """
            [
              {"lat": 31.5204, "lng": 74.3587, "timestampMs": "1710237600000"},
              {"lat": 31.5300, "lng": 74.3600, "timestampMs": "1710238200000"}
            ]
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals(2, r.pointCount)
        assertEquals(31.5204, r.journey!!.points[0].lat, 1e-9)
        assertEquals(74.3587, r.journey!!.points[0].lng, 1e-9)
    }

    @Test
    fun directArray_latLon_parses() {
        val json = """
            [
              {"lat": 31.5204, "lon": 74.3587, "timestampMs": "1710237600000"},
              {"lat": 31.5300, "lon": 74.3600, "timestampMs": "1710238200000"}
            ]
        """.trimIndent()
        val r = TimelineParser.parseText(json, "test.json")

        assertNull(r.failureReason)
        assertEquals(2, r.pointCount)
    }
}
