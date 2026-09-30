package com.journeyvisualizer.app.animation

import com.journeyvisualizer.app.data.geo.MatchQuality
import com.journeyvisualizer.app.data.geo.ResolvedPoint

/**
 * The deterministic animation plan for one imported journey (Phase 5).
 *
 * Build once from [ResolvedPoint]s; query forever with [stateAt]. The build
 * sorts by timestamp (stable — duplicate timestamps keep file order),
 * drops points that can't be placed on a map, and compresses real durations
 * into animation durations per [AnimationConfig]. Nothing random, nothing
 * invented: the original [ResolvedPoint] stays reachable via
 * [AnimationPoint.source].
 */
class AnimationTimeline private constructor(
    /** Chronological, mappable animation points. */
    val points: List<AnimationPoint>,
    /** One segment per consecutive point pair. */
    val segments: List<AnimationSegment>,
    val config: AnimationConfig,
    /** Total animation length at 1x speed (ms). 0 when < 2 points. */
    val totalDurationMs: Long,
) {
    /** Number of animatable events. */
    val eventCount: Int get() = points.size

    /**
     * Pure, deterministic frame lookup: the same [positionMs] always yields
     * the same frame. O(log n) via binary search over segment boundaries —
     * seeking never replays from the beginning.
     */
    fun stateAt(positionMs: Long): AnimationFrameState {
        require(points.isNotEmpty()) { "AnimationTimeline has no points" }
        if (segments.isEmpty()) {
            // Single-point journey: the marker sits still, time is fixed.
            val p = points[0]
            return AnimationFrameState(
                positionMs = 0L,
                totalMs = 0L,
                segmentIndex = -1,
                fraction = 1.0,
                lat = p.lat,
                lng = p.lng,
                displayedTimestampMs = p.timestampMs,
                eventId = p.id,
                currentPoint = p,
                nextPoint = null,
                traveledPointCount = 1,
                overallFraction = 1.0,
            )
        }
        val pos = positionMs.coerceIn(0L, totalDurationMs)
        val segIdx = segmentIndexAt(pos).coerceIn(0, segments.size - 1)
        val seg = segments[segIdx]
        val span = (seg.endAnimMs - seg.startAnimMs).coerceAtLeast(1L)
        val fraction = ((pos - seg.startAnimMs).toDouble() / span).coerceIn(0.0, 1.0)
        val (lat, lng) = if (seg.isDwell) {
            seg.from.lat to seg.from.lng
        } else {
            AnimationMath.slerp(seg.from.lat, seg.from.lng, seg.to.lat, seg.to.lng, fraction)
        }
        // Displayed time interpolates between the REAL timestamps; the
        // records themselves are never rewritten.
        val displayedTs = seg.from.timestampMs +
            (fraction * (seg.to.timestampMs - seg.from.timestampMs)).toLong()
        val atEnd = fraction >= 1.0
        return AnimationFrameState(
            positionMs = pos,
            totalMs = totalDurationMs,
            segmentIndex = segIdx,
            fraction = fraction,
            lat = lat,
            lng = lng,
            displayedTimestampMs = displayedTs,
            eventId = if (atEnd) seg.to.id else seg.from.id,
            currentPoint = if (atEnd) seg.to else seg.from,
            nextPoint = if (atEnd) {
                points.getOrNull(segIdx + 2)
            } else {
                seg.to
            },
            traveledPointCount = segIdx + 1,
            overallFraction = if (totalDurationMs > 0) {
                pos.toDouble() / totalDurationMs
            } else 1.0,
        )
    }

    /**
     * Index of the segment containing [positionMs]. Segments partition
     * [0, totalDurationMs]; the final boundary belongs to the last segment.
     */
    fun segmentIndexAt(positionMs: Long): Int {
        if (segments.isEmpty()) return -1
        val pos = positionMs.coerceIn(0L, totalDurationMs)
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (pos < segments[mid].endAnimMs) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        /**
         * Build the animation plan. Returns null when no point can be placed
         * on a map (caller should surface an honest empty/error state).
         *
         * Points with invalid coordinates are skipped gracefully — the
         * events stay in the timeline data, they just can't be animated
         * geographically. Timestamps come from the normalized parser output
         * (strictly increasing there); the stable sort here is a defensive
         * second pass that also orders any equal timestamps by file order.
         */
        fun build(
            resolved: List<ResolvedPoint>,
            config: AnimationConfig = AnimationConfig(),
        ): AnimationTimeline? {
            val pts = resolved.mapIndexedNotNull { index, rp ->
                val lat = rp.point.lat
                val lng = rp.point.lng
                if (!lat.isFinite() || !lng.isFinite()) return@mapIndexedNotNull null
                if (lat !in -85.0511..85.0511 || lng !in -180.0..180.0) {
                    return@mapIndexedNotNull null
                }
                val loc = rp.location
                AnimationPoint(
                    id = index,
                    lat = lat,
                    lng = lng,
                    timestampMs = rp.point.timeMs,
                    city = loc.name,
                    country = loc.country,
                    isResolved = loc.matchQuality != MatchQuality.UNRESOLVED,
                    source = rp,
                )
            }.sortedBy { it.timestampMs } // stable: dup timestamps keep file order
            if (pts.isEmpty()) return null

            val segments = ArrayList<AnimationSegment>(maxOf(0, pts.size - 1))
            if (pts.size >= 2) {
                // Pass 1: real durations and distances.
                val realDurs = LongArray(pts.size - 1)
                val distances = DoubleArray(pts.size - 1)
                var totalReal = 0L
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val dur = (b.timestampMs - a.timestampMs).coerceAtLeast(0L)
                    val dist = AnimationMath.distanceM(a.lat, a.lng, b.lat, b.lng)
                    realDurs[i] = dur
                    distances[i] = dist
                    totalReal += dur
                }
                // Pass 2: deterministic compression into animation durations.
                var cursor = 0L
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val dwell = distances[i] < config.zeroDistanceM
                    val animMs = if (dwell) {
                        // Same place: keep the event, advance time, no jitter.
                        config.dwellMs
                    } else if (totalReal <= 0L) {
                        // All timestamps equal: split the target evenly.
                        (config.targetTotalMs / (pts.size - 1))
                            .coerceIn(config.minSegmentMs, config.maxSegmentMs)
                    } else {
                        val proportional =
                            realDurs[i].toDouble() / totalReal * config.targetTotalMs
                        proportional.toLong()
                            .coerceIn(config.minSegmentMs, config.maxSegmentMs)
                    }
                    val start = cursor
                    cursor += animMs
                    segments.add(
                        AnimationSegment(
                            from = a,
                            to = b,
                            realDurationMs = realDurs[i],
                            animDurationMs = animMs,
                            distanceM = distances[i],
                            isDwell = dwell,
                            startAnimMs = start,
                            endAnimMs = cursor,
                        )
                    )
                }
            }
            return AnimationTimeline(
                points = pts,
                segments = segments,
                config = config,
                totalDurationMs = segments.sumOf { it.animDurationMs },
            )
        }
    }
}
